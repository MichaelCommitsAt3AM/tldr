import base64
import hashlib
import html
import os
import re
import secrets
import time
from functools import lru_cache

import jwt
from fastapi import APIRouter, Header, HTTPException, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from . import db

router = APIRouter(prefix="/api/v1/auth")
PROOF = r"^[A-Za-z0-9_-]{43}$"


@lru_cache(maxsize=2)
def signing_keys(issuer):
    return jwt.PyJWKClient(f"{issuer}/cdn-cgi/access/certs", timeout=10)


def verify(token):
    team = os.getenv("CF_ACCESS_TEAM_DOMAIN", "").strip().lower()
    audience = os.getenv("CF_ACCESS_AUD", "").strip()
    if not re.fullmatch(r"[a-z0-9-]+\.cloudflareaccess\.com", team) or not audience:
        raise HTTPException(503, "Cloudflare Access sign-in is not configured")
    if not token or len(token) > 8192:
        raise HTTPException(401, "Sign in to TLDR")
    issuer = f"https://{team}"
    try:
        key = signing_keys(issuer).get_signing_key_from_jwt(token)
        claims = jwt.decode(token, key.key, algorithms=["RS256"], issuer=issuer, audience=audience,
                            options={"require": ["iss", "aud", "exp", "sub", "email"]})
    except jwt.PyJWKClientConnectionError as exc:
        raise HTTPException(503, "Cloudflare sign-in is temporarily unavailable") from exc
    except (jwt.InvalidTokenError, jwt.PyJWKClientError) as exc:
        raise HTTPException(401, "Your session expired or is invalid. Sign in again.") from exc
    if not isinstance(claims["email"], str) or not claims["email"].strip() or not claims["sub"]:
        raise HTTPException(401, "Sign in with a Cloudflare Access user account")
    return claims


def require_auth(authorization: str = Header(default="")) -> str:
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer":
        raise HTTPException(401, "Sign in to TLDR")
    return verify(token)["sub"]


def digest(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


@router.get("/browser", response_class=HTMLResponse)
def browser(challenge: str, cf_access_jwt_assertion: str = Header(default="")):
    if not re.fullmatch(PROOF, challenge):
        raise HTTPException(400, "Start sign-in from the TLDR app")
    claims = verify(cf_access_jwt_assertion)
    code = secrets.token_urlsafe(32)
    now = int(time.time())
    with db.connection() as c:
        c.execute("DELETE FROM login_codes WHERE expires_at <= ?", (now,))
        c.execute("INSERT INTO login_codes VALUES(?,?,?,?)",
                  (digest(code), challenge, cf_access_jwt_assertion, min(now + 90, claims["exp"])))
    link = html.escape(f"tech.currenta.tldr://auth/callback?code={code}", quote=True)
    return HTMLResponse(
        f'<!doctype html><html lang="en"><meta charset="utf-8">'
        f'<meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>TLDR sign-in</title><h1>Sign-in verified</h1>'
        f'<p><a href="{link}">Return to TLDR</a></p></html>',
        headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
                 "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'"},
    )


class Exchange(BaseModel):
    code: str = Field(pattern=PROOF)
    verifier: str = Field(pattern=PROOF)


@router.post("/exchange")
def exchange(request: Exchange, response: Response):
    response.headers["Cache-Control"] = "no-store"
    challenge = base64.urlsafe_b64encode(bytes.fromhex(digest(request.verifier))).decode().rstrip("=")
    with db.connection() as c:
        row = c.execute("""DELETE FROM login_codes WHERE code_hash=? AND challenge=? AND expires_at>?
                           RETURNING token""", (digest(request.code), challenge, int(time.time()))).fetchone()
    if not row:
        raise HTTPException(401, "Sign-in link expired or was already used. Start again.")
    token = row["token"]
    claims = verify(token)
    with db.connection() as c:
        c.execute("""INSERT INTO accounts(id,email) VALUES(?,?)
                     ON CONFLICT(id) DO UPDATE SET email=excluded.email""", (claims["sub"], claims["email"]))
    return {"access_token": token}


@router.get("/status")
def status(response: Response, authorization: str = Header(default="")):
    response.headers["Cache-Control"] = "no-store"
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer":
        raise HTTPException(401, "Sign in to TLDR")
    return {"email": verify(token)["email"]}
