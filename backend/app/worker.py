import json
import os
import shutil
import subprocess
import tempfile
import threading
import time

from . import db
from .llm import LLM, ProviderError
from .transcription import transcribe

MAX_BYTES = int(os.getenv("TLDR_MAX_BYTES", str(150 * 1024 * 1024)))
MAX_SECONDS = int(os.getenv("TLDR_MAX_DURATION", "1800"))


class Cancelled(Exception):
    pass


def cancelled(job_id):
    state = db.get(job_id)
    return state is None or bool(state["cancel_requested"])


def command(args, job_id, timeout=600):
    proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    deadline = time.monotonic() + timeout
    while True:
        try:
            out, err = proc.communicate(timeout=0.5)
            break
        except subprocess.TimeoutExpired:
            if cancelled(job_id) or time.monotonic() > deadline:
                proc.kill()
                proc.communicate()
                if cancelled(job_id):
                    raise Cancelled()
                raise RuntimeError("This operation took too long. Try again later.")
    if proc.returncode:
        # Avoid returning command output, which can contain credentials and private URLs.
        detail = err.decode("utf-8", "replace").lower()
        if "ip address is blocked" in detail:
            raise RuntimeError("The video platform blocked this server's IP address. Try another video or upload an authorized file.")
        if "login required" in detail or "sign in" in detail or "private video" in detail:
            raise RuntimeError("This video is private or requires sign-in. Upload an authorized file instead.")
        if "geo-restricted" in detail or "not available in your country" in detail:
            raise RuntimeError("This video is not available in the server's region.")
        raise RuntimeError("Media could not be retrieved or decoded. It may be private, unavailable, restricted, or unsupported.")
    return out


def run_job(job):
    job_id = job["id"]
    started = time.monotonic()
    timings = {}
    if job["transcript"]:
        transcript = json.loads(job["transcript"])
        db.update(job_id, status="summarizing", error=None)
        summary = LLM().summarize_long(transcript["text"], job_id).model_dump()
        if cancelled(job_id):
            raise Cancelled()
        db.update(job_id, status="completed", summary=summary, timings={"retry_summary": time.monotonic() - started})
        if job["upload_path"]:
            os.unlink(job["upload_path"])
        return
    with tempfile.TemporaryDirectory(prefix="tldr-") as temp:
        if job["upload_path"]:
            source = job["upload_path"]
            metadata = {"title": "Uploaded media", "platform": "upload"}
        else:
            # The proxy validates every CONNECT target, including redirects and CDN hosts.
            # Production deployments must additionally isolate extractor egress.
            proxy = os.getenv("TLDR_EXTRACTOR_PROXY")
            if not proxy or os.getenv("TLDR_ISOLATED_NETWORK") != "1":
                raise RuntimeError("Video link processing is disabled until extractor network isolation is configured. Upload a media file instead.")
            if proxy:
                base = ["yt-dlp", "--ignore-config", "--no-playlist", "--no-cache-dir", "--proxy", proxy, "--downloader", "native", "--max-filesize", str(MAX_BYTES), "--socket-timeout", "20"]
                db.update(job_id, status="fetching_metadata")
                tick = time.monotonic()
                info = json.loads(command(base + ["--dump-single-json", "--skip-download", "--", job["url"]], job_id, 120))
                timings["metadata"] = time.monotonic() - tick
                if (info.get("duration") or 0) > MAX_SECONDS:
                    raise RuntimeError("This video exceeds the configured duration limit.")
                metadata = {k: info.get(k) for k in ("id", "title", "uploader", "duration", "thumbnail", "webpage_url", "extractor")}
                db.update(job_id, status="downloading", metadata=metadata)
                tick = time.monotonic()
                command(base + ["-f", "bestaudio/best", "-o", os.path.join(temp, "input.%(ext)s"), "--", job["url"]], job_id)
                timings["download"] = time.monotonic() - tick
                files = [os.path.join(temp, f) for f in os.listdir(temp) if f.startswith("input.") and not f.endswith(".part")]
                if len(files) != 1 or os.path.getsize(files[0]) > MAX_BYTES:
                    raise RuntimeError("The media is missing or exceeds the size limit.")
                source = files[0]
        db.update(job_id, status="extracting_audio", metadata=metadata)
        tick = time.monotonic()
        # Restrict protocols so a crafted uploaded playlist cannot fetch network resources.
        probe = command(["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration:stream=codec_type", "-of", "json", source], job_id, 60)
        info = json.loads(probe)
        if not any(x.get("codec_type") == "audio" for x in info.get("streams", [])):
            raise RuntimeError("No audio track was found.")
        if float(info.get("format", {}).get("duration") or 0) > MAX_SECONDS:
            raise RuntimeError("This media exceeds the configured duration limit.")
        audio = os.path.join(temp, "audio.wav")
        command(["ffmpeg", "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", source, "-vn", "-ac", "1", "-ar", "16000", "-t", str(MAX_SECONDS), "-y", audio], job_id)
        timings["audio"] = time.monotonic() - tick
        db.update(job_id, status="transcribing")
        tick = time.monotonic()
        transcript = transcribe(audio)
        timings["transcription"] = time.monotonic() - tick
        if cancelled(job_id):
            raise Cancelled()
        db.update(job_id, status="summarizing", transcript=transcript)
        tick = time.monotonic()
        summary = LLM().summarize_long(transcript["text"], job_id).model_dump()
        timings["llm"] = time.monotonic() - tick
        timings["total"] = time.monotonic() - started
        if cancelled(job_id):
            raise Cancelled()
        db.update(job_id, status="completed", summary=summary, timings=timings)
    if job["upload_path"]:
        os.unlink(job["upload_path"])


def loop(stop):
    while not stop.is_set():
        with db.connection() as c:
            c.execute("BEGIN IMMEDIATE")
            row = c.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created_at, rowid LIMIT 1").fetchone()
            if row:
                c.execute("UPDATE jobs SET status='fetching_metadata' WHERE id=? AND status='queued'", (row["id"],))
        if not row:
            stop.wait(1)
            continue
        try:
            run_job(dict(row))
        except Cancelled:
            db.update(row["id"], status="cancelled", error=None)
        except (RuntimeError, ValueError, ProviderError) as exc:
            db.update(row["id"], status="failed", error=str(exc))
        except Exception:
            import logging
            logging.exception("Job failed: %s", row["id"])
            db.update(row["id"], status="failed", error="Processing failed. Please retry.")


def start_worker():
    stop = threading.Event()
    threading.Thread(target=loop, args=(stop,), daemon=True).start()
    return stop
