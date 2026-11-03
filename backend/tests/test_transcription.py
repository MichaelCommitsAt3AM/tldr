from types import SimpleNamespace
import pytest
from app import transcription


def test_transcription_preserves_segments(monkeypatch):
    class Fake:
        def transcribe(self, path, **kwargs):
            assert kwargs == {"beam_size": 5, "vad_filter": True}
            return iter([SimpleNamespace(start=0, end=1.5, text=" Habari ")]), SimpleNamespace(language="sw")
    monkeypatch.setattr(transcription, "model", lambda: Fake())
    result = transcription.transcribe("audio.wav")
    assert result["text"] == "Habari"
    assert result["segments"][0]["end"] == 1.5
    assert result["language"] == "sw"


def test_empty_audio(monkeypatch):
    class Fake:
        def transcribe(self, *args, **kwargs):
            return iter([]), SimpleNamespace(language="en")
    monkeypatch.setattr(transcription, "model", lambda: Fake())
    with pytest.raises(ValueError, match="No speech"):
        transcription.transcribe("silent.wav")
