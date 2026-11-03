import pytest
from app.video_urls import validate_url


def test_shared_caption():
    assert validate_url("Watch this! https://youtu.be/abc?t=3.") == "https://youtu.be/abc?t=3"


@pytest.mark.parametrize("url", [
    "http://youtube.com/watch?v=x", "https://youtube.com.evil.test/x",
    "https://youtube.com@127.0.0.1/x", "https://youtube.com:8080/x",
    "https://127.0.0.1/x", "https://[::1]/", "https://evil.test/?x=youtube.com",
])
def test_rejects_unsafe_input(url):
    with pytest.raises(ValueError):
        validate_url(url)


def test_fragment_removed():
    assert validate_url("https://www.instagram.com/reel/abc#x") == "https://www.instagram.com/reel/abc"


@pytest.mark.parametrize("host", ["x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"])
def test_x_shared_video_link(host):
    assert validate_url(f"Watch this https://{host}/user/status/123456789?ref=share!") == f"https://{host}/user/status/123456789?ref=share"


@pytest.mark.parametrize("url", ["https://x.com.evil.example/user/status/123", "https://x.com@127.0.0.1/", "https://twitter.com:8443/a", "https://t.co/example"])
def test_x_lookalike_and_unrestricted_shorteners_rejected(url):
    with pytest.raises(ValueError):
        validate_url(url)
