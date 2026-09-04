if b"Co-Authored-By: Claude" not in commit.message:
    commit.message = commit.message.split(b"\n", 1)[0].rstrip() + b"\n"
