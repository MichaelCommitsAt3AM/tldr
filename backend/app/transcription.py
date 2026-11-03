"""Single-instance Whisper integration. Call from a worker thread, not an event loop."""
from functools import lru_cache
from threading import Lock
from time import monotonic

_lock = Lock()


@lru_cache(maxsize=1)
def model():
    from faster_whisper import WhisperModel
    return WhisperModel("base", device="cpu", compute_type="int8", cpu_threads=2, num_workers=1)


def transcribe(path: str) -> dict:
    with _lock:
        started = monotonic()
        segments, info = model().transcribe(path, beam_size=5, vad_filter=True)
        rows = [{"start": s.start, "end": s.end, "text": s.text.strip()} for s in segments]
        text = " ".join(row["text"] for row in rows).strip()
        if not text:
            raise ValueError("No speech was detected in this audio")
        return {"text": text, "segments": rows, "language": info.language,
                "duration_seconds": monotonic() - started}
