import json
import threading
from types import SimpleNamespace

import pytest

from app import db, worker


def test_worker_completes_uploaded_job(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB", str(tmp_path / "jobs.sqlite"))
    db.initialize()
    media = tmp_path / "upload"
    media.write_bytes(b"fake-media")
    with db.connection() as c:
        c.execute("INSERT INTO jobs(id,status,upload_path) VALUES(?,?,?)", ("job", "queued", str(media)))
        job = dict(c.execute("SELECT * FROM jobs WHERE id='job'").fetchone())
    def fake_command(args, *rest):
        if args[0] == "ffprobe":
            return json.dumps({"streams": [{"codec_type": "audio"}], "format": {"duration": "2"}}).encode()
        return b""
    monkeypatch.setattr(worker, "command", fake_command)
    monkeypatch.setattr(worker, "transcribe", lambda path: {"text": "Hello", "segments": [], "language": "en"})
    class Summary:
        def model_dump(self): return {"title": "Hello", "summary": "Hello"}
    class FakeLLM:
        def summarize_long(self, text, session): return Summary()
    monkeypatch.setattr(worker, "LLM", FakeLLM)
    worker.run_job(job)
    result = db.get("job")
    assert result["status"] == "completed"
    assert result["transcript"]["text"] == "Hello"
    assert result["summary"]["title"] == "Hello"
    assert not media.exists()


def test_missing_source_post_has_actionable_error(monkeypatch):
    process = SimpleNamespace(returncode=1, communicate=lambda **_: (b"", b"HTTP Error 404: Not Found"))
    monkeypatch.setattr(worker.subprocess, "Popen", lambda *args, **kwargs: process)
    with pytest.raises(RuntimeError, match="original post or video is no longer available"):
        worker.command(["yt-dlp", "https://x.com/example/status/123"], "job")
