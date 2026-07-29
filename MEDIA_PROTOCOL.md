# Osmosis — Media & camera DUML commands

Every DUML command we use / know for browsing, fetching, and controlling media on DJI Osmo cameras
(WiFi UDP datalink + BLE control). From our app (`duml/`, `net/DatalinkClient`) and the reference repos
[osmo-download](https://github.com/SemiConscious/osmo-download) and
[DJI-Wifi-Connect/pocket3](https://github.com/sniffingpickles/DJI-Wifi-Connect/tree/main/pocket3). Each **DUML example** is a full, valid
frame (correct CRC8+CRC16) — paste it into <https://b3yond.d3vl.com/duml/> and it decodes.

Transports: **BLE** = write GATT `fff5`, notify `fff4` (the `[6:8]` msg-id round-trips either way — we encode/decode it **little-endian** and the camera echoes the bytes back, so its true endianness is moot for request/response matching).

> ⚠️ **A bare-metal BLE client needs the GATT setup below before the camera will act on anything.** Get it wrong and the camera ATT-acks every write, silently ignores it, and answers nothing — which reads exactly like an unsupported command, so you will hunt the wrong layer for days. Required, all hardware-verified on a Nano:
> - **Subscribe the CCCDs of BOTH `fff4` and `fff5`** (0xFFF5's is easy to miss if service discovery is range-limited to the write characteristic).
> - **Write `01 00` to the `fff4` characteristic VALUE** (not its CCCD), with response, after the CCCDs and before any `fff5` traffic, then let it settle ~200 ms.
> - **`fff5` is WRITE_NO_RSP only** (`props=0x36`) — a Write Request on it is a spec violation.
> - **Every app→camera frame needs `cmd_type` `0x40`**, never `0x00`.
> - **MTU 500.** Negotiating 517 made the camera stop answering *every* request in our build (its NimBLE buffers are sized for 500) — raise the buffer config too or leave it alone.
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

One `0x00/0x26` returns only the **newest ~45 files** (the `2d` = 45 count at payload byte 14). To reach older files the request carries a **cursor = a 4-byte little-endian file *handle* at payload bytes 10-13** — the same handle the record exposes for delete (§2, `u32-LE @ head`). Two things make it page:

1. **Enter playback mode first.** The list only paginates while the camera is in playback — `0x02/0x0c` payload `01 01 00 01` to enter (`01 01 00 00` to leave). A plain query without it just re-returns the newest 45.
2. **Per page send three frames** — `query(cursor=1)` → `trigger` → `query(cursor=pageCursor)`. The **second query's cursor selects the page**; the first (`cursor = 0x00000001`) and the trigger (`4a040e10`) prime the stream.

| page | cursor @ bytes 10-13 (u32-LE) | returns |
|------|-------------------------------|---------|
| newest | `0x00000001` — `01 00 00 00` (or the `0x40000001` sentinel) | newest ~45 |
| next older | the **oldest video handle** of the previous page (`0x40xxxxxx`, e.g. `80 2b 10 40` = `0x40102b80`) | next ~45, older |
| … | repeat with each page's oldest video handle | until a page adds nothing new |

- Only handles **`≥ 0x40000000`** (video records) advance the cursor — a stray low-namespace handle (a `0x0010xxxx` photo) is skipped so it can't jerk the cursor to the bottom and stall paging.
- Consecutive pages overlap by exactly the one boundary file, so **dedup by media path** (≈ 44 new per page).
- The camera drops the datalink after ~2 pages (and any keepalive drifts the app's UDP sequence out of the accept window), so **re-open a fresh registered session — and re-enter playback — per page**.

DUML examples (cursors from a real 195-file Nano library):
- enter playback (`0x02/0x0c 01 01 00 01`): <https://b3yond.d3vl.com/duml/#55110492020100a040020c01010001b63b>
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

A burst or interval shoot is stored as a numbered group — `DJI_…_0286_D_001.JPG`, `_002`, `_003`, … The **normal list returns only the group lead (`_001`)**; standalone photos have no `_NNN` suffix, so the filename alone tells you it's a group (→ badge, no probing). The other frames are **not** in the base manifest and there is **no burst boolean on the wire** — the decompiled SDK's `isManualGroupFile` / `subMediaFile` are its *internal* object model, assembled after this query, not fields in the record (a burst-lead record is byte-identical to a normal photo except the `_001` in its name).

To pull the whole group, **re-issue `0x00/0x26` seeded with the group's handle** — a targeted variant of the paging query:

- **handle = `0x40100000 + seq × 0x40`** (the *same* formula as the favorite handle, §3), placed at payload **bytes 10–13** (LE) where the paging cursor goes. For `0286`: `0x40104780`; for `0292`: `0x40104900`.
- **byte 14** = a frame limit (the app sends the exact count; a generous value works — the camera returns only the group), **byte 16 = `0x10`** ("group mode", vs `0x0d` for the full list), byte 39 = `0x01`.
- The camera replies with a small (~1.8 KB) manifest of **just that group** — every frame with its real path, thumb (`.thm`/`.scr`) and size. Decode it like any manifest; filter by the shared name base if it ever spills into older files.

Full request (0286, 6 frames): `4a002a10 19 0000000000 80471040 06 00 10 0100 ffffffffffffffff 0001…01`. Media itself downloads over HTTP `:80` `/v2?storage=&path=` as usual.

**Parsed — DJI CompositePack (TLV).** The reassembled manifest opens with a `u32-LE` file count (present on the Nano/Xtra/Pocket 3; **`0` on the Action 5/6** — count the records instead), then one record per file. Every field is **length-delimited**, so you read *tag → length → value* — there is no need to recognise what a filename looks like, and no regex. The self-identifying anchor is the **media-path** field; the filename is read only for its extension:

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
| delete handle | `u32-LE @ head` (`head = marker − 8`) | **video only**; feeds `0x00/0x28` (§2); `0` = photo, not deletable |
| **media byte size** | **`u32-LE`, 14 B before the `19 06` pair** (= video `marker − 12`) | real file size, **video *and* photo** — byte-exact vs the SD card (video) / HTTP (photo) |
| proxy (`.LRF`) size | `u32-LE @ marker + 30` | the low-res sidecar's size; a per-camera *constant* on the Action family, so don't mistake it for the media size (we did, once) |
| fps | rational `<u32 num><u32 den>` near the record | `a861 0000 e803 0000` = 25000/1000 = **25 fps** — what the parser reads |
| frameRate | `u8 @ marker − 2` | the `VideoFrameRate` enum for the same value (table below) |
| resolution *(video)* | `u8 @ marker − 1` | video-format index → pixel size (table below) |
| **duration *(video)*** | **`u16-LE @ marker + 26`** | whole **seconds**; = `floor(moov ms / 1000)`, 16/16 exact across a varied-length clip set. Enough for the `mm:ss` label — it retired the `moov` read |
| **width, height *(photo)*** | **`u32-LE`, `+58` / `+62` from the `19 06` pair** | photo pixel dimensions (videos have none here — they use the resolution enum) |
| ⭐ starTag | `u8 @ [ff\|fe] 19 06 + 9` | favourite flag; the one field also present on photo records |

- **Two stores = two lists.** With a card in, the reassembled manifest is **two per-storage lists back to back** — **SD first, then internal** — each opening with its own `[u32-LE count][u32-LE size][u32-LE ts]…` header. The leading count covers only the *first* list; the rest belong to the second. Proven by dumping the same camera with and without a card: the no-card manifest is **byte-identical to the mixed manifest's second list**. (Record handles corroborate it — SD `0x0004xxxx` vs internal `0x4004xxxx` on the Action family — but the split is taken from the count, which every model writes. The camera's `storage=` HTTP index is *not* a fixed SD/internal mapping: an Xtra served its SD at `0` and internal at `1`, so resolve it by probing.)
- **Naming is irrelevant to the parse.** Because the path/name are read by length, the camera's *Naming Management* custom **Folder** and **File** prefixes decode exactly like stock — `DCIM/DJI_001/DJI_…_D.MP4` (stock), `DCIM/DJI_001/DJI_…_D_OP3.MP4` (Pocket 3), `DCIM/DJI_001_OA5/DJI_…_D_DOA5.MP4` (Action 5, custom folder + file suffix), `…_D_A01.MP4` (a user-typed `A01`) — all the same.
- **Everything now reads from DUML (solved 2026-07-29).** Size (`marker − 12`, exact 85/85 vs the SD card), fps (rational), frameRate (`marker − 2`), resolution (`marker − 1`, table below), duration (`u16-LE @ marker + 26`, whole seconds), photo size (same offset as video, off the `[ff\|fe] 19 06` marker) and **photo pixel W×H** (`+58` / `+62` from the `19 06` pair) are all mapped — pinned by shooting a controlled set of clips (varied length / fps / resolution / aspect) and photos, logging each file's ground truth (moov duration/resolution, HTTP size, JPEG bounds) beside the raw record, and correlating. **The MP4 `moov` parse and the size HTTP HEAD are retired** — the app derives 100% of the grid/preview metadata from the manifest, with HEAD kept only as a guard for a record we somehow couldn't size. (The nearby `marker + 30` — our old "`head+38`" — is the *proxy* `.LRF` size, which is why it once looked like the media size on the Nano and a constant elsewhere.)

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

#### What a record *means* — DJI's `MediaFile` schema (from RE of the DJI app)

The `0x00/0x27` tagged record above is the **only** media-list wire format — a Mimo↔Nano WiFi capture shows Mimo using this same DUML on `udp/9004` (plus `tcp/80` for the HTTP media/thumb transfer), no second channel. DJI's app parses each tagged record into a `MediaFile` object (native `native_file_transfer_list`), so `MediaFile`'s fields are the authoritative dictionary of *what each record carries*. Reversed from the DJI camera SDK classes in the app dex (`xtra.sdk.keyvalue.value.media.MediaFile`):

| field | type | notes |
|-------|------|-------|
| `fileName` | String | e.g. `DJI_…_D.MP4` — our `0d` field |
| `fileType` | enum `MediaFileType` | photo/video/… → the extension category |
| `fileSize` | **Long** | the real byte size — **mapped**: `u32-LE @ marker − 12`, verified against the SD card |
| `duration` | **Long** | video length (ms) |
| `frameRate` | enum `VideoFrameRate` | **mapped**: `u8 @ marker − 2`; our fps rational is the same value |
| `resolution` | enum `VideoResolution` | **mapped**: `u8 @ marker − 1` (table below) |
| `date` | `DateTime` | capture time |
| `starTag` | enum | favourite / marked flag — **mapped**: `u8 @ [ff\|fe] 19 06 + 9` |
| `orientation`, `cameraOrientation` | enum | rotation |
| `photoType`/`videoType`/`panoType`, `videoEncodeType`, `videoSpeedRatio`, `timeLapseInterval` | enum/int | mode metadata |
| `dirIndex`, `fileIndex`, `subIndex`, `segSubIndex`, `fileGroupIndex` | int | DCF indices |
| `proxyInfo`, `hasProxy`, `EXIFInfo` (`physicalPathInfo`), `dcfInfo` | nested | proxy/exif/DCF; the `DCIM/…`,`MISC/…` strings live in these nested `physicalPath`s |

So **size, duration, resolution, fps and photo dimensions are all present in every record**, and **all now mapped** (see the field table above). Each was pinned by shooting a *varied* controlled set (clip length / fps / resolution / aspect, plus photos) and diffing the manifest against ground truth (the SD card, moov, HTTP size, JPEG bounds). `fileSize` = `marker − 12` (85/85 byte-exact vs the card); `frameRate` = `marker − 2`; `resolution` = `marker − 1`; `duration` = `u16-LE @ marker + 26` (whole seconds — *not* one of the tagged `[key][type][BE value]` attributes we skip, but a fixed slot in the header); photo `width`/`height` = `+58`/`+62` from the `19 06` pair. **The MP4 `moov` parse is retired.**

##### Enum value tables (mined from the DJI app dex — for decoding the record's int fields)

The record's int fields are small enum codes; these are the code→meaning tables (RE'd from the app's SDK enum classes), so a pinned field reads straight through. The ones **confirmed** by the mapping above are marked ✅.

✅ **star** — the byte at `[ff|fe] 19 06` + 9 is DJI's `MediaFileStarTag`: `0 = NONE`, `1 = TAGGED` (starred). **Nano only:** verified on the Nano (videos + photos); the Xtra/Action-5 manifest carries **no** star flag at this (or any findable) offset — its firmware keeps favourites elsewhere — so the reader returns `false` for it (no false stars, just none shown).

✅ **frameRate** (`marker−2`) — `VideoFrameRate`. ✅ = verified on hardware.

| code | fps |
|------|-----|
| `1` | 24 |
| `2` | 25 ✅ |
| `3` | 30 ✅ |
| `4` | 48 |
| `5` | 50 ✅ |
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

**Resolution** (`marker−1`) — verified codes:

| code | resolution |
|------|-----------|
| `10` | 1920×1080 (1080p 16:9) |
| `12` | 1920×1440 (1080p 4:3) |
| `16` | 3840×2160 (4K 16:9) |
| `45` | 2688×1512 (2.7K 16:9) |
| `95` | 2688×2016 (2.7K 4:3) |
| `103` | 3840×2880 (4K 4:3) |

> **Aside — DJI's `ByteStream` (a *different*, sibling encoding).** The same SDK also serializes `MediaFile` flat via a `ByteStream` codec (`toBytes`/`fromBytes`): **positional, no tags, little-endian** — `bool`=1 B, `int`/enum=4 B, `long`=8 B, `string`=`[u32-LE len][utf8]`, nested = recursive, list = `[count][items]`. This is *not* the camera wire (our records are tagged with 1-byte string lengths); it's the SDK's internal/IPC form. Noted because a capture that ever carries it would decode trivially with this spec.

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
- **Session** — accepted only on a **freshly-registered** datalink: the browse keep-alive advances our UDP seq past the camera's write window (reads still answer, writes are silently dropped), so tear keep-alive down and re-run the datalink-session open (handshake → register → subscribe) before sending. ~9 s. Verified on Nano + Xtra (`status 0000`, file removed).
- DUML example (delete handle `0x40104480`): <https://b3yond.d3vl.com/duml/#551f044e020100a0400028018044104001000000000100000001010000a0d1>

### 3. Favorite / star media
- Cmd Set / ID: `0x02` / `0xBF`  ·  App → Camera(`0x01`), datalink
- Payload: `01 01 [handle:u32-LE] [counter:u32-LE] 00 [on:u8] 00 00 00`  — favorite handle `h`: `01 01 <h> 01000000 00 01 000000`
- `on` = `01` favorite, `00` un-favorite. `handle` is the **favorite index** `0x40100000 + seq*0x40` — for videos it equals the manifest delete handle (#2); photos have no manifest handle, so derive it from the file's sequence number. `counter` is a per-action running index (Mimo sends 1, 2, …).
- Response: `0x02/0xBF` → `00` = OK
- **Session** — like delete (#2) this is a *write* the browse keep-alive silently drops, and the capture shows Mimo only ever favorites with **playback mode active** (`0x02/0x0c` payload `01 01 00 01`, the same mode paging uses). Run it in a fresh datalink session: register → enter playback → send → read the `00` ack. Verified on a Nano — survives a reconnect.
- DUML example (favorite handle `0x40104040`, seq 0257): <https://b3yond.d3vl.com/duml/#551c041b0201befd4002bf0101404010400100000000010000008c88>

---

## Datalink session (sent before the list, over UDP)

### 4. Handshake  *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo. Then drain heartbeats, learn `camera_channel` (heartbeat routing `[8:10]`); app UDP seq starts at `camera_channel + 8`.

### 5. Device info
- Cmd Set / ID: `0x00` / `0x81`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Payload: `00 "APP" 00×37 02 00×8 02 08 00×10` (62 B — 1+3+37+1+8+2+10; `DatalinkClient.appDeviceInfo` / `OsmoCommands.APP_DEVICE_INFO`)
- DUML example: <https://b3yond.d3vl.com/duml/#554b0402024800a08000810041505000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000020800000000000000000000ad80>

### 6. Register
- Cmd Set / ID: `0x00` / `0x88`  ·  App → DM368(`0x08`, id 1)
- Payload: `170008237b41505000000000000002`
- DUML example: <https://b3yond.d3vl.com/duml/#551c041b022800a0400088170008237b41505000000000000002d9e6>

### 7. Init
- Cmd Set / ID: `0x03` / `0xDA`  ·  App → Gimbal(`0x03`)
- Payload: `05ffffffff`
- DUML example: <https://b3yond.d3vl.com/duml/#551204c7020300a04003da05ffffffff4490>

### 8. Subscribe param ✅ *(hardware-verified over BLE — this is the settings surface)*
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1), `cmd_type 0x40`
- **Works over BLE exactly as on the datalink** — ✅ confirmed on a Nano: 11 subscriptions produced **1243 pushes**. Each subscribe is ACKed `plen=10`, then the camera sends that parameter's value and every later change, unprompted.
- **Subscribe payload — one frame PER PARAMETER, verb `0x02`:**
```
02 02 00 00 | sub_id:u32-LE | 00 00 00 | (name_len+6):u16-LE | name_len:u16-LE | <name ascii> | 00 00 00 00
```
  Corrections to the earlier spec here: the name-length field is **u16-LE, not u8**, and the name is **not padded to 20** — frames are variable length (`camcap_base` = 30 B, `camcap_photo_time_limited_burst_param` = 56 B). `sub_id` increments per subscription.
- ⚠ **There is no working group subscribe.** A single `01 00 06 00 "camera"` (verb `0x01`) is **ACKed with `plen=0` and never sends an item** — indistinguishable from an unsupported channel, and it cost us days. Subscribe each name individually.
- **Push payload — self-describing, so no `sub_id` bookkeeping is needed:**
```
02 06 00 00 | idx:u32-LE | 00 00 00 | total_len:u16-LE | name_len:u16-LE | <name> | 00 x6 | value_len:u16-LE | <value>
```
- 🔑 **Naming rule: `camcap_*` = what the body SUPPORTS (a capability table); `cam_*` = the CURRENT value.** Subscribing to `camcap_fov`/`camcap_eis` gives you the supported modes and never the active setting. (Learned the hard way.)

**Decoded values** (Nano, ✅ hardware-verified):

| name | contents |
|------|----------|
| `cam_video_param_v2` | **`[resolution:u8][fps_idx:u8]…`** — the live video setting. `67 02` = res 103 (4K 4:3) @ fps idx 2 (25 fps). Codes match §1's `VideoResolution`/`VideoFrameRate` tables. |
| `camcap_video_format` | **capability list**: `01 \| len:u16-LE \| count:u8 \| count × [res:u8][fps_idx:u8][flags:u8]`. Self-validating (`3×35+1 = 106` = declared len). Nano returns 35 pairs — 4K 16:9, 2.7K 16:9, 2.7K 4:3, 1080p and res `0x0c` at 24–60; **4K 4:3 caps at 50**. `0x0c` (12) is not in the known resolution enum. |
| `cam_storage` 40 B · `cam_status` 9 B · `cam_record_time` 6 B · `cam_image_effect` 16 B · `cam_lens_state` 66 B · `cam_custom_mode_params` 161 B | present, not yet decoded |

- **All 53 names Mimo subscribes** (the complete settings surface): `camcap_base camcap_video_format camcap_fov camcap_iso camcap_photo_storage_format camcap_color_mode camcap_wb camcap_photo_size camcap_video_codec camcap_shutter camcap_photo_timer_interval camcap_exposure_mode camcap_zoom camcap_antiflicker camcap_sharpness camcap_denoise camcap_aperture camcap_shutter_max camcap_eis camcap_iso_auto_max camcap_loop_video_duration camcap_hyperlapse_ratio camcap_slowmotion_ratio camcap_timelapse_duration camcap_countdown camcap_photo_time_limited_burst_param camcap_capture_aspect_type camcap_style_filter_mode cam_storage cam_status cam_record_time cam_expo_param shutter_param cam_photo_param_new cam_lapse_param cam_video_param_v2 cam_image_effect v_quality_enhance_status cam_fov cam_lens_state cam_audio_status_v2 audio_timecode_status temp_curve camcap_common cam_imu_calib_info timecode_info cam_custom_mode_params cam_super_slowmotion_status media_file_sync upgrade_status cam_capture_aspect_type gui_autorecord_param cam_style_filter_status`
- DUML example (`cam_status`, original capture): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

### 9. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Response: NUL-separated ASCII `sdk\0name\0firmware` — scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

Cmd Set `0x02`, App → Camera (`0x01`). **Every app→camera frame must use `cmd_type` `0x40`** (request; `0xC0` = response) — a `0x00` frame is silently dropped before the dispatcher, so the command just looks dead. Record control is **hardware-tested on an Osmo Nano** via the [DJI-Remote](https://github.com/KonradIT/DJI-Remote) ESP32 firmware (2026-07-28). (The upstream repos' `0x02/0x20`/`0x21` record commands **don't exist** on Osmo firmware — they answer `e0` from every receiver; `0x02/0x02` is the record control.)

> ⚠️ **Everything in this section is Nano-verified only.** On an **Xtra Edge Pro** (Action-family rebrand), on the same firmware and the same session, commands to receiver `0x01` get **no reply at all** — while that same camera answers `0x07/0x45` pairing and the `0x53/0x10` wake normally and streams `0x02/0x80` status. So the link is healthy and the *camera command set differs between the two families*. Don't assume these opcodes port across bodies.

Once the link is up the camera answers *every* request, so the **reply byte is an oracle** — send an unknown cmdId with an empty payload and read the reply to map the command space:

| reply | meaning |
|---|---|
| `00` | success |
| `d9` | supported, **wrong state** (e.g. already recording) |
| `df` | supported, **wrong parameter** |
| `e3` | supported, **bad/missing parameter** |
| `e0` | **not supported** |
| *(no reply)* | that receiver does not exist |

### 10. Take photo
- Cmd Set / ID: `0x02` / `0x01`  ·  `cmd_type 0x40`  ·  argument **not yet found**
- The cmdId exists (never answers `e0`), but empty → `e3`, `[00]` → `df`, `[01]` → `d9` (idle *and* recording) — so it wants an argument we haven't cracked, and it is **not** the record toggle.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002017677>

### 11. Start recording
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[01]`
- Reply `00`; recording starts ~860 ms later (the `0x02/0x80` recording bit sets — §18).
- DUML example: <https://b3yond.d3vl.com/duml/#550e046602010204400202014e61>

### 12. Stop recording
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[00]`
- Reply `00`; the recording bit clears ~2.4 s later. **Not a toggle** — re-sending `[01]` while recording answers `df`, so drive start/stop off the decoded recording bit (§18), never by toggling blind.
- DUML example: <https://b3yond.d3vl.com/duml/#550e04660201020440020200c770>

### 12a. Shoot photo — `0x02/0x01` ✅
- Cmd Set / ID: `0x02` / `0x01`  ·  `cmd_type 0x40`  ·  receiver `0x01` (datalink)  ·  payload `[01]`
- Reply `0x02/0x01` (ack, `cmd_type 0xc0`) with payload `00` = success.
- **Fire-and-forget, one press = one capture in the camera's *current* photo mode.** Ground-truthed from a Mimo↔Nano pcap: two presses (`[01]` each, ~40 ms round-trip) produced the two new files that followed — one a single JPEG, one a 6-frame burst — so `[01]` is a generic shutter *trigger*, **not** the photo type. The mode (single / burst / interval / HDR / …) is set separately; the camera completes a burst/interval on its own (no stop press, unlike record §11/§12).
- Symmetric with record: photo = `0x02/0x01 [01]` (shoot); record = `0x02/0x02 [01]`/`[00]` (start/stop).

### 13. Set mode — ⚠️ *this is the **work** mode, not the shooting mode (see §13a)*
- Cmd Set / ID: `0x02` / `0x02`  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`
- Nominally `0` Photo · `1` Video · `2` Playback · `3` SlowMo · `4` Timelapse · `5` Panorama — but `0x02/0x02` **is** the record control above, so on the Nano `0`/`1` **stop/start a recording** rather than switch a mode. ⚠ A "Video" button mapped to `[01]` starts a recording behind the user's back — exclude `0`/`1` from any mode switcher.
- Valid range is `0`–`3` (`[04]` answers `df`), i.e. DJI's four-value *work* mode — capture / record / playback / download. `[03]` is accepted but changes nothing visible. **To change the shooting mode use `0x02/0xE1`.**

### 13a. Set **shooting mode** — `0x02/0xE1` ✅ *(Video / Photo / SlowMo / …)*
- Cmd Set / ID: `0x02` / `0xE1`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`  ·  reply `00`
- **Absent from the upstream repos and from this doc until now.** Recovered by CRC-scanning `capture_full.pcap` and tallying *every* app→camera command rather than grepping for expected opcodes — Mimo sends 8 of these while the user cycles modes. An empty payload answers `e3` (bad parameter), which is why probing the opcode alone looked like a dead end.

| value | mode | DUML example |
|-------|------|--------------|
| `0x00` | SlowMo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10036b3> |
| `0x01` | Video | <https://b3yond.d3vl.com/duml/#550e0466020102044002e101bfa2> |
| `0x02` | TimeLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1022490> |
| `0x05` | Photo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1059be4> |
| `0x0a` | HyperLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10a6c1c> |
| `0x28` | SuperNight | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1287c1e> |

- **The enum is sparse and unordered — table it, never compute it.** The camera's on-screen carousel order is Video → Photo → TimeLapse → HyperLapse → SuperNight → SlowMo, which is *not* the numeric order.
- ✅ **Readback:** the camera echoes the current mode in its `0x02/0x80` push at **byte `@57`**, in this same encoding — verified across ~20 writes with zero mismatches, and every value above was then confirmed by selecting that mode by hand and reading the byte. So mode is both settable and observable, and a remote stays in sync when the user changes it on the camera.
- 🚫 **Do NOT sweep the value space.** Walking `0x00`–`0xFF` to discover the rest of the enum **froze a Nano solid** (power-cycle required). Unknown values are *not* harmlessly rejected. Read the mode out of `@57` instead of probing for it.
- Why Photo never appears in the capture: the camera **booted into it**, so Mimo never needed to write `0x05`.
- ⚠️ **`0x02/0xE1` is probably a general camera-*action* command, not only "set mode".** `lib-osmo-ble/PROTOCOL.md` documents the same cmdId with payload `[0x1A]` as *PrepareToLiveStream*. That reconciles with the sparse, unordered value set above: some values select a shooting mode, others trigger actions. So treat unlisted values as unknown actions — and **do not sweep them** (see the freeze warning).

### 14. Camera parameters — `0x02/0x8E` GET **and SET** ✅ *(the writable control surface over BLE)*

**This is how you change camera settings.** `0x02/0x8E` is a keyed parameter store, not a heartbeat: the `00 01 14 00` payload Mimo sends ~15 Hz while browsing is simply *GET pid `0x0014`*. (What keeps the camera *awake* is the BLE keepalive `0x00/0x2b 01 01`, §21.) Both directions are **hardware-confirmed over BLE** on a Nano — App → Camera(`0x01`), `cmd_type 0x40`:

```
GET = 00 01 <pid:u16-LE>                    -> 00 00 01 <pid:u16-LE> <len:u8> <value…>
SET = 01 01 <pid:u16-LE> <len:u8> <value…>  -> 00
```

A GET for a pid that isn't valid in the current state answers a **single error byte** instead of a value (`e3` most often, then `df`, `d9`) — so a sweep doubles as a map of which pids exist. Note this contrasts with §8's `0x00/0x99`: over BLE the group-subscribe there is ACKed with `plen=0` and **zero items ever follow**, so on real hardware `0x02/0x8E` — not `0x00/0x99` — is the control surface that actually works.

**Known pids**

| pid | field | values | status |
|-----|-------|--------|--------|
| `0x0009` | **field of view** | `05` = Natural-Wide · `01` = Wide | ✅ confirmed by writing it — the Nano's FOV switches on screen |
| `0x000f` | **ISO limit** | `04` = 100-800 · `05` = 100-1600 | ✅ confirmed by writing it — the ISO range switches on screen |
| `0x0014` | — | 3 B, reads `00 00 00` | what Mimo polls; meaning unknown |
| `0x0039` | capability table | 15 entries of `<idx:u16-LE> 01 <value>` | looks like a settings/limits table |

⚠️ **The shooting mode (Video/Photo/SlowMo/Timelapse) is _not_ a parameter** — at least not in `0x0000`–`0x007f`. Two independent ground-truth mode changes moved **only** the settings that hang off a mode (FOV, ISO) and never a pid that could be the mode itself. Combined with `0x02/0x02` being the *work* mode (§13, range 0–3), the shooting mode is still unlocated: the `0x0039` table, pids above `0x7f`, and `0x00/0x99` are the remaining places to look.

Mimo polls `06 08 09 0f 14 15 18 1f 20 28 29 30 39` at ~1 Hz — a good shortlist to sweep first.

**How to find a pid — sweep, change it by hand, diff (A→B→A).** GET `0x0000`–`0x007f` (space the frames ~60 ms; a sweep takes ~15 s), then: sweep → change the setting **on the camera itself** → sweep → change it **back** → sweep. Only trust a pid that **moves and moves back**. Both pids above were found this way, and the round trip is what makes it reliable — in the ISO run exactly one pid out of 21 showed A-B-A and it was the right one. Three traps, all hit for real:

- **An error reply is not a change.** A pid that returned a value in one sweep and an error byte in the next simply wasn't answerable then. Counting those as changes produced ~7 false candidates out of 9 on the first attempt. Compare only value-to-value.
- **Settings are stored per mode**, so changing the *mode* swaps in that mode's saved values and drags its settings along. Both `0x0009` and `0x000f` looked like textbook mode pids (`05`→`01`→`05` and `05`→`04`→`05`) purely as passengers; writing them showed they were FOV and ISO. **A correlation is not a control — confirm by writing it.**
- **Only one sweep at a time.** Two overlapping sweeps interleave their replies and the camera starts rejecting the extra traffic (89 → 99 `e3` errors, a third of the values lost), which quietly corrupts the diff rather than failing loudly.

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
- **Verified on the Nano** (decoded from a DJI-Mimo capture): the camera clock snaps to the sent value
  and recorded file timestamps follow. Osmosis sends it right after registration on every connect.
- DUML example (set `Europe/Madrid`): <https://b3yond.d3vl.com/duml/#55270415022828f740006a0100ce2a666a0000000078000d4575726f70652f4d61647269640c0e>

---

## Status pushes (camera → app, decoded not sent)

### 18. Camera status
- Cmd Set / ID: `0x02` / `0x80`  (~10 Hz push, 60 B)
- **Recording** is *not* `byte1 & 0x01` (a Pocket 3-repo claim that can never fire — `byte1` is a static `0x02`, the cmdSet echo, in both states; confirmed across 2279 frames in our capture). It is **`payload[0] & 0x80`** — ✅ now ground-truthed twice: by recording with the camera's own button and diffing the payload, and by driving §13 from an ESP32 and watching the bit follow.
- ✅ **`payload[0]` is a bitfield, not an enum.** Observed `01` idle, `41` transitional, **`81` recording**, `c1`. Bit 6 also toggles around mode changes, so match on **bit 7 alone** — an equality test against `0x81` will miss states.
- Offsets, all ✅ ground-truthed by diffing an idle capture against a recording one:

| offset | type | field | idle → recording |
|--------|------|-------|------------------|
| `@0` | `u8` bitfield | **bit7 = recording** | `01` → `81` |
| `@5` | `u32-LE` | storage total, MiB | unchanged |
| `@9` | `u32-LE` | storage free, MiB | falls while recording |
| `@17` | `u16-LE` | **remaining recordable seconds** | counts down (reads `0` in Photo mode) |
| `@29` | `u16-LE` | **elapsed record time, seconds** | `0` → counts up |
| `@57` | `u8` | **current shooting mode** (§13a encoding) | changes with the mode |
| `@4` | `u8` | `1` = a video-ish mode, `0` = Photo | — |
| `@13` | `u16-LE` | photos remaining | `0` outside Photo mode |

**Worked example — the same camera in three modes**, selected by hand and read off the push (this is how the mode byte was pinned):

| offset | Video | SlowMo | Photo | field |
|--------|-------|--------|-------|-------|
| `@57` | `01` | `00` | `05` | **shooting mode** — matches §13a exactly |
| `@4` | `01` | `01` | `00` | video-vs-photo flag |
| `@17` `u16-LE` | 1050 | 953 | **0** | remaining recordable seconds (meaningless in Photo) |
| `@13` `u16-LE` | 0 | 0 | **5048** | photos remaining (meaningless outside Photo) |

Note `@17` and `@13` are **mutually exclusive** — each reads 0 in the modes where it doesn't apply, so don't render either without checking `@4` or `@57` first, or a Photo-mode UI will show "0 seconds left".

> **`@57` uses the *same* encoding as the `0x02/0xE1` write values — it is not a separate enum.** Worth stating because the opposite is an easy conclusion to reach: `@57` reads `01` in Video while a naive reading of a button-cycling test suggests Video is written as `00`. The tie-breaker is a log of writes against subsequent `@57` values — across ~20 writes the reported byte equalled the written byte every time, and the three modes in the table above then confirmed it against the camera's own screen. If the two ever appear to disagree, the mode→value mapping is what's wrong, not the encoding.

- `@17`/`@29` are enough to drive a live recording timer and a "space left" readout without polling anything.
- Quirks: reports the **active store only** (internal vs SD). Nano + Xtra.

### 19. SD / storage  *(both stores in one frame)*
- Cmd Set / ID: `0x02` / `0xDC`  ·  App ← Camera, datalink
- Carries **both** stores as two `[total][free]` `u32-LE` MiB blocks — **card @6/@10**, **built-in
  @24/@28**:

| offset | type | field |
|--------|------|-------|
| `@6`  | `u32-LE` | SD **total** MiB (`0` = no card) |
| `@10` | `u32-LE` | SD **free** MiB |
| `@24` | `u32-LE` | internal **total** MiB |
| `@28` | `u32-LE` | internal **free** MiB |

- **Card present = SD total > 0**, not a flag byte. Byte 0 is *not* an "SD inserted" bit — it reads
  `0x11` on a camera with **no** card and `0x00` on two cameras **with** one (i.e. backwards).
- Ground-truthed three ways: an Action 6's `@6/@10` (121785/109748 MiB) matched its own on-screen
  118.9/107.2 GB exactly; an Action 5 Pro and its Xtra rebadge report an identical 48980 MiB built-in;
  a card-less Xtra reports `@6 = 0`.

### 20. Battery / power *(also the only place the dock reports in)*
- Cmd Set / ID: `0x0D` / `0x02`  (34 B, ~1 Hz push)  ·  sender Battery(`0x05`), id `0`

| offset | type | field | confidence |
|--------|------|-------|------------|
| `@1`  | `u16-LE` | pack voltage, mV (≈3300–4450 on a Nano) | confirmed |
| `@5`  | `i32-LE` | current, mA — **signed**: `+` charging, `−` discharging | confirmed |
| `@17` | `u16-LE` | temperature? (reads 45.0 / 47.0 °C) | plausible, unconfirmed |
| `@20` | `u8`  | charge percent, 0–100 | confirmed |
| `@27` | `u8`  | **dock attached** (`0x40` docked, `0` not) | confirmed |
| `@32` | `u8`  | **taking charge** (`1` / `0`) | confirmed |

- **The dock is not a separate DUML device.** Logging every sender address across docked and undocked
  sessions, on both BLE and the datalink, never turned up a second battery (`type 0x05, id != 0`) or any
  new address — so `@27` / `@32` here are the *only* dock signal on the wire.
- `@27` and `@32` are genuinely different: one transition read `@27=0x40` with `@32=0` and only −175 mA —
  physically docked but not yet drawing charge. Treat them as separate flags, not a single "charging" bit.
- Mapped by docking/undocking a Nano mid-session and diffing the frame over six transitions.
- **Not reported anywhere:** the dock's *own* charge level, and the dock's SD-card capacity — `0x02/0x80`
  (#18) covers the **active** store only.

---

## Connection (BLE control — prerequisites to reach media)

### Waking a sleeping camera

A sleeping Osmo Nano **keeps advertising `ADV_IND`** under its own name, so there is no wake *broadcast*
to send — DJI documents a `WKP` manufacturer-data advertisement, but an HCI snoop of Mimo waking a Nano
shows Mimo never advertises at all. The wake is an ordinary **command sequence** over GATT `fff5`:

| # | write | receiver | note |
|---|-------|----------|------|
| 1 | `0x00/0x2b` `04 00` | `0xF0` | first thing Mimo writes, **before** pairing |
| 2 | `0x07/0x45` SetPairingPIN | `0x07` | see #24 |
| 3 | `0x00/0x2b` `01 01` | `0xF0` | then repeating ~1 Hz, forever, as the keepalive |
| 4 | `0x53/0x10` `00 00 00 00` | `0x1C` | camera answers `01 00 00 00` and **wakes** |

Space the writes so `fff5` (write-without-response) doesn't drop back-to-back frames — the floor is roughly the BLE connection interval. Mimo bursts consecutive writes **~8–40 ms** apart (measured from `0x52` write-command timing in our HCI snoops — p50 **9 ms** on the Nano, 71% of gaps under 50 ms); ~100–500 ms is a conservative margin, not a hard requirement.
Mimo does **not** send ConnectToWiFi (#25) anywhere in this flow.

### 21. Session wake / keepalive
- Cmd Set / ID: `0x00` / `0x2b`  ·  App(`0x02`) → **`0xF0`** (type `0x10`, id 7), BLE
- Payload: `04 00` = open the session (sent once, pre-pairing) · `01 01` = keepalive (repeat ~1 Hz)
- Quirks: the Nano drops an idle paired link after ~5–6 s, so the `01 01` ping must keep running for the
  whole session. Re-sending SetPairingPIN instead (what we used to do) is noisier and gets a sleeping
  camera to drop you.
- DUML example (`04 00`, verbatim from a Mimo capture): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b04009ab9>
- DUML example (`01 01` keepalive): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b0101abd6>

### 22. Wake camera
- Cmd Set / ID: `0x53` / `0x10`  ·  App(`0x02`) → **`0x1C`** (type `0x1C`, id 0), BLE
- Payload: `00 00 00 00`
- Response: `01 00 00 00` — the camera wakes ~2–3 s later and brings its AP up on its own
- Quirks: this is the command that actually correlates with the wake. Addressed to Camera(`0x01`) it
  answers `e0`. Send it **after** pairing; the same `rcv_type 28` shows up on the UDP datalink for
  `0x53/0x15`, so `0x53` is a session/system set rather than a camera one.
- DUML example (verbatim from a Mimo capture): <https://b3yond.d3vl.com/duml/#55110492021c1dcb40531000000000894a>

### 23. WiFi enable *(does **not** work)*
- Cmd Set / ID: `0x07` / `0x39`  ·  App → WiFi(`0x07`), BLE
- Quirks: Mimo sends this, but the camera rejects it (`e0`) **for Mimo too**, so it is not load-bearing
  for the wake and we don't send it. Listed only so it isn't re-derived from a capture as a lead.

### 24. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`; token `"osmo"`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval popup on camera; approval then arrives as a **`0x07/0x46` request** (flags `0x40`), which is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

### 25. ConnectToWiFi (AP bring-up — fallback only)
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's *own* creds
- Response: `0x07/0x47` `00 00` = ok; AP comes up ~15 s later
- Quirks: **Mimo never sends this**, and on a *sleeping* camera it correlated with the link being
  terminated (GATT `status=19`). The wake sequence above brings the AP up on its own, so keep this
  only as a fallback for models that never surface creds over BLE (#26/#27).
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 26. GetWifiSsid
- Cmd Set / ID: `0x07` / `0x07`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString ssid]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472>

### 27. GetWifiPassword
- Cmd Set / ID: `0x07` / `0x0e`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString passphrase]`
- Quirks: **give it a beat after GetWifiSsid** (`fff5` is write-without-response; Mimo actually spaces these only a few tens of ms — see §20 — so ~500 ms is just a safe margin). Verified on Xtra / Action 5 Pro; Nano rides the saved-password fallback.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 28. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>
