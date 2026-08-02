#!/usr/bin/env python3
"""Read a PCAPdroid capture of a DJI app talking to a camera, and decode the DUML on it.

Point it at a capture of the official app doing something we can't yet do, and it prints what went
over the wire — command histogram, the per-file operations verbatim, and a timeline. Anything the
official app sends that we don't is the gap.

    python3 tools/duml_pcap.py capture.pcap --port 10004 --ops
    python3 tools/duml_pcap.py capture.pcap --port 9004 --timeline

Datalink ports: 9004 Osmo 360 / Nano / Pocket 3, 10004 Xtra Edge Pro / Action 5 Pro, 9003 drone.

Self-contained on purpose — the captures and the reference implementations live in `reference/`,
which is gitignored, so this must not import from them.
"""

from __future__ import annotations

import argparse
import struct
import sys
from collections import Counter

# ---- DUML framing ---------------------------------------------------------------------------------
#
#   55 | len:10b + ver:6b (u16 LE) | crc8 | sender | receiver | seq:u16 | type | set | id | payload | crc16
#
# The length field is TEN bits, not eight: a frame over 255 bytes carries its high bits in byte 2
# alongside the version, so byte 2 reads 0x05/0x06/0x07 rather than 0x04. Matching only 0x04 hides
# every long frame — which is exactly where a media manifest lives.

SOF = 0x55
CRC8_INIT, CRC8_POLY = 0x77, 0x8C
CRC16_INIT, CRC16_POLY = 0x3692, 0x8408


def _table(poly: int) -> list[int]:
    out = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        out.append(c)
    return out


_CRC8, _CRC16 = _table(CRC8_POLY), _table(CRC16_POLY)


def crc8(data: bytes) -> int:
    crc = CRC8_INIT
    for b in data:
        crc = _CRC8[(crc ^ b) & 0xFF]
    return crc


def crc16(data: bytes) -> int:
    crc = CRC16_INIT
    for b in data:
        crc = (crc >> 8) ^ _CRC16[(crc ^ b) & 0xFF]
    return crc & 0xFFFF


def scan_frames(raw: bytes) -> list[tuple[int, int, int, int, bytes]]:
    """Every CRC-valid DUML frame in `raw`, as (set, id, type, seq, payload).

    Advances one byte at a time rather than by the frame length: a drone tunnels replies inside an
    outer frame, and skipping the outer one skips the payload with it. Verifying both CRCs is what
    makes that safe from false positives.
    """
    out = []
    i, n = 0, len(raw)
    while i + 13 <= n:
        if raw[i] != SOF:
            i += 1
            continue
        len_ver = struct.unpack_from("<H", raw, i + 1)[0]
        length, ver = len_ver & 0x03FF, (len_ver >> 10) & 0x3F
        if ver != 1 or length < 13 or i + length > n:
            i += 1
            continue
        if crc8(raw[i:i + 3]) != raw[i + 3]:
            i += 1
            continue
        if crc16(raw[i:i + length - 2]) != struct.unpack_from("<H", raw, i + length - 2)[0]:
            i += 1
            continue
        out.append((raw[i + 9], raw[i + 10], (raw[i + 8] >> 5) & 0x07,
                    struct.unpack_from("<H", raw, i + 6)[0], raw[i + 11:i + length - 2]))
        i += 1
    return out


# ---- pcap ------------------------------------------------------------------------------------------

LINKTYPE_RAW, LINKTYPE_ETHERNET = 101, 1


def read_udp(path: str, port: int) -> list[tuple[float, bool, bytes]]:
    """UDP datagrams on `port`, as (t, from_app, payload). Handles raw-IP and Ethernet captures."""
    with open(path, "rb") as fh:
        blob = fh.read()
    magic = struct.unpack_from("<I", blob, 0)[0]
    if magic != 0xA1B2C3D4:
        sys.exit(f"not a little-endian pcap (magic {magic:08x}); pcapng is not supported")
    linktype = struct.unpack_from("<I", blob, 20)[0]
    if linktype not in (LINKTYPE_RAW, LINKTYPE_ETHERNET):
        sys.exit(f"unsupported linktype {linktype}")
    l2 = 14 if linktype == LINKTYPE_ETHERNET else 0

    out, off, t0 = [], 24, None
    while off + 16 <= len(blob):
        ts_s, ts_us, caplen, _ = struct.unpack_from("<IIII", blob, off)
        p = off + 16
        off = p + caplen
        if off > len(blob):
            break
        p += l2
        if p + 20 > len(blob) or blob[p] >> 4 != 4 or blob[p + 9] != 17:
            continue                                   # IPv4 + UDP only
        udp = p + (blob[p] & 0x0F) * 4
        if udp + 8 > len(blob):
            continue
        sport, dport, ulen = struct.unpack_from(">HHH", blob, udp)
        if port not in (sport, dport):
            continue
        t = ts_s + ts_us / 1e6
        t0 = t if t0 is None else t0
        out.append((t - t0, _from_app(blob, p, dport, port),
                    blob[udp + 8:min(udp + ulen, len(blob))]))
    return out


def _from_app(blob: bytes, p: int, dport: int, port: int) -> bool:
    """Which end sent this.

    Not as simple as "the device is x.x.x.1": PCAPdroid captures through a VPN tunnel and rewrites the
    phone's address to its own gateway, which is *also* an x.x.x.1 — so that test matches both
    directions and every frame reads as outbound. The device is specifically on 192.168.x.1 (192.168.2.1
    on a drone or a joined camera AP, 192.168.4.1 on some), and the phone never is.
    """
    def is_device(base: int) -> bool:
        return blob[base] == 192 and blob[base + 1] == 168 and blob[base + 3] == 1

    src_dev, dst_dev = is_device(p + 12), is_device(p + 16)
    if src_dev != dst_dev:
        return dst_dev
    # Neither or both look like the device: fall back to the port. Works everywhere except a drone,
    # which binds the datalink port at both ends — hence the address test first.
    return dport == port


# ---- payload decoders ------------------------------------------------------------------------------

def decode_delete(pl: bytes) -> str:
    """`[count:u8][handle:u32-LE ...][count:u32] 00 [count:u32][selector:4]`.

    The trailing selector was copied verbatim from a single Nano capture and has never been checked
    against a camera with two stores, so it is printed rather than assumed.
    """
    if not pl:
        return "(empty)"
    n = pl[0]
    if len(pl) < 1 + 4 * n:
        return f"count={n} (truncated)"
    handles = [struct.unpack_from("<I", pl, 1 + 4 * i)[0] for i in range(n)]
    tail = pl[1 + 4 * n:]
    return f"n={n} handles=[{', '.join(f'0x{h:08x}' for h in handles)}] tail={tail.hex()}"


def decode_favorite(pl: bytes) -> str:
    """`01 01 [handle:u32-LE][counter:u32-LE] 00 [on:u8] 00 00 00`."""
    if len(pl) < 15:
        return f"(short) {pl.hex()}"
    handle, counter = struct.unpack_from("<II", pl, 2)
    return f"handle=0x{handle:08x} counter={counter} on={pl[11]}"


def decode_status(pl: bytes) -> str:
    if not pl:
        return "(no payload)"
    word = struct.unpack_from("<H", pl, 0)[0] if len(pl) >= 2 else pl[0]
    return f"status=0x{word:04x}{'  OK' if word == 0 else ''}"


FILE_OPS = {
    (0x00, 0x28): ("DELETE", decode_delete),
    (0x02, 0xBF): ("FAVOURITE", decode_favorite),
}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pcap")
    ap.add_argument("--port", type=int, default=9004, help="datalink UDP port (default 9004)")
    ap.add_argument("--ops", action="store_true", help="per-file operations, decoded (default on)")
    ap.add_argument("--timeline", action="store_true", help="also print every media-list frame")
    ap.add_argument("--top", type=int, default=25, help="how many commands to list")
    args = ap.parse_args()

    grams = read_udp(args.pcap, args.port)
    print(f"=== {args.pcap}: {len(grams)} datagrams on udp/{args.port} ===\n")
    if not grams:
        sys.exit("nothing on that port — wrong --port for this camera?")

    pkt_types, cmds, ops, media = Counter(), Counter(), [], []
    for t, from_app, pl in grams:
        if len(pl) > 6:
            pkt_types[pl[6]] += 1
        arrow = "->" if from_app else "<-"
        for cs, ci, _ctype, seq, body in scan_frames(pl):
            cmds[f"{arrow} {cs:02x}/{ci:02x}"] += 1
            if (cs, ci) in FILE_OPS:
                name, dec = FILE_OPS[(cs, ci)]
                # What the camera sends back is a status word; what the app sends is the operands.
                # Keyed on direction rather than the type nibble, which differs between request (0x40)
                # and response (0xc0) in a way that is easy to get subtly wrong.
                shown = dec(body) if from_app else decode_status(body)
                ops.append(f"{t:8.2f}s {arrow} {name:9s} {shown}\n"
                           f"{'':10s}    raw {body.hex()}")
            elif cs == 0x00 and ci in (0x26, 0x27) and body[:1] == b"\x4a":
                sub, ln, sq = body[1], struct.unpack_from("<H", body, 2)[0] & 0x0FFF, struct.unpack_from("<H", body, 4)[0]
                chunk = struct.unpack_from("<I", body, 6)[0] if len(body) >= 10 else -1
                if from_app or chunk == 0:
                    media.append(f"{t:8.2f}s {arrow} 4a sub={sub:02x} seq={sq:04x} len={ln}"
                                 + (f"  {body.hex()}" if len(body) <= 24 else ""))

    print("-- transport pktTypes --")
    for k, v in sorted(pkt_types.items()):
        print(f"  pkt{k:02x}  {v}")

    print(f"\n-- DUML commands (top {args.top}) --")
    for k, v in cmds.most_common(args.top):
        print(f"  {k}  {v}")

    print(f"\n-- per-file operations ({len(ops)}) --")
    print("\n".join(f"  {o}" for o in ops) if ops else "  (none)")

    if args.timeline:
        print(f"\n-- media list frames ({len(media)}) --")
        for m in media:
            print(f"  {m}")


if __name__ == "__main__":
    main()
