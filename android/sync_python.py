#!/usr/bin/env python3
"""Copy the root gemini_web2api package into the Android Python source set.

Run from anywhere; no external dependencies. The Android Gradle project does
not duplicate the Python code: CI (and local Android Studio users) run this
script before building so the APK always contains the current package.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(ROOT, "..", "gemini_web2api"))
DST = os.path.join(ROOT, "app", "src", "main", "python", "gemini_web2api")


def main() -> int:
    if not os.path.isfile(os.path.join(SRC, "server.py")):
        print(f"error: {SRC} does not look like the gemini_web2api package", file=sys.stderr)
        return 1
    if os.path.exists(DST):
        shutil.rmtree(DST)
    shutil.copytree(
        SRC,
        DST,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".git"),
    )
    print(f"synced {SRC} -> {DST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
