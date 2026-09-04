from datetime import datetime, timezone

timestamp = int(commit.author_date.split()[0])
date = datetime.fromtimestamp(timestamp, tz=timezone.utc).strftime("%Y-%m-%d")

if date == "2026-08-25":
    commit.message = commit.message.replace(
        b"\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>",
        b""
    )
