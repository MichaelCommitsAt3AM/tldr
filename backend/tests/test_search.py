import json
import httpx
from app import search


def test_citations_must_be_from_search(monkeypatch):
    monkeypatch.setenv("BRAVE_SEARCH_API_KEY", "test")
    monkeypatch.delenv("TLDR_ISOLATED_NETWORK", raising=False)
    class Client:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def get(self, *args, **kwargs):
            return httpx.Response(200, json={"web": {"results": [{"url": "https://example.org/evidence", "title": "Evidence", "description": "A result"}]}}, request=httpx.Request("GET", "https://api.search.brave.com"))
    class FakeLLM:
        def complete(self, *args):
            return json.dumps({"verdict": "Supported", "explanation": "Claim is true", "source_urls": ["https://invented.example/fake"]})
    monkeypatch.setattr(search.httpx, "Client", lambda **kwargs: Client())
    monkeypatch.setattr(search, "LLM", FakeLLM)
    report = search.fact_check({"id": "job", "summary": {"important_claims": ["A claim"]}})
    assert report["claims"][0]["verdict"] == "Unverified"
    assert report["claims"][0]["sources"][0]["url"] == "https://example.org/evidence"


def test_overview_retries_long_output_without_changing_details(monkeypatch):
    findings = [{"claim": "A claim", "verdict": "Unverified", "explanation": "Evidence is incomplete."}]
    replies = iter(["word " * 81, "The claim remains unverified because the available evidence is incomplete."])
    class FakeLLM:
        def complete(self, messages, session_id):
            assert session_id == "job"
            assert "Evidence is incomplete." in messages[1]["content"]
            return next(replies)
    monkeypatch.setattr(search, "LLM", FakeLLM)
    assert search.check_overview(findings, "job") == "The claim remains unverified because the available evidence is incomplete."
    assert findings[0]["explanation"] == "Evidence is incomplete."


def test_overview_failure_keeps_a_short_honest_fallback(monkeypatch):
    class FakeLLM:
        def complete(self, *args):
            raise search.ProviderError("Unavailable")
    monkeypatch.setattr(search, "LLM", FakeLLM)
    text = search.check_overview([{"claim": "A", "verdict": "Unverified", "explanation": "No evidence."}], "job")
    assert "1 unverified" in text
    assert len(text.split()) <= 80


def test_no_claims_skips_search_and_model(monkeypatch):
    monkeypatch.delenv("BRAVE_SEARCH_API_KEY", raising=False)
    def unexpected(*args, **kwargs):
        raise AssertionError("No external calls should be made")
    monkeypatch.setattr(search.httpx, "Client", unexpected)
    monkeypatch.setattr(search, "LLM", unexpected)
    assert search.fact_check({"id": "job", "summary": {"important_claims": []}})["claims"] == []
