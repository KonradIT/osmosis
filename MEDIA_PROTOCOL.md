# Osmosis — Media & camera DUML commands

Every DUML command we use / know for browsing, fetching, and controlling media on DJI Osmo cameras
(WiFi UDP datalink + BLE control). From our app (`duml/`, `net/DatalinkClient`) and the reference repos
[osmo-download](https://github.com/SemiConscious/osmo-download) and
[DJI-Wifi-Connect/pocket3](https://github.com/sniffingpickles/DJI-Wifi-Connect/tree/main/pocket3). Each **DUML example** is a full, valid
frame (correct CRC8+CRC16) — paste it into <https://b3yond.d3vl.com/duml/> and it decodes.

Transports: **BLE** = write GATT `fff5`, notify `fff4` (the `[6:8]` msg-id round-trips either way — we encode/decode it **little-endian** and the camera echoes the bytes back, so its true endianness is moot for request/response matching).

> ⚠️ **The BLE link must be LE-encrypted (bonded) before the camera will talk to you.** Unencrypted, it silently ignores every write and drops the link in ~600 ms; once the transport is encrypted the camera answers (the first-ever `0xC0` response) and the session lives 20 s+. **Android and iOS escalate LE security transparently and reuse the bond**, so this step is invisible in app code *and* in app-side/HCI captures — a reused bond shows no fresh SMP pairing or encryption-change event (verified: our own btsnoop of a working session has zero of either), which is exactly why it's so easy to miss. A bare-metal client (ESP32 / NimBLE — and GoPro's BLE behaves the same) **must enable encryption/bonding explicitly** before sending any DUML. This is the *transport* bond: the DUML payload itself is plaintext and `0x07/0x45` pairing is a separate app-level token approval. The WiFi **UDP datalink** that follows is plaintext DUML.
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

**Parsed — DJI CompositePack (TLV).** The reassembled manifest opens with a `u32-LE` file count (present on the Nano/Xtra/Pocket 3; **`0` on the Action 5/6** — count the records instead), then one record per file. Every field is **length-delimited**, so you read *tag → length → value* — there is no need to recognise what a filename looks like, and no regex. The self-identifying anchor is the **media-path** field; the filename is read only for its extension:

```
0d <len:u8>              <ascii>        # filename "<base>.<ext>"  (read for the ext only)
1a <total:u8> 00 00 00 01 <ascii>       # media path, ascii = total-6 bytes, "DCIM/…" (NO ext)
1a <total:u8> 00 00 00 02 <ascii>       # thumb path,  "MISC/THM/…"
```

Handle + size hang off a **record marker `03 ff 19 06` at `head+8`**, present on **video records only** (photos have none → no handle, no size):

| field | where | notes |
|-------|-------|-------|
| media path | `1a … 00 00 00 01` value | `DCIM/<folder>/<base>`, no extension |
| thumb path | `1a … 00 00 00 02` value | `MISC/THM/<folder>/<base>` |
| extension  | `0d` filename field | the only field carrying `.MP4`/`.JPG`/… |
| delete handle | `u32-LE @ head` (`head = marker − 8`) | feeds `0x00/0x28` (§2); `0` = photo, not deletable |
| **media byte size** | **`u32-LE @ marker − 12`** | the real file size, **all cameras** — ground-truth-verified byte-exact against the SD card |
| proxy (`.LRF`) size | `u32-LE @ marker + 30` | the low-res sidecar's size; a per-camera *constant* on the Action family, so don't mistake it for the media size (we did, once) |
| fps | rational `<u32 num><u32 den>` near the record | `a861 0000 e803 0000` = 25000/1000 = **25 fps** — what the parser reads |
| frameRate | `u8 @ marker − 2` | the `VideoFrameRate` enum for the same value (table below) |
| resolution | `u8 @ marker − 1` | video-format index → pixel size (table below) |
| ⭐ starTag | `u8 @ [ff\|fe] 19 06 + 9` | favourite flag; the one field also present on photo records |

- **Two stores = two lists.** With a card in, the reassembled manifest is **two per-storage lists back to back** — **SD first, then internal** — each opening with its own `[u32-LE count][u32-LE size][u32-LE ts]…` header. The leading count covers only the *first* list; the rest belong to the second. Proven by dumping the same camera with and without a card: the no-card manifest is **byte-identical to the mixed manifest's second list**. (Record handles corroborate it — SD `0x0004xxxx` vs internal `0x4004xxxx` on the Action family — but the split is taken from the count, which every model writes. The camera's `storage=` HTTP index is *not* a fixed SD/internal mapping: an Xtra served its SD at `0` and internal at `1`, so resolve it by probing.)
- **Naming is irrelevant to the parse.** Because the path/name are read by length, the camera's *Naming Management* custom **Folder** and **File** prefixes decode exactly like stock — `DCIM/DJI_001/DJI_…_D.MP4` (stock), `DCIM/DJI_001/DJI_…_D_OP3.MP4` (Pocket 3), `DCIM/DJI_001_OA5/DJI_…_D_DOA5.MP4` (Action 5, custom folder + file suffix), `…_D_A01.MP4` (a user-typed `A01`) — all the same.
- **Size (solved 2026-07-24).** The media byte size is the `u32-LE` at **`marker − 12`** — confirmed exact for 85/85 Nano files against the camera's own SD card, and varying-per-file on the Action family. The nearby `marker + 30` (our old "`head+38`") is the *proxy* `.LRF` size, which is why it looked plausible on the Nano and read as a constant elsewhere. **Resolution** is in the record too, and now mapped (`u8 @ marker − 1`, table below). **`duration` is the only field still unmapped**, so the MP4 `moov` is read for that alone.

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

So **size, duration, resolution and fps are all present in every record**. `fileSize` is pinned (`marker − 12`, above) by correlating tagged records against the **camera's own SD card** mounted over USB — 85/85 files byte-exact. fps we read from the rational, and `frameRate` (`marker − 2`), `resolution` (`marker − 1`) and `starTag` are pinned too — each mapped by shooting clips with *varied* settings and diffing the manifest against the card.

**`duration` is the last field still unmapped.** It lives in the tagged `[key][type][big-endian value]` attributes we skip (`0x1c`, `0x20`–`0x22`, `0x26`, `0x28`, `0x2b`, `0x2c`, `0x31`, `0x36`, `0x37`) as an int, not literal ms, so pinning it needs clips of varied *length* cross-referenced to those keys. That is the only thing keeping the MP4 `moov` parse alive.

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

### 8. Subscribe param
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1) — used over **BLE** as a control surface as well as the datalink (Mimo subscribes the same params over BLE; a single-frame group-subscribe variant also exists there, which we don't use)
- Payload: `02020000 <sub_id:u32LE> 00000000 <len:u16LE> 00 <name_len:u8> 00 <name padded to 20> 00000000`
- Sent once per param: `camcap_mode_profile`, `camcap_video_format`, `camcap_fov`, `camcap_iso`, `camcap_photo_storage_format`, `camcap_color_mode`, `cam_storage`, `cam_status`
- DUML example (`cam_status`): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

### 9. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Response: NUL-separated ASCII `sdk\0name\0firmware` — scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

From [DJI-Wifi-Connect/pocket3](https://github.com/sniffingpickles/DJI-Wifi-Connect/tree/main/pocket3) + [osmo-download](https://github.com/SemiConscious/osmo-download). Cmd Set `0x02`, App → Camera(`0x01`,
id 0), over the datalink. **Derived from the DJI protocol standard — cmdIds solid, payloads may need
per-model adjustment; not yet verified on our Nano/Xtra, and they appear in *none* of our captures
(WiFi or BLE), so treat this whole block as unconfirmed.**

### 10. Take photo
- Cmd Set / ID: `0x02` / `0x01`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002017677>

### 11. Start recording
- Cmd Set / ID: `0x02` / `0x20`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a0000220fd47>

### 12. Stop recording
- Cmd Set / ID: `0x02` / `0x21`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002217456>

### 13. Set mode
- Cmd Set / ID: `0x02` / `0x02`  ·  payload `[mode:u8]` — `0` Photo, `1` Video, `2` Playback, `3` SlowMo, `4` Timelapse, `5` Panorama
- DUML example (Video): <https://b3yond.d3vl.com/duml/#550e0466020100a0000202017bb8>

### 14. Camera heartbeat  *(UDP datalink; Mimo sends it ~15 Hz while browsing)*
- Cmd Set / ID: `0x02` / `0x8E`  ·  cmd_type PUSH  ·  payload `00 01 14 00` — really a **parameter poll** (pid `0x0014`), not a session keepalive. We ride it as datalink traffic while browsing; what actually keeps the camera *awake* is the **BLE** keepalive `0x00/0x2b 01 01` (#21).
- DUML example: <https://b3yond.d3vl.com/duml/#55110492020100a040028e00011400a858>

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
- Fields we read: **storage total** = `u32-LE MiB @ byte 5`, **free** = `@ byte 9`.
- **Recording** is *not* `byte1 & 0x01` (a Pocket 3-repo claim that can never fire — `byte1` is a static `0x02`, the cmdSet echo, in both states; confirmed across 2279 frames in our capture). It's reported as **`payload[0] & 0x80`** (independent RE; our idle capture shows `payload[0] = 0x01`, consistent). We don't decode it yet.
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

Space the writes so `fff5` (write-without-response) doesn't drop back-to-back frames — the floor is roughly the BLE connection interval. Mimo runs tight (**~20–40 ms** between writes in the captures); ~100–500 ms is a conservative margin, not a hard requirement.
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
- Quirks: **give it a beat after GetWifiSsid** (`fff5` is write-without-response; Mimo spaces these ~20–40 ms, ~500 ms is just a safe margin). Verified on Xtra / Action 5 Pro; Nano rides the saved-password fallback.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 28. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>
