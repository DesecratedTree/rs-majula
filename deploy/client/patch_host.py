#!/usr/bin/env python3
"""Relax the original 742 client's hardcoded host allow-list.

The 2012 client's applet start (nh.ad) only accepts hosts ending in `.jagex.com`,
`.runescape.com`, `.stellardawn.com`, `127.0.0.1` or `192.168.1.`, and draws the
`error_game_invalidhost` page for anything else — including a private server's
public IP.

The allow-list's last entry is the constant `"192.168.1."`, compared with
`String.endsWith`. Rewriting that constant-pool entry to the empty string makes
the final comparison always succeed, so any host is accepted, while leaving every
other check (and every other constant in the client) untouched:

    CONSTANT_Utf8 #11, length 10, "192.168.1."   ->   CONSTANT_Utf8 #11, length 0

Constant-pool indices are unchanged, so no other part of the class moves. This
only edits that one string; the jar's RSA keys and everything else stay as they
are (that is what deploy/client/patch_client.py handles).

Usage:
    python deploy/client/patch_host.py                      # patches public/742/client.jar
    python deploy/client/patch_host.py --check              # reports without writing
    python deploy/client/patch_host.py --jar path/to.jar
"""

import argparse
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]

TARGET = "nh.class"
ORIGINAL = b"\x01\x00\x0a192.168.1."  # CONSTANT_Utf8, length 10, "192.168.1."
RELAXED = b"\x01\x00\x00"  # CONSTANT_Utf8, length 0, ""


def patch_class(data: bytes) -> bytes:
    found = data.count(ORIGINAL)
    if found != 1:
        raise ValueError(f"expected exactly one host constant in {TARGET}, found {found}")
    return data.replace(ORIGINAL, RELAXED)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, default=ROOT / "public/742/client.jar")
    parser.add_argument("--check", action="store_true", help="verify only, write nothing")
    args = parser.parse_args()

    jar = args.jar
    if not jar.is_file():
        print(f"error: {jar} not found", file=sys.stderr)
        return 1

    with ZipFile(jar) as src:
        entries = [(info, src.read(info.filename)) for info in src.infolist()]

    patched = 0
    already = 0
    for info, data in entries:
        if info.filename == TARGET:
            if ORIGINAL in data:
                patched = 1
            elif b"\x01\x00\x00192.168.1." not in data:
                already = 1

    if patched == 0 and already == 1:
        print(f"{jar}: host check already relaxed")
        return 0
    if patched == 0:
        print(f"error: {TARGET} in {jar} looks unfamiliar (no host constant)", file=sys.stderr)
        return 1

    if args.check:
        print(f"{jar}: host constant still present; would be relaxed")
        return 0

    tmp = jar.with_suffix(jar.suffix + ".tmp")
    with ZipFile(tmp, "w", ZIP_DEFLATED) as out:
        for info, data in entries:
            out.writestr(info, patch_class(data) if info.filename == TARGET else data)
    tmp.replace(jar)
    print(f"{jar}: host check relaxed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
