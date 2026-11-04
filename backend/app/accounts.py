"""List TLDR accounts or assign old single-device history."""
import argparse

from . import db


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("list", "claim-history"))
    parser.add_argument("user_id", nargs="?")
    args = parser.parse_args()
    if (args.action == "list") != (args.user_id is None):
        parser.error("Supply a user ID for claim-history.")
    with db.connection() as c:
        if args.action == "list":
            for row in c.execute("SELECT id, email FROM accounts ORDER BY email"):
                print(row["id"], row["email"])
            return
        user_id = args.user_id
        c.execute("BEGIN IMMEDIATE")
        row = c.execute("SELECT email FROM accounts WHERE id=?", (user_id,)).fetchone()
        if not row:
            parser.error("Account not found. The user must sign in to TLDR first.")
        result = c.execute("UPDATE jobs SET owner_id=? WHERE owner_id IS NULL", (user_id,))
        print(f"Assigned {result.rowcount} legacy jobs to {row['email']}.")


if __name__ == "__main__":
    main()
