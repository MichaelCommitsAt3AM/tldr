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
