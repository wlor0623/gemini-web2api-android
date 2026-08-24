"""Local smoke test for server_runner.py (no Android needed).

Creates a temp data dir, starts the server on a test port, hits /v1/models,
then stops it. Run from the repo root:
    python android/test_runner_local.py
"""
import json
import os
import shutil
import sys
import tempfile
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO, "android", "app", "src", "main", "python"))
sys.path.insert(0, REPO)

import server_runner  # noqa: E402

PORT = 18081


def main():
    data_dir = tempfile.mkdtemp(prefix="gw2a-test-")
    # Pre-create a config so we do not collide with any locally running service.
    with open(os.path.join(data_dir, "config.json"), "w", encoding="utf-8") as f:
        json.dump({"port": PORT, "host": "127.0.0.1"}, f)
    try:
        r = json.loads(server_runner.start_server(data_dir))
        print("start_server ->", r)
        assert r["running"] is True, r
        assert r["base_url"] == f"http://127.0.0.1:{PORT}/v1", r

        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/v1/models", timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        ids = [m["id"] for m in body["data"]]
        print("GET /v1/models ->", resp.status, len(ids), "models:", ids[:3], "...")
        assert any("flash" in i for i in ids)

        # double start is a no-op
        r2 = json.loads(server_runner.start_server(data_dir))
        assert r2["running"] is True

        r3 = json.loads(server_runner.stop_server())
        print("stop_server ->", r3)
        assert r3["running"] is False
        assert json.loads(server_runner.server_status())["running"] is False
        print("OK")
    finally:
        shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
