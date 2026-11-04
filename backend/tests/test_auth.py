import time
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException

from app import auth


def test_access_jwt_requires_matching_signature_issuer_audience_and_expiry(monkeypatch):
    issuer = "https://example.cloudflareaccess.com"
    monkeypatch.setenv("CF_ACCESS_TEAM_DOMAIN", "example.cloudflareaccess.com")
    monkeypatch.setenv("CF_ACCESS_AUD", "tldr-audience")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    signing_keys = SimpleNamespace(get_signing_key_from_jwt=lambda _: SimpleNamespace(key=key.public_key()))
    monkeypatch.setattr(auth, "signing_keys", lambda _: signing_keys)
    claims = {"iss": issuer, "aud": ["tldr-audience"], "exp": int(time.time()) + 60,
              "sub": "test-user", "email": "test@example.com"}
    assert auth.verify(jwt.encode(claims, key, algorithm="RS256"))["sub"] == "test-user"

    for change in ({"aud": ["different-app"]}, {"iss": "https://other.cloudflareaccess.com"},
                   {"exp": 0}, {"email": ""}):
        with pytest.raises(HTTPException) as error:
            auth.verify(jwt.encode({**claims, **change}, key, algorithm="RS256"))
        assert error.value.status_code == 401

    wrong_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with pytest.raises(HTTPException) as error:
        auth.verify(jwt.encode(claims, wrong_key, algorithm="RS256"))
    assert error.value.status_code == 401
