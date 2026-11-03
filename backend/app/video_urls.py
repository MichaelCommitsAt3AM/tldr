"""Validation of URLs supplied by users, before any network access.

This does not make yt-dlp safe: extractors can fetch additional URLs and redirects.
Network isolation is also required before these URLs are handed to yt-dlp.
"""
import re
from urllib.parse import urlsplit, urlunsplit

HOSTS = {
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
    "tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
    "instagram.com", "www.instagram.com", "m.instagram.com",
    "x.com", "www.x.com", "mobile.x.com",
    "twitter.com", "www.twitter.com", "mobile.twitter.com",
}


def extract_url(text: str) -> str:
    match = re.search(r"https?://[^\s<>\"']+", text, re.IGNORECASE)
    if not match:
        raise ValueError("No video URL found")
    return match.group().rstrip(".,!?;:)]}")


def validate_url(text: str) -> str:
    url = extract_url(text)
    try:
        parts = urlsplit(url)
        host = (parts.hostname or "").lower()
        port = parts.port
    except ValueError as exc:
        raise ValueError("Invalid URL") from exc
    if parts.scheme.lower() != "https" or host not in HOSTS:
        raise ValueError("Use an HTTPS link from YouTube, TikTok, Instagram, or X")
    if parts.username is not None or parts.password is not None or port not in (None, 443):
        raise ValueError("URL credentials and custom ports are not allowed")
    return urlunsplit(("https", host, parts.path or "/", parts.query, ""))
