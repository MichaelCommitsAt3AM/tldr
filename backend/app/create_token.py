"""Run on the server to rotate the single device token; display it once locally."""
import hashlib
import os
import secrets
from pathlib import Path
from .db import DATA

token = secrets.token_urlsafe(32)
path = Path(DATA) / "device-token.sha256"
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    f.write(hashlib.sha256(token.encode()).hexdigest())
print(token)
