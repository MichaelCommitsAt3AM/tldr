"""Generate a short-lived one-time code for the device owner, on the server only."""
import hashlib
import secrets
import time
from . import db

code = f"{secrets.randbelow(1_000_000_000):09d}"
with db.connection() as c:
    c.execute("INSERT OR REPLACE INTO pairing(id,code_hash,expires_at,attempts) VALUES(1,?,?,0)",
              (hashlib.sha256(code.encode()).hexdigest(), time.time() + 300))
print("Pairing code (valid for 5 minutes):", code)
