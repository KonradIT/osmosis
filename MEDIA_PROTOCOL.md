# Osmosis — Media & camera DUML commands

Every DUML command we use / know for browsing, fetching, and controlling media on DJI Osmo cameras
(WiFi UDP datalink + BLE control).

Transports: **BLE** = write GATT `fff5`, notify `fff4` (the `[6:8]` msg-id round-trips either way — we encode/decode it **little-endian** and the camera echoes the bytes back, so its true endianness is moot for request/response matching).

> ⚠️ **A bare-metal BLE client needs the GATT setup below before the camera will act on anything.** Get it wrong and the camera ATT-acks every write, silently ignores it, and answers nothing — which reads exactly like an unsupported command, so you will hunt the wrong layer for days. Required:
> - **Subscribe the CCCDs of BOTH `fff4` and `fff5`** (0xFFF5's is easy to miss if service discovery is range-limited to the write characteristic).
> - **Write `01 00` to the `fff4` characteristic VALUE** (not its CCCD), with response, after the CCCDs and before any `fff5` traffic, then let it settle ~200 ms.
> - **`fff5` is WRITE_NO_RSP only** (`props=0x36`) — a Write Request on it is a spec violation.
> - **Every app→camera frame needs `cmd_type` `0x40`**, never `0x00`.
> - **MTU 500.** Negotiating 517 makes the camera stop answering *every* request (its NimBLE buffers are sized for 500) — raise the buffer config too or leave it alone.
> - **Wait for the `0x07/0x45` pairing reply before sending the wake.** Ours arrives at ~+232 ms, far later than the ~+21 ms a Mimo capture suggests.
>
> **LE encryption/bonding is NOT required**
**Datalink** = UDP (DJI-standard `9004` + TCP-7001 poke first — Nano, Action 5/6, Pocket 3; the **Xtra Edge Pro**
rebrand alone speaks `10004` with no poke), DUML wrapped
in `[8B udp hdr][12B routing hdr][frame]`. Addressing byte `(id<<5)|type`: App `0x02`, Camera `0x01`,
Gimbal `0x03`, Battery `0x05`, WiFi `0x07`, DM368 `0x08`, plus two session endpoints that are **not** the
camera — `0xF0` (type `0x10`, id 7) and `0x1C` (type `0x1C`, id 0). Address the wake commands below to the
camera by mistake and it answers `e0` (reject) and stays asleep; nothing else hints at what went wrong.

---

## Media

### 1. Get media list
- Cmd Set: `0x00`
- Cmd ID: `0x26`  (response `0x00/0x27`)
- Dir / transport: App → Camera(`0x01`), datalink
- Payload (page 1): `4a002a10 01000000 0000 01000000 2d00 0d0100 ffffffffffffffff 0001000000000000 000000`
- Response: chunked `0x00/0x27` frames, each payload = `[10B sub-header 4A 01 xx xx <seq:u16LE@6> 00 00][chunk]`. **Strip the sub-header, concat chunks in arrival order** → the manifest.
- DUML example: <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>

#### Paginate the full library

One `0x00/0x26` returns only the **newest ~45 files** (the `2d` = 45 count at payload byte 14). To reach older files the request carries a **cursor = a 4-byte little-endian file *handle* at payload bytes 10-13** — the same handle the record exposes for delete ([§2](#2-delete-media), `u32-LE @ head`). Two things make it page:

1. **Enter playback mode first** — the list only paginates in playback; without it a query re-returns the newest 45.
   - Cmd Set / ID: `0x02` / `0x0c`  ·  App → Camera(`0x01`), datalink
   - Payload: `01 01 00 01` = enter playback · `01 01 00 00` = leave
   - DUML example (enter): <https://b3yond.d3vl.com/duml/#55110492020100a040020c01010001b63b>
2. **Per page send three frames** — `query(cursor=1)` → `trigger` → `query(cursor=pageCursor)`. The **second query's cursor selects the page**; the first (`cursor = 0x00000001`) and the trigger (`4a040e10`) prime the stream.

| page | cursor @ bytes 10-13 (u32-LE) | returns |
|------|-------------------------------|---------|
| newest | `0x00000001` — `01 00 00 00` (or the `0x40000001` sentinel) | newest ~45 |
| next older | the **oldest video handle** of the previous page (`0x40xxxxxx`, e.g. `80 2b 10 40` = `0x40102b80`) | next ~45, older |
| … | repeat with each page's oldest video handle | until a page adds nothing new |

- Only handles **`≥ 0x40000000`** (video records) advance the cursor — a stray low-namespace handle (a `0x0010xxxx` photo) is skipped so it can't jerk the cursor to the bottom and stall paging.
- Consecutive pages overlap by exactly the one boundary file, so **dedup by media path** (≈ 44 new per page).
- Two ways to sequence the pages: **a fresh registered session per page** (simplest, always works), or **inline on one long-lived session** with a correct sliding-window `ackSeq` (see *Datalink transport / sequencing*). Both return the same pages.

DUML examples:
- newest page (cursor `0x00000001`): <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>
- trigger (`4a040e10`): <https://b3yond.d3vl.com/duml/#551b0475020100a04000264a040e10010000000000010000008d86>
- next page (cursor `0x401036c0`): <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000c03610402d000d0100ffffffffffffffff000100000000000000000000000000a7d3>
- page after (cursor `0x40102b80`): <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000802b10402d000d0100ffffffffffffffff0001000000000000000000000000007701>

```python
import struct

_LIST    = bytes.fromhex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000")
_TRIGGER = bytes.fromhex("4a040e1001000000000001000000")
VIDEO_HANDLE_BASE = 0x40000000   # video record handles live here (0x4010xxxx on the Nano)

def list_cmd(cursor: int) -> bytes:
    """0x00/0x26 payload with a 4-byte little-endian handle cursor at bytes 10-13."""
    p = bytearray(_LIST)
    struct.pack_into("<I", p, 10, cursor)
    return bytes(p)

def next_cursor(page_handles, cursor):
    """The oldest video handle strictly older than `cursor`, or None once exhausted."""
    older = [h for h in page_handles if VIDEO_HANDLE_BASE <= h < cursor]
    return min(older) if older else None

def all_media(send_duml, collect_manifest, open_session):
    """`send_duml(0x00,0x26,payload)` queues a frame; `collect_manifest()` reassembles the
       0x00/0x27 stream + decodes it to records (see decode_manifest below); `open_session()`
       re-handshakes a fresh registered session and enters playback (0x02/0x0c 01 01 00 01)."""
    seen, cursor = set(), 0x40000001            # 0x40000001 == newest page
    while cursor is not None:
        open_session()                          # fresh session + playback, per page
        send_duml(0x00, 0x26, list_cmd(1))      # prime: query newest
        send_duml(0x00, 0x26, _TRIGGER)         # trigger the stream
        send_duml(0x00, 0x26, list_cmd(cursor)) # 2nd query's cursor selects the page
        page = collect_manifest()
        for f in page:                          # dedup the one-file boundary overlap
            if f.path not in seen:
                seen.add(f.path); yield f
        cursor = next_cursor([f.handle for f in page], cursor)
```

#### Burst / interval groups (expand a group's frames)

A burst or interval shoot is stored as a numbered group — `DJI_…_0286_D_001.JPG`, `_002`, `_003`, … The **normal list returns only the group lead (`_001`)**; standalone photos have no `_NNN` suffix, so the filename alone tells you it's a group.

To pull the whole group, **re-issue `0x00/0x26` seeded with the group's handle** — a targeted variant of the paging query:

- **handle** = placed at payload **bytes 10–13** (LE) where the paging cursor goes. On the Nano it's `0x40100000 + seq × 0x40` (`0286` → `0x40104780`), **but base/step are per camera *and* per store** — the Xtra's SD is `0x00040000`/`0x10`, its internal `0x40040000`/`0x10`. **Fit `base + seq × step` from the handles the manifest already exposes for each store**.
- **byte 14** = a frame limit (the app sends the exact count; a generous value works — the camera returns only the group), **byte 16 = `0x10`** ("group mode", vs `0x0d` for the full list), byte 39 = `0x01`.
- The camera replies with a small (~1.8 KB) manifest of **just that group** — every frame with its real path, thumb (`.thm`/`.scr`) and size. Decode it like any manifest; filter by the shared name base if it ever spills into older files.

#### Response to 0x00/0x26:

**Parsed — DJI CompositePack (TLV).** The reassembled manifest opens with a `u32-LE` file count (present on the Nano/Xtra/Pocket 3; **`0` on the Action 5/6** — count the records instead), then one record per file. Every field is **length-delimited**, so you read *tag → length → value*. The self-identifying anchor is the **media-path** field; the filename is read only for its extension:

```
0d <len:u8>              <ascii>        # filename "<base>.<ext>"  (read for the ext only)
1a <total:u8> 00 00 00 01 <ascii>       # media path, ascii = total-6 bytes, "DCIM/…" (NO ext)
1a <total:u8> 00 00 00 02 <ascii>       # thumb path,  "MISC/THM/…"
```

Each record carries a **marker** the header fields hang off: **videos `03 ff 19 06`** (`head = marker − 8`), **photos a shorter `[ff\|fe] 19 06`**. Size hangs off it for **both** (measured from the `19 06` pair, which is common to both marker shapes); handle/fps/resolution/duration are video-only, photos instead carry their pixel W×H:

| field | where | notes |
|-------|-------|-------|
| media path | `1a … 00 00 00 01` value | `DCIM/<folder>/<base>`, no extension |
| thumb path | `1a … 00 00 00 02` value | `MISC/THM/<folder>/<base>` |
| extension  | `0d` filename field | the only field carrying `.MP4`/`.JPG`/… |
| delete handle | `u32-LE @ head` (`head = marker − 8`) | **video only**; feeds `0x00/0x28` ([§2](#2-delete-media)); `0` = photo, not deletable |
| **media byte size** | **`u32-LE`, 14 B before the `19 06` pair** (= video `marker − 12`) | real file size, **video *and* photo** |
| proxy (`.LRF`) size | `u32-LE @ marker + 30` | the low-res sidecar's size |
| fps | rational `<u32 num><u32 den>` near the record | `a861 0000 e803 0000` = 25000/1000 = **25 fps** — what the parser reads |
| frameRate | `u8 @ marker − 2` | the `VideoFrameRate` enum for the same value (table below) |
| resolution *(video)* | `u8 @ marker − 1` | video-format index → pixel size (table below) |
| **duration *(video)*** | **`u16-LE @ marker − 4`** (= `head + 4`) | whole **seconds**; = `floor(moov ms / 1000)`. |
| **width, height *(photo)*** | **`u32-LE`, `+58` / `+62` from the `19 06` pair** | photo pixel dimensions (videos have none here — they use the resolution enum) |
| ⭐ starTag | `u8 @ [ff\|fe] 19 06 + 9` | favourite flag; the one field also present on photo records |

- **Two stores = two lists.** With a card in, the reassembled manifest is **two per-storage lists back to back** — **SD first, then internal** — each opening with its own `[u32-LE count][u32-LE size][u32-LE ts]…` header. The leading count covers only the *first* list; the rest belong to the second. Proven by dumping the same camera with and without a card: the no-card manifest is **byte-identical to the mixed manifest's second list**. The split is taken from the count, which every model writes.
- **The `/v2?storage=N` HTTP mount = the record handle's `0x40000000` bit**: set → internal → `storage=1`; clear → SD → `storage=0`. Confirm with one HEAD. It is **not** the manifest list ordinal — a single-store camera's one list is group 0 yet can mount at `storage=1`.

| camera | store | handle base | `storage=` |
|--------|-------|-------------|-----------|
| Xtra Edge Pro / Action 5 Pro | SD | `0x0004xxxx` | `0` |
| Xtra Edge Pro / Action 5 Pro | internal | `0x4004xxxx` | `1` |
| Osmo Nano | internal | `0x4010xxxx` | `1` |
| Action 6 | internal | `0x4010xxxx` | `1` |
| Pocket 3 | microSD (only store) | `0x0004xxxx` | `0` |
- **Naming is irrelevant to the parse.** Because the path/name are read by length, the camera's *Naming Management* custom **Folder** and **File** prefixes decode exactly like stock — `DCIM/DJI_001/DJI_…_D.MP4` (stock), `DCIM/DJI_001/DJI_…_D_OP3.MP4` (Pocket 3), `DCIM/DJI_001_OA5/DJI_…_D_DOA5.MP4` (Action 5, custom folder + file suffix), `…_D_A01.MP4` (a user-typed `A01`) — all the same.

**Read it in Python** (`struct` for the little-endian ints; the buffer is the reassembled `0x00/0x27` payload):

```python
import struct

def read_path(buf, i, sub, prefix):
    """A path TLV at buf[i]: 1a [total] 00 00 00 <sub> <ascii>, ascii = total-6 bytes."""
    if buf[i:i+1] != b"\x1a" or buf[i+2:i+5] != b"\x00\x00\x00" or buf[i+5] != sub:
        return None
    slen = buf[i+1] - 6
    value = buf[i+6 : i+6+slen]
    return (value, i+6+slen) if slen >= len(prefix) and value.startswith(prefix) else None

def decode_manifest(buf):
    # 1) enumerate media records by their most self-identifying field, the DCIM media path.
    medias, i = [], 0
    while i < len(buf):
        f = read_path(buf, i, sub=1, prefix=b"DCIM/")
        if f: medias.append((i, f[1], f[0].decode())); i = f[1]
        else: i += 1

    files = []
    for k, (pos, end, path) in enumerate(medias):
        lo = medias[k-1][1] if k else 0                       # this record's byte window…
        hi = medias[k+1][0] if k+1 < len(medias) else len(buf)
        folder, base = path.split("/")[1], path.rsplit("/", 1)[-1]

        ext, j = "", lo                                       # extension from the 0d filename field
        while j < hi - 2:
            if buf[j] == 0x0D and buf[j+2:j+2+len(base)+1] == (base + ".").encode():
                ext = buf[j+2+len(base)+1 : j+2+buf[j+1]].decode().upper(); break
            j += 1

        handle = size = 0                                     # handle/size behind the video marker
        m = buf.find(b"\x03\xff\x19\x06", lo, hi)
        if m != -1:
            head = m - 8
            handle = struct.unpack_from("<I", buf, head)[0]
            if ext in ("MP4", "MOV") and head >= 4:
                size = struct.unpack_from("<I", buf, head - 4)[0]    # media size @ marker-12 (= head-4)

        files.append(dict(folder=folder, name=f"{base}.{ext}" if ext else base,
                          handle=handle, size=size))
    return files

manifest_bytes = b""            # <- reassembled 0x00/0x27 payload from the camera
media_files = decode_manifest(manifest_bytes)

count = struct.unpack_from("<I", manifest_bytes, 0)[0] if manifest_bytes else 0
print(f"File count: {count or len(media_files)}")   # header count, or record count (Action 5/6 = 0)
for f in media_files:
    print(f"Folder {f['folder']} - Name {f['name']} - Size {f['size']}")
```

#### What a record *means* — DJI's `MediaFile` schema:

The `0x00/0x27` tagged record above is the **only** media-list wire format:

| field | type | notes |
|-------|------|-------|
| `fileName` | String | e.g. `DJI_…_D.MP4` — our `0d` field |
| `fileType` | enum `MediaFileType` | photo/video/… → the extension category |
| `fileSize` | **Long** | the real byte size — **mapped**: `u32-LE @ marker − 12` |
| `duration` | **Long** | video length (ms) |
| `frameRate` | enum `VideoFrameRate` | **mapped**: `u8 @ marker − 2`; our fps rational is the same value |
| `resolution` | enum `VideoResolution` | **mapped**: `u8 @ marker − 1` (table below) |
| `date` | `DateTime` | capture time |
| `starTag` | enum | favourite / marked flag — **mapped**: `u8 @ [ff\|fe] 19 06 + 9` |
| `orientation`, `cameraOrientation` | enum | rotation |
| `photoType`/`videoType`/`panoType`, `videoEncodeType`, `videoSpeedRatio`, `timeLapseInterval` | enum/int | mode metadata |
| `dirIndex`, `fileIndex`, `subIndex`, `segSubIndex`, `fileGroupIndex` | int | DCF indices |
| `proxyInfo`, `hasProxy`, `EXIFInfo` (`physicalPathInfo`), `dcfInfo` | nested | proxy/exif/DCF; the `DCIM/…`,`MISC/…` strings live in these nested `physicalPath`s |

##### Enum value tables (mined from the DJI app dex — for decoding the record's int fields)


**Start/Heart/Favorite** — the byte at `[ff|fe] 19 06` + 9 is DJI's `MediaFileStarTag`: `0 = NONE`, `1 = TAGGED` (starred). Cameras where it works: **Osmo Nano**.

**frameRate** (`marker−2`) — `VideoFrameRate`:

| code | fps |
|------|-----|
| `1` | 24 |
| `2` | 25 |
| `3` | 30 |
| `4` | 48 |
| `5` | 50 |
| `6` | 60 |
| `7` | 120 |
| `8` | 240 |
| `10` | 100 |
| `11` | 96 |
| `29` | 15 |

**`MediaFileType`**

| code | type |
|------|------|
| `0` | JPEG |
| `1` | DNG |
| `2` | MOV |
| `3` | MP4 |
| `4` | PANORAMA |
| `5` | TIFF |
| `10` | AUDIO |
| `19` | LRF |
| `20` | THM |
| `21` | SCR |
| `44` | OSV |
| `65535` | UNKNOWN |

**`MediaVideoType`**

| code | mode |
|------|------|
| `0` | NORMAL |
| `1` | SLOW_MOTION |
| `2` | HYPER_LAPSE |
| `3` | TIME_LAPSE |
| `4` | HDR |
| `5` | LOOP |
| `101`–`104` | MASTERSHOT |

**`MediaPhotoType`**

| code | mode |
|------|------|
| `0` | NORMAL |
| `1` | HDR |
| `2` | AEB |
| `3` | INTERVAL |
| `4` | BURST |
| `16` | HIGH_RESOLUTION |

**Resolution** (`marker−1`):

| code | resolution |
|------|-----------|
| `10` | 1920×1080 (1080p 16:9) |
| `12` | 1920×1440 (1080p 4:3) |
| `16` | 3840×2160 (4K 16:9) |
| `45` | 2688×1512 (2.7K 16:9) |
| `95` | 2688×2016 (2.7K 4:3) |
| `103` | 3840×2880 (4K 4:3) |


**Parsed — index-based** (older Osmo Action 1/2/3): header `[u32-LE count][u32-LE total_size]`, then fixed **65 B** records, **no path strings** (files keyed by numeric `FileIndex`):

| offset | type | field |
|--------|------|-------|
| `[0:4]`   | u32-LE   | Unix timestamp |
| `[8:12]`  | u32-LE   | **FileIndex** (`0x640251`…`0x640241`) |
| `[10:14]` | 2×u16-LE | DCF dir / file number (`100` = `100MEDIA`) |
| `[19:23]` | u32-LE   | video UUID (Amba `DjiMovDmx`) |
| `[38:42]` | u32-LE   | size-ish (~KB; a photo record reads ~0.6 MB) |

### 2. Delete media
- Cmd Set / ID: `0x00` / `0x28`  ·  App → Camera(`0x01`), datalink  ·  **irreversible on the card**
- Payload: `[count:u8][handle:u32-LE × count][count:u32-LE] 00 [count:u32-LE] 01 01 00 00`  — delete 1 file `h`: `01 <h> 01000000 00 01000000 01010000`
- `handle` = per-file object id from the manifest record head (below); the trailing `00 … 01 01 00 00` is a storage selector, verbatim from the capture.
- Response: `0x00/0x28` → `0000` = OK  ·  `00d6` = no such handle
- **Handle** — u32-LE at the record head, located by anchoring on the constant record marker `03 ff 19 06` (at head + 8, so `handle = u32 @ marker − 8`). Nano (361 B records) handles start `0x40104000` step `0x40`; the Action family — Xtra Edge Pro, Action 5/6, Pocket 3 (272 B records) — starts `0x40040000` step `0x10`. (Naming doesn't track the family: only the Xtra rebrand writes `CAM_…`, while genuine Action/Pocket units use `DJI_…`.) Photo records lack the marker → non-deletable (fail-safe).
- **Session** — a *write*: it lands inline on the live browse session when the `ackSeq` is correct (see *Datalink transport / sequencing*), else send it in a freshly-registered session (handshake → register → subscribe). Reads answer either way; only writes drop on a wrong `ackSeq`.
- DUML example (delete handle `0x40104480`): <https://b3yond.d3vl.com/duml/#551f044e020100a0400028018044104001000000000100000001010000a0d1>

### 3. Favorite / star media
- Cmd Set / ID: `0x02` / `0xBF`  ·  App → Camera(`0x01`), datalink
- Payload: `01 01 [handle:u32-LE] [counter:u32-LE] 00 [on:u8] 00 00 00`  — favorite handle `h`: `01 01 <h> 01000000 00 01 000000`
- `on` = `01` favorite, `00` un-favorite. `handle` is the **favorite index**: for videos it equals the manifest delete handle (#2); photos have no manifest handle, so derive it from the sequence number. **Base/step are per camera *and* per store** — Nano `0x40100000`/`0x40`, Xtra SD `0x00040000`/`0x10`, Xtra internal `0x40040000`/`0x10` — so **fit `base + seq × step` from the manifest's own handles per store**. `counter` is a per-action running index (Mimo sends 1, 2, …).
- Response: `0x02/0xBF` → `00` = OK
- **Session** — a *write*, sent with **playback mode active** (`0x02/0x0c 01 01 00 01`). Runs inline on the live session (correct `ackSeq`) or in a fresh registered session; read the `00` ack.
- DUML example (favorite handle `0x40104040`, seq 0257): <https://b3yond.d3vl.com/duml/#551c041b0201befd4002bf0101404010400100000000010000008c88>

### 3a. Highlight / moment marks
- Cmd Set / ID: `0x02` / `0xff`  ·  App → Camera(`0x01`), datalink  ·  the SDK's generic `camera_expansion_cmd` (`PullHighLightAction`)
- Request: `40 2f 00 01 0b 00 00 00 [handle:u32-LE] 00 00` — `handle` = the video's manifest delete-handle ([§2](#2-delete-media)).
- Reply: `00 · 40 2f 00 01 · [len:u32-LE] · [handle:u32-LE] · [count:u8] · 00 · { 00 [startTimeMs:u32-LE] } × count`. Count at reply byte 13, first mark at 16, stride 5.
- **Read-only**, so it runs inline on the live session. Each mark is a `startTimeMs` (ms); marks read as points (no separate duration).
- Example replies: a 2-mark clip → `4000, 7000` ms; a 3-mark clip → `1000, 3000, 5000` ms. Handles: Xtra `0x4004xxxx`, Nano `0x4010xxxx` (same command). See ROADMAP #7.

---

## Datalink session (sent before the list, over UDP)

### Datalink transport / sequencing — the one that makes commands land inline

Each UDP packet is `[8B udp hdr][12B routing hdr][DUML frame]`. It's a **sliding-window sequenced
transport**, and getting the sequencing right is what lets *every* command (delete, favorite, group-expand,
pagination, highlights) run on **one long-lived session** instead of a fresh registered session per op.

- **udp hdr** `[8]`: `[len|0x8000 :u16][sessionId:u16][seq:u16-LE][pktType:u8][xor:u8]`.
- **routing hdr** `[12]`: **`[ackSeq:u16-LE][ownSeq:u16-LE]` 00 00 00 00 `[counter:u8]` 01 00 00**.
- **pktType**: `0x00` handshake · `0x01` camera data/telemetry · `0x04` **ACK** (of the camera's stream) ·
  `0x05` **command** (carries a DUML frame).

For a **command** packet, `ownSeq` (= the udp-hdr seq)
is the app's **own monotonic `+8` counter**, started at `camera_channel + 8` at registration; it wraps at
`0xFFFF` and is *independent* of the camera. `ackSeq` is the **last of our own seqs the camera echoed back**
— it lags `ownSeq` by 8–150, and **stays in our own seq space**. Separately, an ACK packet (`0x04`, seq 0)
carries `[camSeq][camSeq]` to acknowledge the camera's telemetry stream.

**Do not** put the camera's telemetry seq in a command's `ackSeq`: the camera floods telemetry ~10×
faster than the app's commands and its seq wraps to a different phase, so an `ackSeq` tracking it diverges
from `ownSeq` and the receiver window **silently drops writes** (reads stay lenient). Correct value:
**`ackSeq = ownSeq − 8`** (the previous command seq).

**Inline commands:** the keep-alive thread owns the socket, so a command that needs a reply needs to be **queued**
for that thread, see exampke in `DatalinkClient.runCommand`
/ `runManifestQuery`. Skip the empty-payload transport ACK the camera sends *before* the real reply.
Playback mode (`0x02/0x0c 01 01 00 01`) is held for the whole browse session (some inline reads/writes need
it), not entered per-fetch.

### 4. Handshake  *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo. Then drain heartbeats, learn `camera_channel` (heartbeat routing `[8:10]`); app UDP seq starts at `camera_channel + 8`.

### 5. Device info
- Cmd Set / ID: `0x00` / `0x81`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Payload: `00 "APP" 00×37 02 00×8 02 08 00×10` (62 B — 1+3+37+1+8+2+10)
- DUML example: <https://b3yond.d3vl.com/duml/#554b0402024800a08000810041505000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000020800000000000000000000ad80>

### 6. Register
- Cmd Set / ID: `0x00` / `0x88`  ·  App → DM368(`0x08`, id 1)
- Payload: `170008237b41505000000000000002`
- DUML example: <https://b3yond.d3vl.com/duml/#551c041b022800a0400088170008237b41505000000000000002d9e6>

### 7. Init
- Cmd Set / ID: `0x03` / `0xDA`  ·  App → Gimbal(`0x03`)
- Payload: `05ffffffff`
- DUML example: <https://b3yond.d3vl.com/duml/#551204c7020300a04003da05ffffffff4490>

### 8. Subscribe param  *(the settings surface, over BLE)*
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1), `cmd_type 0x40`
- **Works over BLE exactly as on the datalink.** Each subscribe is ACKed `plen=10`, then the camera sends that parameter's value and every later change, unprompted.
- **Subscribe payload — one frame PER PARAMETER, verb `0x02`:**
```
02 02 00 00 | sub_id:u32-LE | 00 00 00 | (name_len+6):u16-LE | name_len:u16-LE | <name ascii> | 00 00 00 00
```
  The name-length field is **u16-LE** (not u8) and the name is **not padded** — frames are variable length (`camcap_base` = 30 B, `camcap_photo_time_limited_burst_param` = 56 B). `sub_id` increments per subscription.
- ⚠ **There is no working group subscribe.** A single `01 00 06 00 "camera"` (verb `0x01`) is **ACKed with `plen=0` and never sends an item** (indistinguishable from an unsupported channel). Subscribe each name individually.
- **Push payload — self-describing, so no `sub_id` bookkeeping is needed:**
```
02 06 00 00 | idx:u32-LE | 00 00 00 | total_len:u16-LE | name_len:u16-LE | <name> | 00 x6 | value_len:u16-LE | <value>
```
- 🔑 **Naming rule: `camcap_*` = what the body SUPPORTS (a capability table); `cam_*` = the CURRENT value.** Subscribing to `camcap_fov`/`camcap_eis` gives the supported modes, never the active setting.
- ⏱ **`cam_*` values re-push continuously (~0.5–1 Hz); `camcap_*` tables are sent once, right after subscribe.** A capability table is easy to miss (sent once, in the burst after connect) — be ready to receive it then, or re-subscribe.
- 🧪 **Method for an unmapped `cam_*` value: A→B→A on hardware.** Log the value at rest, change exactly ONE setting on the camera, change it back, and keep only the byte that moved *and returned* (a byte that moves once is drift). ⚠️ Do not sweep a value space to find codes — enumerating `0x02/0xE1` froze a Nano solid (power-cycle).

**Decoded values** (Nano):

| name | contents |
|------|----------|
| `cam_video_param_v2` | **`[resolution:u8][fps_idx:u8]…`** — the live video setting. `67 02` = res 103 (4K 4:3) @ fps idx 2 (25 fps). Codes match [§1](#1-get-media-list)'s `VideoResolution`/`VideoFrameRate` tables. |
| `camcap_video_format` | **capability list**: `01 \| len:u16-LE \| count:u8 \| count × [res:u8][fps_idx:u8][flags:u8]`. Self-validating (`3×35+1 = 106` = declared len). Nano returns 35 pairs — 4K 16:9, 2.7K 16:9, 2.7K 4:3, 1080p and res `0x0c` at 24–60; **4K 4:3 caps at 50**. `0x0c` (12) = **1920×1440 (1080p 4:3)** per [§1](#1-get-media-list)'s Resolution table — the same enum, so the capability list and the manifest read through one another. |
| `cam_photo_param_new` | **the live PHOTO setting** (24 B) — `[?][0x15][00][size:u8][aspect:u8]…`, i.e. **size @ byte 3, aspect ratio @ byte 4**. `02 15 00 04 00 …` = L, 4:3. Sizes are the camera's own **letter** labels, *not* megapixels (the pixel count differs per body), and they do **not** use [§1](#1-get-media-list)'s `VideoResolution` enum. Size `0x03` = M, `0x04` = L — **a Nano offers only these two, there is no S**, so the size enum is complete for this body; expect other bodies to add codes rather than reuse these. Aspect `0x00` = 4:3, `0x01` = 16:9. Needed because `cam_video_param_v2` keeps reporting the *video* resolution while the camera sits in photo mode, so a UI that reads it in photo mode shows a wrong spec. |
| `cam_storage` 40 B · `cam_status` 9 B · `cam_record_time` 6 B · `cam_image_effect` 16 B · `cam_lens_state` 66 B · `cam_custom_mode_params` 161 B | present, not yet decoded |

- **All 53 names Mimo subscribes** (the complete settings surface): `camcap_base camcap_video_format camcap_fov camcap_iso camcap_photo_storage_format camcap_color_mode camcap_wb camcap_photo_size camcap_video_codec camcap_shutter camcap_photo_timer_interval camcap_exposure_mode camcap_zoom camcap_antiflicker camcap_sharpness camcap_denoise camcap_aperture camcap_shutter_max camcap_eis camcap_iso_auto_max camcap_loop_video_duration camcap_hyperlapse_ratio camcap_slowmotion_ratio camcap_timelapse_duration camcap_countdown camcap_photo_time_limited_burst_param camcap_capture_aspect_type camcap_style_filter_mode cam_storage cam_status cam_record_time cam_expo_param shutter_param cam_photo_param_new cam_lapse_param cam_video_param_v2 cam_image_effect v_quality_enhance_status cam_fov cam_lens_state cam_audio_status_v2 audio_timecode_status temp_curve camcap_common cam_imu_calib_info timecode_info cam_custom_mode_params cam_super_slowmotion_status media_file_sync upgrade_status cam_capture_aspect_type gui_autorecord_param cam_style_filter_status`
- DUML example (`cam_status`, original capture): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

### 9. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Response: NUL-separated ASCII `sdk\0name\0firmware` — scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

Cmd Set `0x02`, App → Camera (`0x01`). **Every app→camera frame must use `cmd_type` `0x40`** (request; `0xC0` = response) — a `0x00` frame is silently dropped before the dispatcher. The upstream repos' `0x02/0x20`/`0x21` record commands answer `e0` (unsupported) on Osmo firmware; **`0x02/0x02` is the record control** ([§11](#11-start-recording)/[§12](#12-stop-recording)).

> [!CAUTION]
> Works only on the Nano. On an **Xtra Edge Pro** (Action-family rebrand), commands to receiver `0x01` get **no reply at all** — though the same camera answers `0x07/0x45` pairing, the `0x53/0x10` wake, and streams `0x02/0x80` status, so the link is healthy. The camera command set differs between the two families; don't assume these opcodes port across bodies.

Once the link is up the camera answers *every* request, so the **reply byte is an oracle** — send an unknown cmdId with an empty payload and read the reply to map the command space:

| reply | meaning |
|---|---|
| `00` | success |
| `d9` | supported, **wrong state** (e.g. already recording) |
| `df` | supported, **wrong parameter** |
| `e3` | supported, **bad/missing parameter** |
| `e0` | **not supported** |
| *(no reply)* | that receiver does not exist |

### 10. Shoot photo — `0x02/0x01`
- Cmd Set / ID: `0x02` / `0x01`  ·  `cmd_type 0x40`  ·  receiver `0x01` (datalink)  ·  payload `[01]`
- Reply `0x02/0x01` (ack, `cmd_type 0xc0`) with payload `00` = success.
- **One press = one capture in the current photo mode.** `[01]` is a generic shutter *trigger*, **not** the photo type — the mode (single / burst / interval / HDR / …) is set separately ([§13a](#13a-set-shooting-mode)), and the camera completes a burst/interval on its own (no stop press, unlike record [§11](#11-start-recording)/[§12](#12-stop-recording)).
- Symmetric with record: photo = `0x02/0x01 [01]` (shoot); record = `0x02/0x02 [01]`/`[00]` (start/stop).
- `[01]` is required — an **empty** payload answers `e3` (parameter missing); `[01]` in a *video* shooting mode answers `d9` (wrong state), never `e0`. So set the shooting mode first ([§13a](#13a-set-shooting-mode) `0x02/0xE1 [05]`) — `d9` means "right command, wrong mode", not "unsupported".

### 11. Start recording
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[01]`
- Reply `00`; recording starts ~860 ms later (the `0x02/0x80` recording bit sets — [§18](#18-camera-status)).
- DUML example: <https://b3yond.d3vl.com/duml/#550e046602010204400202014e61>

### 12. Stop recording
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[00]`
- Reply `00`; the recording bit clears ~2.4 s later. **Not a toggle** — re-sending `[01]` while recording answers `df`, so drive start/stop off the decoded recording bit ([§18](#18-camera-status)), never by toggling blind.
- DUML example: <https://b3yond.d3vl.com/duml/#550e04660201020440020200c770>

> ⚠️ **Control does not work on Xtra over BLE**

### 13. Set mode — ⚠️ *this is the **work** mode, not the shooting mode (see [§13a](#13a-set-shooting-mode))*
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`
- Nominally `0` Photo · `1` Video · `2` Playback · `3` SlowMo · `4` Timelapse · `5` Panorama — but `0x02/0x02` **is** the record control above, so on the Nano `0`/`1` **stop/start a recording** rather than switch a mode. ⚠ A "Video" button mapped to `[01]` starts a recording behind the user's back — exclude `0`/`1` from any mode switcher.
- Valid range is `0`–`3` (`[04]` answers `df`), i.e. DJI's four-value *work* mode — capture / record / playback / download. `[03]` is accepted but changes nothing visible. **To change the shooting mode use `0x02/0xE1`.**

### 13a. Set **shooting mode**
- Cmd Set / ID: `0x02` / `0xE1`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`  ·  reply `00`

| value | mode | DUML example |
|-------|------|--------------|
| `0x00` | SlowMo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10036b3> |
| `0x01` | Video | <https://b3yond.d3vl.com/duml/#550e0466020102044002e101bfa2> |
| `0x02` | TimeLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1022490> |
| `0x05` | Photo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1059be4> |
| `0x0a` | HyperLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10a6c1c> |
| `0x28` | SuperNight | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1287c1e> |

- **The enum is sparse and unordered — table it, never compute it.** The camera's on-screen carousel order is Video → Photo → TimeLapse → HyperLapse → SuperNight → SlowMo, which is *not* the numeric order.
- **Readback:** the camera echoes the current mode in its `0x02/0x80` push at **byte `@57`**, same encoding — so mode is both settable and observable, and a remote stays in sync when the user changes it on the camera.

### 14. Camera parameters

`0x02/0x8E` is a keyed parameter store, not a heartbeat: the `00 01 14 00` payload Mimo sends ~15 Hz while browsing is simply *GET pid `0x0014`*. (The BLE keepalive `0x00/0x2b 01 01` ([§21](#21-session-wake--keepalive)) is what keeps the camera awake.) Both directions work over BLE — App → Camera(`0x01`), `cmd_type 0x40`:

```
GET = 00 01 <pid:u16-LE>                    -> 00 00 01 <pid:u16-LE> <len:u8> <value…>
SET = 01 01 <pid:u16-LE> <len:u8> <value…>  -> 00
```

A GET for a pid that isn't valid in the current state answers a **single error byte** instead of a value (`e3` most often, then `df`, `d9`) — so a sweep doubles as a map of which pids exist. Note this contrasts with [§8](#8-subscribe-param--the-settings-surface-over-ble)'s `0x00/0x99`: over BLE the group-subscribe there is ACKed with `plen=0` and **zero items ever follow**, so on real hardware `0x02/0x8E` — not `0x00/0x99` — is the control surface that actually works.

**Known pids**

| pid | field | values | status |
|-----|-------|--------|--------|
| `0x0009` | **field of view** | `05` = Natural-Wide · `01` = Wide | switches FOV on Video mode |
| `0x000f` | **ISO limit** | `04` = 100-800 · `05` = 100-1600 | writing it switches the ISO range on Video mode |

- DUML example (GET pid `0x0009`): <https://b3yond.d3vl.com/duml/#551104920201020440028e00010900778d>
- DUML example (**SET** pid `0x0009` = `01`, Wide): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010900010189d4>
- DUML example (**SET** pid `0x0009` = `05`, Natural-Wide): <https://b3yond.d3vl.com/duml/#551304030201020440028e010109000105ad92>
- DUML example (**SET** pid `0x000f` = `04`, ISO 100-800): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010f000104bec8>
- DUML example (**SET** pid `0x000f` = `05`, ISO 100-1600): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010f00010537d9>
- DUML example (the datalink poll Mimo sends, GET pid `0x0014`): <https://b3yond.d3vl.com/duml/#55110492020100a040028e00011400a858>

### 15. Camera state query
- Cmd Set / ID: `0x02` / `0xA0`  ·  cmd_type PUSH  ·  empty payload
- Response: 28 B — `recording_time_s` = `u16-LE @ byte 6`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002a0f5c3>

### 16. Camera status poll
- Cmd Set / ID: `0x02` / `0x61`  ·  cmd_type PUSH  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002617014>

### 17. Set time & timezone
- Cmd Set / ID: `0x00` / `0x6A`  ·  App -> Camera, **receiver `0x28`** (the system/RTC subsystem, the
  same one command 8 subscribes to). The media receiver `0x01` **silently drops it** — this is the
  one gotcha.
- Payload: `01 00` · `[unix seconds : u64-LE]` · `[UTC offset minutes : u16-LE, signed]` · `[tz len : u8]` · `[IANA tz id, ASCII]`

| bytes | field | example (`Europe/Madrid`, offset +120 min) |
|-------|-------|--------------------------------------------|
| `00-01` | prefix | `01 00` |
| `02-09` | unix seconds, `u64-LE` | `ce 2a 66 6a 00 00 00 00` |
| `10-11` | UTC offset minutes, `u16-LE` signed | `78 00` (= 120) |
| `12`    | tz-id length, `u8` | `0d` (= 13) |
| `13..`  | IANA tz id, ASCII | `45 75 72 6f 70 65 2f 4d 61 64 72 69 64` |

- Response `55 … C0 00 6A 00 01 00 …` — first payload byte `0x00` = **OK**.
- The camera clock snaps to the sent value and recorded file timestamps follow. Osmosis sends it right
  after registration on every connect.
- DUML example (set `Europe/Madrid`): <https://b3yond.d3vl.com/duml/#55270415022828f740006a0100ce2a666a0000000078000d4575726f70652f4d61647269640c0e>

---

## Status pushes (camera → app, decoded not sent)

### 18. Camera status
- Cmd Set / ID: `0x02` / `0x80`  (~10 Hz push, 60 B)
- **`payload[0]` is a bitfield, not an enum.**

| offset | type | field | idle → recording |
|--------|------|-------|------------------|
| `@0` | `u8` bitfield | **bit7 = recording** | `01` → `81` |
| `@5` | `u32-LE` | storage total, MiB | unchanged |
| `@9` | `u32-LE` | storage free, MiB | falls while recording |
| `@17` | `u16-LE` | **remaining recordable seconds** | counts down (reads `0` in Photo mode) |
| `@29` | `u16-LE` | **elapsed record time, seconds** | `0` → counts up |
| `@57` | `u8` | **current shooting mode** ([§13a](#13a-set-shooting-mode) encoding) | changes with the mode |
| `@4` | `u8` | `1` = a video-ish mode, `0` = Photo | — |
| `@13` | `u16-LE` | photos remaining | `0` outside Photo mode |

**Worked example — the same camera in three modes:**

| offset | Video | SlowMo | Photo | field |
|--------|-------|--------|-------|-------|
| `@57` | `01` | `00` | `05` | **shooting mode** — matches [§13a](#13a-set-shooting-mode) exactly |
| `@4` | `01` | `01` | `00` | video-vs-photo flag |
| `@17` `u16-LE` | 1050 | 953 | **0** | remaining recordable seconds (meaningless in Photo) |
| `@13` `u16-LE` | 0 | 0 | **5048** | photos remaining (meaningless outside Photo) |

Note `@17` and `@13` are **mutually exclusive** — each reads 0 in the modes where it doesn't apply, so don't render either without checking `@4` or `@57` first, or a Photo-mode UI will show "0 seconds left".

> **`@57` uses the *same* encoding as the `0x02/0xE1` write values** — not a separate enum. `@57` reads `01` in Video, matching the `0x01` write value. If the two ever appear to disagree, the mode→value mapping is wrong, not the encoding.

- `@17`/`@29` are enough to drive a live recording timer and a "space left" readout without polling anything.
- Quirks: reports the **active store only** (internal vs SD).

### 19. SD / storage  *(both stores in one frame)*
- Cmd Set / ID: `0x02` / `0xDC`  ·  App ← Camera, datalink
- **Byte 2 = store count.** Two-store bodies are 32 B (**card @6/@10**, **built-in @24/@28**); a
  single-store body (Pocket 3 = microSD only) is 22 B with just the first block. Decode gate is
  `size >= 22`, not `>= 32` — the wider gate dropped the Pocket 3 frame and it never reported storage.

| offset | type | field |
|--------|------|-------|
| `@6`  | `u32-LE` | SD **total** MiB (`0` = no card) |
| `@10` | `u32-LE` | SD **free** MiB |
| `@24` | `u32-LE` | internal **total** MiB (absent on a 22 B frame → report `0`) |
| `@28` | `u32-LE` | internal **free** MiB |

- **Card present = SD total > 0**, not a flag byte. Byte 0 is *not* an "SD inserted" bit — it reads
  `0x11` on a camera with **no** card and `0x00` on cameras **with** one (i.e. backwards).
- Examples: an Action 6 reads `@6/@10` = 121785/109748 MiB (= its on-screen 118.9/107.2 GB); an Action 5
  Pro and its Xtra rebadge both report 48980 MiB built-in; a card-less Xtra reads `@6 = 0`.

### 20. Battery / power *(also the only place the dock reports in)*
- Cmd Set / ID: `0x0D` / `0x02`  (34 B, ~1 Hz push)  ·  sender Battery(`0x05`), id `0`

| offset | type | field |
|--------|------|-------|
| `@1`  | `u16-LE` | pack voltage, mV (≈3300–4450) |
| `@5`  | `i32-LE` | current, mA — **signed**: `+` charging, `−` discharging |
| `@17` | `u16-LE` | temperature? (reads 45.0 / 47.0 °C) — **unconfirmed** |
| `@20` | `u8`  | charge percent, 0–100 |
| `@27` | `u8`  | **dock attached** (`0x40` docked, `0` not) |
| `@32` | `u8`  | **taking charge** (`1` / `0`) |

- **The dock is not a separate DUML device** — no second battery (`type 0x05, id != 0`) or new sender
  address appears when docked, so `@27` / `@32` here are the *only* dock signal on the wire.
- `@27` and `@32` are separate flags, not one "charging" bit: a transition read `@27=0x40` with `@32=0`
  and only −175 mA — physically docked but not yet drawing charge.
- **Not reported anywhere:** the dock's *own* charge level, and the dock's SD-card capacity — `0x02/0x80`
  (#18) covers the **active** store only.

---

## Connection (BLE control — prerequisites to reach media)

### Waking a sleeping camera

A sleeping Osmo Nano **keeps advertising `ADV_IND`** under its own name, so there is no wake *broadcast*
to send — DJI's R-SDK documents a `WKP` manufacturer-data advertisement, but that didn't work on Nano. The wake is an ordinary **command sequence** over GATT `fff5`:

| # | write | receiver | note |
|---|-------|----------|------|
| 1 | `0x00/0x2b` `04 00` | `0xF0` | first thing Mimo writes, **before** pairing |
| 2 | `0x07/0x45` SetPairingPIN | `0x07` | see #24 |
| 3 | `0x00/0x2b` `01 01` | `0xF0` | then repeating ~1 Hz, forever, as the keepalive |
| 4 | `0x53/0x10` `00 00 00 00` | `0x1C` | camera answers `01 00 00 00` and **wakes** |

Space the writes so `fff5` (write-without-response) doesn't drop back-to-back frames — the floor is roughly the BLE connection interval. Mimo bursts consecutive writes **~8–40 ms** apart (p50 **9 ms**, 71% of gaps under 50 ms); ~100–500 ms is a conservative margin, not a hard requirement.
Mimo does **not** send ConnectToWiFi (#25) anywhere in this flow.

### 21. Session wake / keepalive
- Cmd Set / ID: `0x00` / `0x2b`  ·  App(`0x02`) → **`0xF0`** (type `0x10`, id 7), BLE
- Payload: `04 00` = open the session (sent once, pre-pairing) · `01 01` = keepalive (repeat ~1 Hz)
- Quirks: the Nano drops an idle paired link after ~5–6 s, so the `01 01` ping must keep running for the
  whole session. Re-sending SetPairingPIN as the keepalive instead is noisier and can get a sleeping
  camera to drop you.
- DUML example (`04 00`, verbatim from a Mimo capture): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b04009ab9>
- DUML example (`01 01` keepalive): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b0101abd6>

### 22. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`; token `"osmo"`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval popup on camera; approval then arrives as a **`0x07/0x46` request** (flags `0x40`), which is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

### 23. ConnectToWiFi (AP bring-up — fallback only)
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's *own* creds
- Response: `0x07/0x47` `00 00` = ok; AP comes up ~15 s later
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 24. GetWifiSsid
- Cmd Set / ID: `0x07` / `0x07`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString ssid]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472>

### 25. GetWifiPassword
- Cmd Set / ID: `0x07` / `0x0e`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString passphrase]`
- Quirks: **give it a beat after GetWifiSsid** (`fff5` is write-without-response; Mimo actually spaces these only a few tens of ms — see [§20](#20-battery--power-also-the-only-place-the-dock-reports-in) — so ~500 ms is just a safe margin). The Nano may not surface a password here — fall back to its saved credentials.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 26. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>

---

## DJI Drone QuickTransfer media offload

> Following is applicable to **Mavic 3, Mavic 3 Classic, Mavic 3 Pro** for now. Other drones to be confirmed.

A drone runs the same DUML stack as an Osmo, with four differences that break a camera client outright:

| | Osmo camera | DJI drone |
|---|---|---|
| Pairing token | `osmo` | **`DJI FLY`** — any other token pairs but yields **no WiFi creds** |
| Datalink | UDP `9004` + TCP-7001 poke (Xtra: `10004`) | **UDP `9003`, no poke**, bind local port `9003` (symmetric) |
| Session | handshake → registration → commands | handshake → **`0x51` session-open** ([§27](#27-session-open-0x51--required-before-anything-else)) |
| Media addressing | paths, `/v2?storage=N&path=…` | **DCF indices**, `/v1?file_index=…` ([§29](#29-http-media-api-v1--dcf-indexed)) |
| Registration | `0x00/0x81`, `0x00/0x88`, `0x03/0xda`, param subs | **none** — go straight to commands |

Addressing byte is unchanged: App `0x02`, Camera `0x01`. The `0x51` channel uses its own endpoints
(`0xee` app, `0xe9` drone) outside the `(id<<5)|type` scheme.

### 27. Session open (`0x51`) — required before anything else

A drone answers **no command at all** until this completes. Before it, it emits ~2 DUML frames/s of empty
keepalive; one second after, ~1200 frames/s and every command works.

- Cmd Set: `0x51`
- Cmd ID: `0x02` open · `0x08` challenge · `0x06` identity · `0x13` beacon
- Dir / transport: App(`0xee`) ⇄ Drone(`0xe9`), datalink
- Wrapper: every `0x51` frame is an **inner DUML frame + 22 trailing bytes**, carried as the payload of an outer `0x51/0x01` frame (target `0xe93b`)

| step | dir | frame | flags | inner payload |
|---|---|---|---|---|
| 1 | → | `0x51/0x13` | `0x00` | app identity (answers the beacon) |
| 2 | → | `0x51/0x02` | `0x40` | `05 01 04 01 00` |
| 3 | ← | `0x51/0x08` | `0x40` | drone serial + app id (challenge) |
| 4 | → | `0x51/0x08` | `0xC0` | `00 00 11 <serial:20> 00` |
| 5 | → | `0x51/0x06` | `0x40` | `04 02 00 <appid:19> 00 00 00 11 <serial:20> 00` |
| 6 | ←→ | `0x51/0x06` | `0xC0` | serial echo, both directions |

- **Serial** = 20 ASCII bytes after the `0x11` tag in the drone's own `0x51/0x13` beacon.
- **Trailing bytes** `39fdb2ae 02 <ctr> 00 00 00 79102e9b 01 00×8` — **`ctr` (byte 5) must increase on every `0x51` frame sent**. A repeated or decreasing value is dropped as a replay, with no reply at all.
- **Outer DUML message id** is a per-frame counter from `1`, not a constant.
- DUML example (`0x51/0x02` open, outer frame): <https://b3yond.d3vl.com/duml/#553504683be90100005101551204c7eee97c004051020501040100619639fdb2ae020100000079102e9b010000000000000000f340>

Two fields that look like flow control but are not: the routing header's `r0-1` on a **received** packet
is not a running ack (it repeats the handshake channel and only moves when a reply lands), and the
sequence window is not enforced — the reference app runs ~1600 packets ahead of it.

### 28. Get media list (drone)

- Cmd Set: `0x00`
- Cmd ID: `0x26`  (response `0x00/0x27`)
- Dir / transport: App → Camera(`0x01`), datalink
- Payload (newest page): `4a002110 0c00 00000000 01000000 2d 000d0100 ffffffffffffffff 000100000000`
- Response: chunked `0x00/0x27` frames, subtype `0x01`
- DUML example: <https://b3yond.d3vl.com/duml/#552e04a7020177c94000264a0021100c0000000000010000002d000d0100ffffffffffffffff000100000000c085>

The `0x4a` envelope (both directions, all subtypes):

| off | size | field |
|---:|---|---|
| +0 | u8 | `0x4a` |
| +1 | u8 | subtype — see below |
| +2 | u16 | low 12 bits = this frame's payload length; bit `0x1000` = **final chunk** |
| +4 | u16 | seq (reply echoes the query's) |
| +6 | u32 | chunk index |
| +10 | u32 | *(list reply chunk 0 only)* total file count |
| +14 | u32 | *(list reply chunk 0 only)* total manifest bytes |

Reading `+2` as a `u8` parses short frames and silently corrupts every long one.

#### Transfer lifecycle

Subtypes are a family per transfer kind — `+0` query, `+1` reply, `+2` proceed, `+3` state, `+4`
release. A media list is `0x00`–`0x04`, a thumbnail `0x20`–`0x24`. `seq` is one monotonic counter
shared by both kinds.

| subtype | dir | meaning | bytes |
|---:|---|---|---|
| `0x00` / `0x20` | → | query | 33 B list · 48 B thumb |
| `0x01` / `0x21` | ← | data, chunked | |
| `0x02` | → | proceed, answering a state frame | `4a020f10 <seq:u16> 00000000 0000000000` |
| `0x03` / `0x23` | ← | transfer state: raised before the data, and again once it ends | `4a030a00 <seq:u16> 00000000` |
| `0x04` / `0x24` | → | **release the transfer** | `4a040e10 <seq:u16> 00000000 01000000` |

**A transfer holds a slot until it is released, and there is a finite number of them.** Leak them and
the drone stops answering media queries while telemetry keeps streaming at full rate — a healthy-looking
link that serves nothing. Release every transfer, including one that returned no data and one abandoned
part-way (the reference app cancels by sending the release immediately after the query).

If a state frame arrives before any data, answer it with `0x02` or the drone will keep waiting.

**Reassembly.** A reply spans several 1472-byte packets and single frames straddle packet boundaries.
The manifest rides `pktType 0x03`; strip each packet's **8-byte transport + 12-byte routing header**
before concatenating, or every straddling chunk fails CRC and disappears.

- Query byte 14 (`0x2d` = 45) is the page size.
- **Paging cursor** = query bytes 10–13, `u32-LE`. `1` = newest page; an older page passes the **oldest `file_index` of the page just received**, which the drone replays as that page's first record — dedup by index. No playback mode, no fresh session.

#### Record — fixed 94 bytes, newest first

| off | size | field |
|---:|---|---|
| +0 | u32 | mtime, **FAT/DOS packed** (not unix) |
| +4 | u32 | file size, bytes |
| +8 | u32 | **`file_index`** — packed, see [§29](#29-http-media-api-v1--dcf-indexed) |
| +12 | u16 | duration, whole seconds (`0` = still) |

No filename is transmitted; it is reconstructed from the index. Fields past `+14` are unmapped.

```python
import struct, datetime

def fat_to_datetime(v):                      # +0 is FAT, not unix
    date, time = v >> 16, v & 0xFFFF
    return datetime.datetime(
        1980 + (date >> 9), (date >> 5) & 0x0F, date & 0x1F,
        time >> 11, (time >> 5) & 0x3F, (time & 0x1F) * 2)   # seconds stored /2

def decode_manifest(blob):                   # blob = chunks concatenated, envelopes stripped
    for off in range(0, len(blob) - 93, 94):
        mtime, size, index, dur = struct.unpack_from("<IIIH", blob, off)
        storage, dir_index, file_no = index >> 30, (index >> 16) & 0x3FFF, index & 0xFFFF
        if not (100 <= dir_index <= 999 and file_no):
            continue                         # phase lost — a chunk is missing
        yield dict(index=index, storage=storage,
                   name="DJI_%04d.%s" % (file_no, "MP4" if dur else "JPG"),
                   path="DCIM/%dMEDIA/DJI_%04d" % (dir_index, file_no),
                   size=size, duration=dur, mtime=fat_to_datetime(mtime))
```

### 29. HTTP media API (`/v1`) — DCF indexed

`lighttpd/1.4.55`, TCP **80**, no auth. Response carries `Accept-Ranges: bytes`, `Content-Range` and a
`Last-Modified` that matches the manifest's FAT mtime.

```
GET /v1?file_index=<u32>&file_subtype=<S>&file_seg_subindex=<G>
```

All three parameters are expected — the connection is closed when one is missing.
`file_seg_subindex` selects a part of a segmented recording; `0` = whole file. **It is a real per-file
value, not a constant**: the reference app reads it off each file's own record rather than hardcoding
zero, so a segmented recording is only reachable by passing the right one.

**A missing file is reported by closing the connection with no response at all**, not by a 404 — so a
client sees an IOException where it expects a status code. (Every URL that is not `/v1` or `/v2` takes
the same path, which is why `GET /` returns an empty reply.)

The reference app only ever builds a `/v1` URL with `file_subtype=0`. Every other rendition it fetches
by **physical path over `/v2`**, taking the path from the file's own record and appending the extension
for the type it wants — which is exactly the `/v2?storage=N&path=…` shape the cameras use.

**`file_index` is a packed 32-bit field**, not a flat number:

| bits | width | field |
|---|---|---|
| 31:30 | 2 | storage id |
| 29:16 | 14 | DCF directory (`100` → `100MEDIA`) |
| 15:0 | 16 | DCF file number (`554` → `DJI_0554`) |

| storage id | medium |
|---:|---|
| 0 | SD card |
| 1 | internal eMMC |
| 2 | NVMe SSD |
| 3 | reserved / unset |

`file_subtype` is a **19-value enum**, recovered in full (with its own names) from a decompiled
DJI-derived app. Only the five below are exercised here; the rest are listed because guessing at a
number is how you end up with a connection close and no idea why.

| `file_subtype` | name | content | on-card path |
|---:|---|---|---|
| 0 | ORIGIN | original full-res | `DCIM/<dir>MEDIA/DJI_<n>` |
| 1 | THUMBNAIL | thumbnail (`.thm`) | `MISC/THM/<dir>/DJI_<n>` |
| 2 | SCREEN | screen-res render (`.scr`) | `MISC/THM/<dir>/DJI_<n>` |
| 17 | AIS | sensor data | `MISC/THM/<dir>/DJI_<n>` |
| 18 | PROXY | low-res proxy video (`.lrf`) | `DCIM/<dir>MEDIA/DJI_<n>` |

The rest: 3 CLIP · 4 STREAM · 5 PANO · 6 PANOSCREENNAIL · 7 PANOTHUMBNAIL · 8 TIMELAPSESCREENAIL ·
9 FILE · 10 CUSTOM_DATA · 11 PHOTO_METADATA · 12 USER_CTRL_INFO · 13 JSON · 14 PAYLOAD_WIDGET_JSON ·
**15 PROXY_MOOV** · **16 ORIGIN_MOOV**.

The two `_MOOV` subtypes are worth noting: an MP4's `moov` atom served on its own, without the media
data. Streaming a clip currently costs a range request for the `moov` before playback can start, so
these would replace that with one small fetch. Untested — the Neo 2 firmware answers "Not support this
subtype yet!" for everything in 3–16, so support is per-model.

Extensions per type, from the same source: `.jpg .dng .mov .mp4 .pano .tiff .log.lz4 .seq .tiff.seq
.lrf .thm .scr`.

**Which renditions actually exist depends on the media.** On a Mavic 3 a video has a THM, while a still
has *nothing but the original* — subtypes 1, 2, 17 and 18 all close the connection for a photo index.
The reference app sidesteps this by pulling every thumbnail over the datalink instead
([§28](#28-get-media-list-drone), subtype `0x20`), and never requests subtype 1 or 2 over HTTP at all.

A cheaper route for a still, since `Range` is supported: fetch the **first 64 kB of the original** and
take the thumbnail out of its EXIF `APP1` segment (a u16 length caps `APP1` at 64 kB, so one request
always suffices). Measured on a Mavic 3, the embedded JPEG starts 1502 bytes in. Unlike the datalink
route this parallelises and leases no transfer slot.

Extensions are probed in order (`.JPG .jpg .MP4 .mp4 .MOV .mov .DNG .dng` for ORG; `.LRF/.lrf`,
`.THM/.thm`, `.SCR/.scr` for the rest), so the URL carries no extension.

The LRF proxy is ~7× smaller than the original (38.8 MB vs 273 MB on a 30 s clip) and decodes at
1280×720 — use it for preview and scrubbing, ORG only for download.

```python
def pack_file_index(storage, dir_index, file_no):
    return (storage << 30) | (dir_index << 16) | file_no

ORG, THM, SCR, AIS, LRF = 0, 1, 2, 17, 18

def url(index, subtype=ORG, seg=0):
    return "/v1?file_index=%d&file_subtype=%d&file_seg_subindex=%d" % (index, subtype, seg)

url(pack_file_index(0, 100, 554), LRF)   # /v1?file_index=6554154&file_subtype=18&file_seg_subindex=0
```

### 30. Drone status pushes

Pushes are wrapped inside `0x51/0x01` tunnel frames — a top-level frame scan steps over them; scan
byte-at-a-time with both CRCs verified. Field layouts are **identical to the camera frames**
([§19](#19-sd--storage-both-stores-in-one-frame), [§20](#20-battery--power-also-the-only-place-the-dock-reports-in)):

| Cmd Set / ID | field | offset |
|---|---|---|
| `0x0d`/`0x02` | battery percent | `u8 @ 20` |
| `0x0d`/`0x02` | pack voltage, mV | `u16-LE @ 1` |
| `0x0d`/`0x02` | current, mA (signed, −ve = discharging) | `i32-LE @ 5` |
| `0x0d`/`0x03` | per-cell voltages, mV | `u16-LE × 4 @ 2` |
| `0x02`/`0xdc` | SD total / free, MiB | `u32-LE @ 6` / `@ 10` |
| `0x02`/`0xdc` | internal total / free, MiB | `u32-LE @ 24` / `@ 28` |
| `0x02`/`0x80` | active store total / free, MiB | `u32-LE @ 5` / `@ 9` |

### 31. Drone uplink stream

The reference app sends `0x02/0x82` (42 B), `0x02/0xdc` (40 B) and `0x04/0x1c` (`38`) at ~860/s for the
whole session — 95% of its uplink — addressed to `0x1c01`/`0x1c04` with sender `0x01`. Not required to
open the session or to browse media.
