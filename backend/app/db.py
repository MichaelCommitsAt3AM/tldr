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
        CREATE TABLE IF NOT EXISTS pairing (
          id INTEGER PRIMARY KEY CHECK (id=1), code_hash TEXT NOT NULL,
          expires_at REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0
        );
        """)
        # A killed worker cannot know whether its previous subprocess completed.
        c.execute("UPDATE jobs SET status='queued', updated_at=CURRENT_TIMESTAMP WHERE status IN ('fetching_metadata','downloading','extracting_audio','transcribing','summarizing') AND cancel_requested=0")
        c.execute("UPDATE jobs SET status='cancelled', updated_at=CURRENT_TIMESTAMP WHERE cancel_requested=1 AND status NOT IN ('completed','failed','cancelled')")


def get(job_id):
    with connection() as c:
        row = c.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    if not row:
        return None
    value = dict(row)
    for key in ("metadata", "transcript", "summary", "fact_check", "timings"):
        if value[key]:
            value[key] = json.loads(value[key])
    value.pop("upload_path", None)
    return value


def update(job_id, **fields):
    allowed = {"status", "metadata", "transcript", "summary", "fact_check", "error", "timings", "cancel_requested"}
    if not fields or not set(fields).issubset(allowed):
        raise ValueError("Invalid fields")
    values = [json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v for v in fields.values()]
    with connection() as c:
        c.execute("UPDATE jobs SET " + ", ".join(k + "=?" for k in fields) + ", updated_at=CURRENT_TIMESTAMP WHERE id=?", (*values, job_id))
