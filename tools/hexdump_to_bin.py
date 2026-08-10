#!/usr/bin/env python3
"""Rebuild a manifest .bin fixture from the hex dump in an Osmosis log.

A tester on an unknown camera can't be walked through a packet capture, but they already know how
to flip "Save logs" and share the file. With the manifest dump on (automatic for any unverified
model, or long-press "Save logs"), the log carries the raw file-list blob as a hex dump; this turns
that back into the bytes, so it can land in app/src/test/resources/manifests/ like the fixtures that
came from pcaps.

The log format, from CameraSession.dumpManifest:

    datalink: --- MANIFEST-HEX BEGIN (37612B), report this to crack the layout ---
      0000  <32 bytes of hex, a space after the 16th>   <32 chars of ASCII>
      ...
    datalink: --- MANIFEST-HEX END ---

A capped dump announces both figures — `BEGIN (64000B of 74612B)` — and the first is what the log
actually carries. A truncated fixture is still worth having: a manifest that failed to decode is
usually diagnosable from its opening records alone.

Offsets are verified as it goes: a log line dropped by a rotating log file or a truncated share
would otherwise produce a fixture that is quietly missing 32 bytes in the middle, which is far worse
than no fixture at all.

    python tools/hexdump_to_bin.py osmosis_20260807_143013.log app/src/test/resources/manifests/
"""
import os
import re
import sys

# Group 1 is the byte count actually dumped; the optional group 2 is the full manifest size, present
# only when the dump was capped. Matching just `(\d+)B\)` misses every capped dump, which is exactly
# the failure case the dump exists to capture.
BEGIN = re.compile(r"MANIFEST-HEX BEGIN \((\d+)B(?: of (\d+)B)?\)")
END = re.compile(r"MANIFEST-HEX END")
# "[14:30:57.906]   0000  aabbcc.. ..ddeeff   ascii"
#
# The emitter is `"  %04x  %-65s %s"`: two spaces, offset, two spaces, then a 65-wide hex field (32
# bytes, with one space after the 16th), then one space, then ASCII. Match up to the hex field and
# slice it by that fixed width rather than pattern-matching the hex run — a greedy hex match runs
# straight into the ASCII column, because filenames like "0264_D" are themselves valid hex pairs.
ROW = re.compile(r"^(?:\[[^\]]*\]\s*)?\s*([0-9a-f]{4,8})\s\s")
HEX_FIELD_WIDTH = 65


def blocks(lines):
    """Yield (declared_size, bytearray) for each complete dump in the log."""
    it = iter(lines)
    for line in it:
        m = BEGIN.search(line)
        if not m:
            continue
        declared, buf, offset, ok = int(m.group(1)), bytearray(), 0, True
        for line in it:
            if END.search(line):
                break
            row = ROW.match(line)
            if not row:
                continue
            at = int(row.group(1), 16)
            if at != offset:
                print(f"  ! offset gap: expected 0x{offset:04x}, log says 0x{at:04x} "
                      f"- dump is incomplete, skipping", file=sys.stderr)
                ok = False
                break
            field = line[row.end():row.end() + HEX_FIELD_WIDTH].replace(" ", "")
            if not field or len(field) % 2 or not all(c in "0123456789abcdef" for c in field):
                print(f"  ! unreadable hex at 0x{at:04x}, skipping", file=sys.stderr)
                ok = False
                break
            chunk = bytes.fromhex(field)
            buf += chunk
            offset += len(chunk)
        if ok:
            yield declared, bytes(buf)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    log_path = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "."
    stem = os.path.splitext(os.path.basename(log_path))[0]

    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        found = list(blocks(fh))

    if not found:
        print("no MANIFEST-HEX block in this log.\n"
              "The dump only runs for unverified camera models, or after long-pressing "
              '"Save logs" and reconnecting.', file=sys.stderr)
        return 1

    os.makedirs(out_dir, exist_ok=True)
    written = 0
    for i, (declared, data) in enumerate(found):
        if len(data) != declared:
            print(f"  ! block {i}: log declared {declared}B but only {len(data)}B recovered "
                  f"- skipping", file=sys.stderr)
            continue
        suffix = "" if len(found) == 1 else f"_{i}"
        out = os.path.join(out_dir, f"{stem}{suffix}.bin")
        with open(out, "wb") as fh:
            fh.write(data)
        print(f"{out}  {len(data)}B")
        written += 1
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
