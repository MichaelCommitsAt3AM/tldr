import json
import httpx
import pytest
from app.llm import LLM, ProviderError


def test_summary_request_and_validation(monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    def handler(request):
        assert request.url.path.endswith("/chat/completions")
        assert request.headers["authorization"] == "Bearer test-key"
        assert request.headers["x-opencode-session"] == "job-1"
        prompt = json.loads(request.content)["messages"][0]["content"]
        assert "ONLY summary" in prompt
        assert "Do not produce a longer summary" in prompt
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"title":"T","short_summary":"S","important_claims":["A claim"],"language":"en"}'}}]})
    llm = LLM(httpx.Client(transport=httpx.MockTransport(handler)))
    result = llm.summarize("Untrusted transcript", "job-1").model_dump()
    assert result == {"title": "T", "short_summary": "S", "important_claims": ["A claim"], "language": "en"}


def test_long_transcripts_still_produce_only_a_quick_summary(monkeypatch):
    llm = LLM()
    calls = []
    def complete(messages, session):
        calls.append(messages)
        return json.dumps({"title": "T", "short_summary": "Quick summary.", "important_claims": [], "language": "en"})
    monkeypatch.setattr(llm, "complete", complete)
    result = llm.summarize_long("word " * 6000, "job").model_dump()
    assert len(calls) == 3  # Two transcript chunks, then one combined quick summary.
    assert set(result) == {"title", "short_summary", "important_claims", "language"}


def test_rate_limit_is_not_retried(monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(429)
    llm = LLM(httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(ProviderError, match="limit"):
        llm.complete([])
    assert len(calls) == 1
