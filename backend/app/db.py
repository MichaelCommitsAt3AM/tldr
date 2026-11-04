import json
import os
import sqlite3
from contextlib import contextmanager

DATA = os.getenv("TLDR_DATA_DIR", "./data")
os.makedirs(DATA, exist_ok=True)
DB = os.path.join(DATA, "tldr.sqlite3")


@contextmanager
def connection():
    conn = sqlite3.connect(DB, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def initialize():
    with connection() as c:
        c.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, url TEXT, upload_path TEXT, status TEXT NOT NULL,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP, updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
          metadata TEXT, transcript TEXT, summary TEXT, fact_check TEXT,
          error TEXT, cancel_requested INTEGER NOT NULL DEFAULT 0, timings TEXT
        );
        CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status, created_at);
        CREATE TABLE IF NOT EXISTS accounts (
          id TEXT PRIMARY KEY, email TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS login_codes (
          code_hash TEXT PRIMARY KEY, challenge TEXT NOT NULL,
          token TEXT NOT NULL, expires_at INTEGER NOT NULL
        );
        """)
        if "owner_id" not in {row["name"] for row in c.execute("PRAGMA table_info(jobs)")}:
            c.execute("ALTER TABLE jobs ADD COLUMN owner_id TEXT")
        c.execute("CREATE INDEX IF NOT EXISTS jobs_owner ON jobs(owner_id, created_at)")
        # A killed worker cannot know whether its previous subprocess completed.
        c.execute("UPDATE jobs SET status='queued', updated_at=CURRENT_TIMESTAMP WHERE status IN ('fetching_metadata','downloading','extracting_audio','transcribing','summarizing') AND cancel_requested=0")
        c.execute("UPDATE jobs SET status='cancelled', updated_at=CURRENT_TIMESTAMP WHERE cancel_requested=1 AND status NOT IN ('completed','failed','cancelled')")


def get(job_id, owner_id=None):
    with connection() as c:
        if owner_id is None:
            row = c.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        else:
            row = c.execute("SELECT * FROM jobs WHERE id=? AND owner_id=?", (job_id, owner_id)).fetchone()
    if not row:
        return None
    value = dict(row)
    for key in ("metadata", "transcript", "summary", "fact_check", "timings"):
        if value[key]:
            value[key] = json.loads(value[key])
    value.pop("upload_path", None)
    value.pop("owner_id", None)
    return value


def update(job_id, **fields):
    allowed = {"status", "metadata", "transcript", "summary", "fact_check", "error", "timings", "cancel_requested"}
    if not fields or not set(fields).issubset(allowed):
        raise ValueError("Invalid fields")
    values = [json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v for v in fields.values()]
    with connection() as c:
        c.execute("UPDATE jobs SET " + ", ".join(k + "=?" for k in fields) + ", updated_at=CURRENT_TIMESTAMP WHERE id=?", (*values, job_id))
