# Osmosis — Media & camera DUML commands

Implementation reference for browsing, fetching and controlling media on DJI Osmo cameras and
QuickTransfer-capable drones. Two transports:

| transport | carrier | use |
|---|---|---|
| **BLE** | write GATT `fff5`, notify `fff4` | pairing, wake, WiFi credentials, settings |
| **Datalink** | UDP, DUML in `[8B udp hdr][12B routing hdr][frame]` | media list, HTTP offload, control, status |

The BLE `[6:8]` msg-id is encoded little-endian and echoed back verbatim by the camera.

> [!IMPORTANT]
> BLE GATT setup required before the camera acts on anything. Missing any step: the camera ATT-acks
> every write and answers nothing.
> - Subscribe the CCCDs of **both** `fff4` and `fff5`.
> - Write `01 00` to the `fff4` characteristic **value** (not its CCCD), with response, after the CCCDs and before any `fff5` traffic; settle ~200 ms.
> - `fff5` is WRITE_NO_RSP only (`props=0x36`).
> - Every app→camera frame uses `cmd_type 0x40`.
> - MTU **500**. At 517 the camera stops answering every request.
> - Wait for the `0x07/0x45` pairing reply (up to ~250 ms) before sending the wake.
> - LE encryption/bonding is not required.

**Datalink ports**

| body | UDP port | TCP-7001 poke first |
|---|---|---|
| Nano, Action 5 Pro / 6, Pocket 3 / 4 / 4 Pro | `9004` | yes |
| Xtra Edge Pro | `10004` | no |
| Mavic 3, Neo 2 | `9003` (bind local `9003`) | no |

**DUML addressing byte** = `(id << 5) | type`:

| target | byte |
|---|---|
| App | `0x02` |
| Camera | `0x01` |
| Gimbal | `0x03` |
| Battery | `0x05` |
| WiFi | `0x07` |
| DM368 | `0x08` |
| Session endpoint (type `0x10`, id 7) | `0xF0` |
| Session endpoint (type `0x1C`, id 0) | `0x1C` |
| System / RTC | `0x28` |

> [!WARNING]
> Wake commands ([§21](#21-session-wake--keepalive), [Waking a sleeping camera](#waking-a-sleeping-camera))
> go to `0xF0` / `0x1C`. Addressed to the camera (`0x01`) they answer `e0` and the camera stays asleep.

---

## Per-model reference

Everything below is model-agnostic except: the UDP port, the handle geometry, the store →
`/v2?storage=` index, and the proxy extension. `(unconfirmed)` = no data; the row is the fallback.

### Identification and transport

Model id = `u16-LE` in the BLE manufacturer data under DJI company id `0x08AA`
([protocol map §1](docs/01-protocol-map.md#1-device-identification-ble-advertisement)). Resolve by
id, not name — bodies get renamed.

| Camera | model id | BLE local name | Datalink | TCP-7001 poke | WiFi |
|---|---|---|---|---|---|
| Osmo Action (1) | `0x0006` | `OsmoAction` | 9004 | yes | WPA2 |
| Osmo Action 2 | `0x0010` | `OsmoAction2` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | WPA2 `(unconfirmed)` |
| Osmo Action 3 | `0x0012` | `OsmoAction3` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | WPA2 `(unconfirmed)` |
| Osmo Action 4 | `0x0014` | `OsmoAction4` | 9004 | yes | WPA2 |
| Osmo Action 5 Pro | `0x0015` | `OsmoAction5Pro` | 9004 | yes | WPA2 |
| Xtra Edge Pro | `0x0015` | `XtraEdgePro` | **10004** | **no** | WPA2 |
| Osmo 360 | `0x0017` | `Osmo360` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | **WPA3** |
| Osmo Action 6 | `0x0018` | `OsmoAction6` | 9004 | yes | WPA2 |
| Osmo Nano | `0x0019` | `OsmoNano` | 9004 | yes | WPA2 |
| Osmo Pocket 3 | `0x0020` | `OsmoPocket3` | 9004 | yes | WPA2 |
| Osmo Pocket 4 | `0x0021` | `OsmoPocket4` | 9004 | yes | WPA2 |
| Osmo Pocket 4 Pro | `0x0022` | `OsmoPocket4P` | 9004 | yes | WPA2 |
| Mavic 3 | `0x0070` | *(varies)* | **9003** | **no** | WPA2 |
| DJI Neo 2 | `0x007e` | *(varies)* | **9003** | **no** | WPA2 |

- **Osmo Action (1)** uses the index-based list ([§1](#1-get-media-list), "Parsed — index-based") and addresses media by numeric index.
- **Osmo Action 4** and **Osmo 360** pair and hand over credentials but their AP never comes up; neither reaches the datalink. The 360 advertises an extra `fff7` characteristic.
- **Mavic 3 / Neo 2** are aircraft: `udp/9003`, no poke, `0x51` session-open first ([§27](#27-session-open-0x51--required-before-anything-else-mavic-3), [§27a](#27a-neo-2--the-same-transport-a-different-unlock)).
- **Xtra Edge Pro** is an Action 5 Pro rebrand with the same model id. Distinguish it by OUI `EC:9E:EA`. Datalink on `10004`, no poke. Answers nothing on camera-control cmdset `0x02` ([§10–17](#camera-control)).
- **Two advert formats.** Pocket 4 carries the classic model byte. Pocket 4 Pro uses the newer form: flag bit at payload byte 5 marks a 16-bit product type at bytes 10–11 (`218` = Pocket 4 Pro). A client reading only the classic field sees `0x0000` for the Pro.
- **Unrecognised body:** try `9004` + poke + WPA2, then the alternate (`10004` / no poke).

### Media layout

| Camera | Path shape | Handle base / step | Store → `/v2?storage=` | Proxy ext | Star flag |
|---|---|---|---|---|---|
| Osmo Nano | `DCIM/DJI_001/DJI_…_D` | internal `0x40100000` / `0x40` | internal → **1**, dock SD → **0** | `.LRF` | `T+8` |
| Osmo Pocket 4 | `DCIM/DJI_001/DJI_…_D` | internal `0x40100000` / `0x40` | internal → **1** | none listed | `T+8` |
| Osmo Pocket 4 Pro | `DCIM/DJI_001/DJI_…` | `0x00100000` / `0x40` | 45 → **0**, 1 → **1** | `(unconfirmed)` | `(unconfirmed)` |
| Osmo Action 5 Pro | `DCIM/DJI_001/DJI_…_D` | SD `0x00040000`, internal `0x40040000`, step `0x10` | SD → **0**, internal → **1** | `.LRF` | signature |
| Xtra Edge Pro | `DCIM/CAM_001/CAM_…_D` | SD `0x00040000`, internal `0x40040000`, step `0x10` | SD → **0**, internal → **1** | `.XRF` | signature |
| Osmo Action 6 | `DCIM/DJI_001/DJI_…_D` | SD `0x00100000`, internal `0x40100000`, step `0x40` | SD → **0**, internal → **1** | `.LRF` | `T+8` |
| Osmo Pocket 3 | `DCIM/DJI_001/DJI_…_D` | microSD `0x00040000` / `0x10` | microSD (only store) → **0** | `.LRF` | signature |

`T+8` / `signature` = the two star reads in [Star / favourite flag](#star--favourite-flag).

> [!IMPORTANT]
> Fit `base + seq × step` from the manifest's own handles, per store. Geometry is per body *and* per
> store; the model name is no guide (Pocket 4 uses the Nano's `0x40` step, not the Pocket 3's `0x10`).

- The proxy is never listed in the manifest. Build the preview URL by swapping the media path's extension (`.LRF`, `.XRF` on the Xtra). Proxy size is at `T+28`.
- Naming does not identify the family: only the Xtra writes `CAM_…`; custom Folder/File prefixes decode identically. Never parse a name to decide anything.
- Manifest count header reads `0` on the Action 5 Pro — count records.
- A two-store body sends one list per store, each with its own header and handle base. The same file number can exist on both stores; only the handle separates them.

### Storage frame and power, per body

| Camera | `0x02/0xdc` shape | Notes |
|---|---|---|
| Osmo Nano | 22 B, `stores=1` | can report `0/0` with a card in and files on internal |
| Osmo Pocket 3 | 22 B, `stores=1` | |
| Xtra Edge Pro / Action 5 Pro | 40 B, `stores=2` | |
| Osmo Action 6 | 40 B, `stores=2` | card block matches its own screen |
| Osmo Pocket 4 | 40 B, `stores=2` | two-store body even with no card — first block reads `0/0` |

Dock and charging bytes (`0x0D/0x02 @27`, `@32`) are Nano-specific. Voltage, current and percent are portable.

### Behavioural quirks

| Camera | Quirk |
|---|---|
| Osmo Nano | Dock SD reads cut a long HTTP transfer at ~757–774 MB; resume with a `Range` request. Internal streams >1.4 GB uncut. |
| Osmo Nano | Reads the dock SD only when seated lens-away from the dock screen; otherwise the SD query returns a `start` frame and no data. |
| Osmo Pocket 3 | Answers `e0` to the `0x53/0x10` wake; the AP still comes up via the `0x00/0x2b` session. |
| Osmo Pocket 3 | Answers `e0` to `0x02/0x0c`. Playback is entered with `0x01/0x01` ([§13b](#13b-pocket-3-playback-entry-0x010x01)). |
| Osmo Pocket 3 | Serves an incomplete first page when listed while still in capture. Enter playback first. |
| Osmo Pocket 3 | Still records carry `f6` (photo) / `c7` (panorama) before the `19 06` tag instead of `ff`/`fe`. |
| Osmo Pocket 4 | Folds its gimbal in playback; `0x04/0x05` telemetry rate is unchanged. |
| Osmo Pocket 4 | May need two `0x02/0x0c` attempts before it confirms playback. |
| Osmo Pocket 4 / 3 | Can hold a session from a previous connection: handshake succeeds, media query never answered. Re-handshake or power-cycle. |
| Action family | HTTP `404` / `500` during a long transfer are transient; retry with backoff. |

---

## Media

### 1. Get media list
- Cmd Set / ID: `0x00` / `0x26`  ·  App → Camera(`0x01`)  ·  datalink  ·  response `0x00/0x27`
- Payload (newest page): `4a002a10 01000000 0000 01000000 2d00 0d0100 ffffffffffffffff 0001000000000000 000000`
- DUML example: <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>

Query payload fields:

| byte | field | values |
|---|---|---|
| `@4` | request counter | echoed at reply sub-header `+4` |
| `@10–13` | cursor, `u32-LE` | `0x00000001` newest (SD) · `0x40000001` newest (internal) · a file handle = page older than it |
| `@14` | page size | `2d` = 45 |
| `@16` | list mode | `0d` full list · `10` group expand |
| `@18` | favourites only | `00` all · `01` favourites |
| `@19–22` | video kind mask, `u32-LE` | `ffffffff` all · `80000000` include · `00000000` exclude |
| `@23–26` | photo kind mask, `u32-LE` | as above |
| `@37` | highlights only | `00` all · `02` only files carrying marks |

Response: chunked `0x00/0x27` frames, payload = `[10B sub-header][chunk]`. Strip the sub-header and
concatenate chunks in arrival order.

| sub-header | field |
|---|---|
| `+0` | `0x4A` |
| `+1` | subtype: `0x04` stream start · `0x01` data chunk · `0x03` stream end. Only `0x01` carries bytes. |
| `+4` | request counter echoed from query `@4` |
| `+6` | `u16-LE` seq, restarts per page |

> [!WARNING]
> Select chunks by DUML command (`0x00/0x27`), not by the `4A 01` payload prefix. `4A 01` also
> matches parameter pushes ([§8](#8-subscribe-param)) and corrupts the manifest.

#### Filter by kind, favourite or highlight

Filtering is done by the camera: a filtered query returns up to 45 *matching* records from the whole
store, not a filtered slice of the newest 45. Both masks set plus `@37 = 02` returns videos only
(only videos carry marks).

#### Paginate the full library

One query returns the newest 45 files. Older pages need playback mode and a handle cursor.

1. Enter playback ([Holding playback mode](#holding-playback-mode)). Without it every query re-returns the newest 45.
2. Per page send three frames: `query(cursor=1)` → `trigger` → `query(cursor=page)`. The second query's cursor selects the page. Give the two queries different counters at `@4`.

| page | cursor `@10–13` | returns |
|---|---|---|
| newest | `0x00000001` / `0x40000001` | newest 45 |
| next older | oldest **video** handle (`≥ 0x40000000`) of the previous page | next 45 |
| … | repeat | until a page returns fewer than 45 records |

- Only handles `≥ 0x40000000` advance the cursor; a low-namespace photo handle stalls paging.
- Consecutive pages overlap by one boundary file; dedup by media path.
- End of library = a short page (fewer than 45 records).
- Pages run either on a fresh registered session each, or inline on one session with a correct `ackSeq` ([Datalink transport](#datalink-transport--sequencing)).

DUML examples:
- trigger (`4a040e10`): <https://b3yond.d3vl.com/duml/#551b0475020100a04000264a040e10010000000000010000008d86>
- next page (cursor `0x401036c0`): <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000c03610402d000d0100ffffffffffffffff000100000000000000000000000000a7d3>
- page after (cursor `0x40102b80`): <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000802b10402d000d0100ffffffffffffffff0001000000000000000000000000007701>

```python
import struct

_LIST    = bytes.fromhex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000")
_TRIGGER = bytes.fromhex("4a040e1001000000000001000000")
VIDEO_HANDLE_BASE = 0x40000000

def list_cmd(cursor: int) -> bytes:
    p = bytearray(_LIST)
    struct.pack_into("<I", p, 10, cursor)
    return bytes(p)

def next_cursor(page_handles, cursor):
    older = [h for h in page_handles if VIDEO_HANDLE_BASE <= h < cursor]
    return min(older) if older else None

def all_media(send_duml, collect_manifest, open_session):
    """send_duml(set, id, payload) queues a frame; collect_manifest() reassembles 0x00/0x27 and
       decodes it (decode_manifest below); open_session() registers + enters playback."""
    seen, cursor = set(), 0x40000001
    while cursor is not None:
        open_session()
        send_duml(0x00, 0x26, list_cmd(1))
        send_duml(0x00, 0x26, _TRIGGER)
        send_duml(0x00, 0x26, list_cmd(cursor))
        page = collect_manifest()
        for f in page:
            if f.path not in seen:
                seen.add(f.path); yield f
        cursor = next_cursor([f.handle for f in page], cursor)
```

#### Burst / interval groups

A burst or interval shoot is a numbered group `…_0286_D_001.JPG`, `_002`, … The list returns only the
lead (`_001`); standalone photos carry no `_NNN` suffix.

Expand a group with `0x00/0x26`:

| byte | value |
|---|---|
| `@10–13` | the group's handle (`base + seq × step`, fitted per store) |
| `@14` | frame limit (any generous value; the camera returns only the group) |
| `@16` | `0x10` |
| `@39` | `0x01` |

Reply: a small manifest of just that group, every frame with path, thumb and size. Decode as any manifest.

#### Response to 0x00/0x26

DJI CompositePack (TLV). The manifest opens with a `u32-LE` file count (`0` on Action 5/6 — count
records), then one record per file. Every field is length-delimited.

```
0d <len:u8>              <ascii>        # filename "<base>.<ext>"  (read for the ext only)
1a <total:u8> 00 00 00 01 <ascii>       # media path, ascii = total-6 bytes, "DCIM/…" (no ext)
1a <total:u8> 00 00 00 02 <ascii>       # thumb path,  "MISC/THM/…"
```

Every record carries a `19 06` tag. Let `T` = its offset. Fixed fields hang off `T`:

```
video:  03 ff 19 06            T-2 = 03 (MP4)
photo:  00 [ff|fe|f6|c7] 19 06 T-2 = 00 (JPEG) / 04 (panorama)
```

| field | where | notes |
|---|---|---|
| media path | `1a … 00 00 00 01` value | `DCIM/<folder>/<base>`, no extension |
| thumb path | `1a … 00 00 00 02` value | `MISC/THM/<folder>/<base>` |
| extension | `0d` filename field | only field carrying `.MP4`/`.JPG`/… |
| file type | `u8 @ T−2` | `MediaFileType` code (table below) |
| **handle** | `u32-LE @ T−10` | delete / favourite / group-expand handle, every record |
| **media byte size** | `u32-LE @ T−14` | video and photo |
| duration *(video)* | `u16-LE @ T−6` | whole seconds |
| frameRate *(video)* | `u8 @ T−4` | code (table below) |
| resolution *(video)* | `u8 @ T−3` | code (table below) |
| fps rational | `<u32 num><u32 den>` in the enum block | `a861 0000 e803 0000` = 25000/1000 = 25 fps; `den` ∈ {1000, 1001}; written twice |
| proxy size | `u32-LE @ T+28` | `.LRF` / `.XRF` size |
| star *(Nano, Action 6, Pocket 4)* | `u8 @ T+8` | `== 1` favourite; see [Star / favourite flag](#star--favourite-flag) |
| width, height *(photo)* | `u32-LE @ T+58` / `T+62` | pixels |

> [!WARNING]
> Bound every search at the next record's `T`, never at the record's own path. Two field orders exist,
> and an overshooting scan reads the neighbouring file's value.
>
> ```
> head · enum block · filename · media path · thumb path      # Action 6, Nano
> head · media path · thumb path · enum block · filename      # Xtra / Action 5 Pro
> ```
>
> Where the media path follows `T` directly (Pocket 3), `T` is also at `mediaPathField − 7`
> (= ascii − 13). Records are not fixed-width.

##### Two stores, labelled by counter

Cursor `0x00000001` lists the SD card, `0x40000001` the internal store — DJI `FileLocation`
(`SD_CARD=0`, `INTERNAL_STORAGE=1`), the same integer `/v2?storage=` takes. Every `0x00/0x27` chunk
echoes the query's `@4` counter at sub-header `+4`:

```
-> 0x00/0x26  byte4=1  cursor=0x00000001     list the SD card
-> 0x00/0x26  byte4=2  cursor=0x40000001     list internal
<- 0x00/0x27  sub-header byte4=1  …          SD answer
<- 0x00/0x27  sub-header byte4=2  …          internal answer
```

Fallback where the counter is not echoed, or both queries return the same list: **handle bit
`0x40000000` set → internal → `storage=1`; clear → SD → `storage=0`.** The storage index is *not*
the list ordinal.

| camera | store | handle base / step | `storage=` |
|---|---|---|---|
| Osmo Nano | internal | `0x40100000` / `0x40` | `1` |
| Osmo Pocket 4 | internal | `0x40100000` / `0x40` | `1` |
| Action 6 | SD / internal | `0x00100000` / `0x40100000`, step `0x40` | `0` / `1` |
| Xtra Edge Pro / Action 5 Pro | SD | `0x00040000` / `0x10` | `0` |
| Xtra Edge Pro / Action 5 Pro | internal | `0x40040000` / `0x10` | `1` |
| Pocket 3 | microSD (only store) | `0x00040000` / `0x10` | `0` |

Two stores in one blob = two lists back to back, SD first, each opening with its own
`[u32-LE count][u32-LE size][u32-LE ts]…` header. The leading count covers only the first list.

Custom Folder/File naming (`DCIM/DJI_001_OA5/DJI_…_D_DOA5.MP4`, `…_D_OP3.MP4`, `…_D_A01.MP4`)
decodes identically — paths are read by length.

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
    medias, i = [], 0
    while i < len(buf):
        f = read_path(buf, i, sub=1, prefix=b"DCIM/")
        if f: medias.append((i, f[1], f[0].decode())); i = f[1]
        else: i += 1

    files = []
    for k, (pos, end, path) in enumerate(medias):
        lo = medias[k-1][1] if k else 0
        hi = medias[k+1][0] if k+1 < len(medias) else len(buf)
        folder, base = path.split("/")[1], path.rsplit("/", 1)[-1]

        ext, j = "", lo
        while j < hi - 2:
            if buf[j] == 0x0D and buf[j+2:j+2+len(base)+1] == (base + ".").encode():
                ext = buf[j+2+len(base)+1 : j+2+buf[j+1]].decode().upper(); break
            j += 1

        handle = size = 0
        m = buf.find(b"\x03\xff\x19\x06", lo, hi)
        if m != -1:
            T = m + 2
            handle = struct.unpack_from("<I", buf, T - 10)[0]
            size   = struct.unpack_from("<I", buf, T - 14)[0]

        files.append(dict(folder=folder, name=f"{base}.{ext}" if ext else base,
                          handle=handle, size=size))
    return files

manifest_bytes = b""            # reassembled 0x00/0x27 payload
media_files = decode_manifest(manifest_bytes)
count = struct.unpack_from("<I", manifest_bytes, 0)[0] if manifest_bytes else 0
print(f"File count: {count or len(media_files)}")
for f in media_files:
    print(f"Folder {f['folder']} - Name {f['name']} - Size {f['size']}")
```

#### `MediaFile` schema

| field | type | wire |
|---|---|---|
| `fileName` | String | the `0d` field |
| `fileType` | `MediaFileType` | `u8 @ T−2` |
| `fileSize` | Long | `u32-LE @ T−14` |
| `duration` | Long | `u16-LE @ T−6` (seconds) |
| `frameRate` | code | `u8 @ T−4` |
| `resolution` | code | `u8 @ T−3` |
| `starTag` | `MediaFileStarTag` | see [Star / favourite flag](#star--favourite-flag) |
| `date` | DateTime | capture time |
| `orientation`, `cameraOrientation` | enum | rotation |
| `photoType` / `videoType` / `panoType`, `videoEncodeType`, `videoSpeedRatio`, `timeLapseInterval` | enum/int | mode metadata |
| `dirIndex`, `fileIndex`, `subIndex`, `segSubIndex`, `fileGroupIndex` | int | DCF indices |
| `proxyInfo`, `hasProxy`, `EXIFInfo` (`physicalPathInfo`), `dcfInfo` | nested | the `DCIM/…` / `MISC/…` strings live here |

##### Star / favourite flag

`MediaFileStarTag`: `0 = NONE`, `1 = TAGGED`. Two reads, in this order:

1. **Signature read** — the `00`/`01` byte immediately after this 12-byte signature, which occurs once per record after its media path:
   ```
   1b 0a 00 00 00 02 02 01 14 02 15 03  <00|01>
   ```
   Match all twelve bytes. Xtra Edge Pro, Action 5 Pro, Pocket 3.
2. **Fallback** `u8 @ T+8`, test `== 1`. Nano, Action 6, Pocket 4 (their records carry `… 14 02 15 00`, so the signature does not match).

> [!CAUTION]
> Never test `T+8 != 0`. On the Xtra / Action 5 Pro / Pocket 3 that byte is a path length (`44`/`48`)
> and every file reads as starred.

##### Enum tables

**frameRate** (`T−4`):

| code | fps |
|---|---|
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

**`MediaFileType`** (`T−2`):

| code | type |
|---|---|
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
|---|---|
| `0` | NORMAL |
| `1` | SLOW_MOTION |
| `2` | HYPER_LAPSE |
| `3` | TIME_LAPSE |
| `4` | HDR |
| `5` | LOOP |
| `101`–`104` | MASTERSHOT |

**`MediaPhotoType`**

| code | mode |
|---|---|
| `0` | NORMAL |
| `1` | HDR |
| `2` | AEB |
| `3` | INTERVAL |
| `4` | BURST |
| `16` | HIGH_RESOLUTION |

**Resolution** (`T−3`):

| code | hex | resolution |
|---|---|---|
| `10` | `0A` | 1920×1080 (1080p 16:9) |
| `12` | `0C` | 1920×1440 (1080p 4:3) |
| `16` | `10` | 3840×2160 (4K 16:9) |
| `45` | `2D` | 2688×1512 (2.7K 16:9) |
| `66` | `42` | 1080×1920 (1080p 9:16) |
| `67` | `43` | 1512×2688 (2.7K 9:16) |
| `95` | `5F` | 2688×2016 (2.7K 4:3) |
| `103` | `67` | 3840×2880 (4K 4:3) |
| `105` | `69` | 1080×1080 (1080p 1:1) |
| `106` | `6A` | 2160×2160 (2160p 1:1) |
| `107` | `6B` | 3072×3072 (3K 1:1) |
| `108` | `6C` | 1728×3072 (3K 9:16) |
| `125` | `7D` | 3840×3840 (4K OpenGate, 1:1 full sensor) |

> [!NOTE]
> Thanks to [Kaze-for-DJI](https://github.com/brianmerchant/Kaze-for-DJI/commit/341a35de18493ff61f97c93b8b10161a7512aa36) for the Pocket 3 1:1 / 9:16 resolutions.

`125` OpenGate: `.LRF` proxy is 720×720 (1280×720 for 4K 16:9); bitrate ~96 Mbit/s.

#### Parsed — index-based (Osmo Action 1/2/3)

Header `[u32-LE count][u32-LE total_size]`, then fixed 65 B records, no path strings; files are keyed by `FileIndex`.

| offset | type | field |
|---|---|---|
| `[0:4]` | u32-LE | Unix timestamp |
| `[8:12]` | u32-LE | **FileIndex** (`0x640251`…) |
| `[10:14]` | 2×u16-LE | DCF dir / file number (`100` = `100MEDIA`) |
| `[19:23]` | u32-LE | video UUID (Amba `DjiMovDmx`) |
| `[38:42]` | u32-LE | size (~KB) |

### 1a. Unlisted sidecar files (`.DNG` / `.WAV`)

JPEG+RAW leaves a `.DNG` beside the still; Built-In Mic Audio Backup leaves a `.WAV` beside the clip.
Neither is listed and there is no manifest flag. Same path as the parent, extension swapped, same store:

```
GET /v2?storage=1&path=DCIM/CAM_001/CAM_20260822234658_0073_D.DNG   -> 200, 80,332,744 B
GET /v2?storage=1&path=DCIM/CAM_001/CAM_20260822234724_0075_D.WAV   -> 200,    925,740 B
```

### 2. Delete media
- Cmd Set / ID: `0x00` / `0x28`  ·  App → Camera(`0x01`)  ·  datalink  ·  write, needs a correct `ackSeq` or a fresh registered session
- Payload: `[count:u8] [handle:u32-LE × count] [counter:u32-LE] 00 [count:u32-LE] 01 01 00 00`
- Response: `0x00/0x28` → `0000` = OK  ·  `00d6` = no such handle
- DUML example (handle `0x40104480`): <https://b3yond.d3vl.com/duml/#551f044e020100a0400028018044104001000000000100000001010000a0d1>

| field | notes |
|---|---|
| `count` (u8 and u32) | files in this batch; one command deletes the whole selection, one `0000` back |
| `handle` | `u32-LE @ T−10` of the record, every media type. A group (burst/interval) is deleted by its `_001` lead handle; frames are never enumerated |
| `counter` | per-command running index; not policed by the camera |
| `00 … 01 01 00 00` | storage selector, verbatim |

```
delete 1 file h :  01 <h> 01000000 00 01000000 01010000
delete 11 files :  0b <h1…h11> 02000000 00 0b000000 01010000
```

> [!CAUTION]
> Irreversible. Send a handle only when the bytes at `T−10` and the `base + seq × step` fit from the
> other records in the same list agree. Treat a handle held by more than one record as non-deletable
> for all of them.

- A favourite does not protect a file; the camera deletes it with `0000`.
- Free space in `0x02/0xdc` ([§19](#19-sd--storage)) moves by the file's size within ~2 s — a confirmation without re-listing.
- Cost ~1 s to `0000`. A re-registration (handshake + register) adds ~4 s.

### 3. Favorite / star media
- Cmd Set / ID: `0x02` / `0xBF`  ·  App → Camera(`0x01`)  ·  datalink  ·  write, playback mode active
- Payload: `01 01 [handle:u32-LE] [counter:u32-LE] 00 [on:u8] 00 00 00`
- Response: `0x02/0xBF` → `00` = OK
- DUML example (handle `0x40104040`): <https://b3yond.d3vl.com/duml/#551c041b0201befd4002bf0101404010400100000000010000008c88>

| field | notes |
|---|---|
| `handle` | the record's manifest handle (`u32-LE @ T−10` = `base + seq × step`) |
| `counter` | per-action running index (1, 2, …) |
| `on` | `01` favourite · `00` un-favourite |

### 3a. Highlight / moment marks
- Cmd Set / ID: `0x02` / `0xFF`  ·  App → Camera(`0x01`)  ·  datalink  ·  read-only (`camera_expansion_cmd` / `PullHighLightAction`)
- Payload: `40 2f 00 01 0b 00 00 00 [handle:u32-LE] 00 00`
- Response: `00 · 40 2f 00 01 · [len:u32-LE] · [handle:u32-LE] · [count:u8] · 00 · { 00 [startTimeMs:u32-LE] } × count`

Count at reply byte 13, first mark at byte 16, stride 5. Marks are points in ms (e.g. `4000, 7000`).

---

## Datalink session

### Holding playback mode

Playback is a camera-wide mode. Pagination and several commands need it; a gimballed body stops
filming while it is held. The camera drops the mode ~1 s after entry unless the app beats.

| | frame | when |
|---|---|---|
| enter | `0x02/0x0c` payload `01 01 00 01` | once, after registration (Pocket 3: [§13b](#13b-pocket-3-playback-entry-0x010x01)) |
| beat | `0x00/0x88` sub-cmd `0x17` (14 B, ASCII `APP` at bytes 5–7) | every ~1 s, whole session |
| re-assert | `0x02/0x0c` payload `01 01 00 01` | every ~10 s (optional, idempotent) |
| leave | `0x02/0x0c` payload `01 01 00 00` | teardown only |

- DUML example (enter): <https://b3yond.d3vl.com/duml/#55110492020100a040020c01010001b63b>

```
1. handshake + register                              §4–§7
2. send 0x02/0x0c 01 01 00 01
3. wait up to ~900 ms for the 0x02/0x0c reply; no reply -> resend, up to 3 attempts
4. loop ~1 Hz until teardown:    0x00/0x88 sub-cmd 0x17
5. every ~10 s:                  0x02/0x0c 01 01 00 01
6. teardown only:                0x02/0x0c 01 01 00 00
```

> [!IMPORTANT]
> The `0x02/0x0c` reply means *received*, not *entered*. Confirm on bit 30 of `0x02/0x80`
> ([§20b](#20b-camera-state-flags-0x020x80)); that bit is the definition of "held".

> [!WARNING]
> Do not poll `0x02/0x8E` while holding playback. It is a parameter GET ([§14](#14-camera-parameters))
> and takes the camera out of playback ~1 s later. Enter playback *before* the first list query.

Alternative beat: `0x00/0x88` sub-cmd `0x1a` (`1a 00 00 00 01`, 5 B) at ~1 Hz, after two `0x17`
announces. Status pushes (`0x02/0x80`, `0x02/0x82`) arrive regardless of playback.

### Datalink transport / sequencing

Each UDP packet is `[8B udp hdr][12B routing hdr][DUML frame]`, a sliding-window sequenced transport.

- **udp hdr** `[8]`: `[len|0x8000 :u16][sessionId:u16][seq:u16-LE][pktType:u8][xor:u8]`
- **routing hdr** `[12]`: `[ackSeq:u16-LE][ownSeq:u16-LE] 00 00 00 00 [counter:u8] 01 00 00`

| pktType | meaning |
|---|---|
| `0x00` | handshake |
| `0x01` | camera data / telemetry |
| `0x03` | manifest data (drone) |
| `0x04` | ACK of the camera's stream (seq 0, routing `[camSeq][camSeq]`) |
| `0x05` | command (carries a DUML frame) |

For a command packet:

```
ownSeq = app's own counter, +8 per command, starts at camera_channel + 8, wraps at 0xFFFF
ackSeq = ownSeq - 8            (the previous command seq; never the camera's telemetry seq)
```

With this `ackSeq` every command (delete, favourite, group-expand, pagination, highlights) runs inline
on one long-lived session. A wrong `ackSeq` silently drops writes; reads still answer.

The keep-alive thread owns the socket; queue commands that need a reply to it. The camera sends an
empty-payload transport ACK before the real reply — skip it.

> [!WARNING]
> A registered session stops accepting writes after ~40–70 s (Nano ~70 s, Xtra Edge Pro ~50 s).
> Reads are unaffected. Re-register before a write once the session is older than 40 s.

### 4. Handshake *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo. Drain heartbeats, read `camera_channel` from heartbeat routing `[8:10]`; app UDP seq starts at `camera_channel + 8`.

### 5. Device info
- Cmd Set / ID: `0x00` / `0x81`  ·  App → DM368(`0x08`, id 2)  ·  `cmd_type 4`
- Payload: `00 "APP" 00×37 02 00×8 02 08 00×10` (62 B)
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
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1)  ·  `cmd_type 0x40`  ·  BLE or datalink
- Payload, one frame per parameter, verb `0x02`:

```
02 02 00 00 | sub_id:u32-LE | 00 00 00 | (name_len+6):u16-LE | name_len:u16-LE | <name ascii> | 00 00 00 00
```

- Response: ACK `plen=10`, then the value and every later change as pushes:

```
02 06 00 00 | idx:u32-LE | 00 00 00 | total_len:u16-LE | name_len:u16-LE | <name> | 00 x6 | value_len:u16-LE | <value>
```

- DUML example (`cam_status`): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

> [!NOTE]
> There is no group subscribe: `01 00 06 00 "camera"` (verb `0x01`) is ACKed `plen=0` and never sends an item. Subscribe each name.

- `camcap_*` = what the body supports (capability table, sent once after subscribe). `cam_*` = the current value (re-pushed ~0.5–1 Hz).
- `sub_id` increments per subscription; frames are variable length (name not padded).

> [!CAUTION]
> Do not sweep a value space to discover codes. Enumerating `0x02/0xE1` freezes a Nano (power-cycle).

**Decoded values** (Nano):

| name | contents |
|---|---|
| `cam_video_param_v2` | `[resolution:u8][fps_idx:u8]…` — live video setting. `67 02` = res 103 (4K 4:3) @ fps idx 2 (25). Same codes as [§1](#1-get-media-list). Keeps reporting the video setting in photo mode. |
| `camcap_video_format` | `01 \| len:u16-LE \| count:u8 \| count × [res:u8][fps_idx:u8][flags:u8]`. Nano: 35 pairs; 4K 4:3 caps at 50 fps. |
| `cam_photo_param_new` | 24 B, `[?][0x15][00][size:u8][aspect:u8]…`. Size `0x03` = M, `0x04` = L (letter labels, not megapixels). Aspect `0x00` = 4:3, `0x01` = 16:9. |
| `cam_storage` 40 B · `cam_status` 9 B · `cam_record_time` 6 B · `cam_image_effect` 16 B · `cam_lens_state` 66 B · `cam_custom_mode_params` 161 B | present, not decoded |

**All 53 names the official app subscribes:** `camcap_base camcap_video_format camcap_fov camcap_iso camcap_photo_storage_format camcap_color_mode camcap_wb camcap_photo_size camcap_video_codec camcap_shutter camcap_photo_timer_interval camcap_exposure_mode camcap_zoom camcap_antiflicker camcap_sharpness camcap_denoise camcap_aperture camcap_shutter_max camcap_eis camcap_iso_auto_max camcap_loop_video_duration camcap_hyperlapse_ratio camcap_slowmotion_ratio camcap_timelapse_duration camcap_countdown camcap_photo_time_limited_burst_param camcap_capture_aspect_type camcap_style_filter_mode cam_storage cam_status cam_record_time cam_expo_param shutter_param cam_photo_param_new cam_lapse_param cam_video_param_v2 cam_image_effect v_quality_enhance_status cam_fov cam_lens_state cam_audio_status_v2 audio_timecode_status temp_curve camcap_common cam_imu_calib_info timecode_info cam_custom_mode_params cam_super_slowmotion_status media_file_sync upgrade_status cam_capture_aspect_type gui_autorecord_param cam_style_filter_status`

### 9. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2)  ·  `cmd_type 4`  ·  empty payload
- Response: NUL-separated ASCII `sdk\0name\0firmware`; firmware = the `NN.NN.NN.NN` string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

Cmd Set `0x02`, App → Camera(`0x01`), `cmd_type 0x40` (request) / `0xC0` (response). A `0x00` frame
in cmdset `0x02` is dropped before the dispatcher. `0x02/0x02` is the record control.

> [!CAUTION]
> Works on the Nano and Pocket 3. On an **Xtra Edge Pro** commands to receiver `0x01` get no reply at
> all, while pairing, wake and status pushes work normally.

Reply byte:

| reply | meaning |
|---|---|
| `00` | success |
| `d9` | supported, wrong state (e.g. already recording) |
| `df` | supported, wrong parameter |
| `e3` | supported, bad/missing parameter |
| `e0` | not supported |
| *(no reply)* | receiver does not exist |

### 10. Shoot photo
- Cmd Set / ID: `0x02` / `0x01`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[01]`
- Response: `00`

`[01]` is the shutter trigger, not the photo type; set the mode with [§13a](#13a-set-shooting-mode)
first. Burst/interval completes on its own. Empty payload → `e3`; sent in a video mode → `d9`.

### 11. Start recording
- Cmd Set / ID: `0x02` / `0x02`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[01]`
- Response: `00`, then the `0x02/0x80` recording bit sets ([§18](#18-camera-status)) — Nano ~860 ms, Pocket 3 ~600 ms. Wait on the bit, not a delay.
- DUML example: <https://b3yond.d3vl.com/duml/#550e046602010204400202014e61>

> [!NOTE]
> Pocket 3 passes through state byte `41` before `81`. Test `== 0x81`, not "any change".

### 12. Stop recording
- Cmd Set / ID: `0x02` / `0x02`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[00]`
- Response: `00`; the recording bit clears ~2.4 s later on a Nano, ~700 ms on a Pocket 3 (state byte `c1` while finalising, then `01`).
- DUML example: <https://b3yond.d3vl.com/duml/#550e04660201020440020200c770>

Not a toggle: `[01]` while recording answers `df`. Drive start/stop off the recording bit.

### 13. Set work mode
- Cmd Set / ID: `0x02` / `0x02`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`, `0`–`3` (`[04]` → `df`)

> [!WARNING]
> This is DJI's four-value *work* mode (capture / record / playback / download), and `0x02/0x02` is
> also the record control: `[00]` / `[01]` stop / start a recording. Exclude them from any mode
> switcher. `[03]` is accepted and changes nothing visible. Shooting mode is [§13a](#13a-set-shooting-mode).

### 13a. Set shooting mode
- Cmd Set / ID: `0x02` / `0xE1`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  payload `[mode:u8]`  ·  reply `00`

| value | mode | DUML example |
|---|---|---|
| `0x00` | SlowMo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10036b3> |
| `0x01` | Video | <https://b3yond.d3vl.com/duml/#550e0466020102044002e101bfa2> |
| `0x02` | TimeLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1022490> |
| `0x05` | Photo | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1059be4> |
| `0x0a` | HyperLapse | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10a6c1c> |
| `0x28` | SuperNight | <https://b3yond.d3vl.com/duml/#550e0466020102044002e1287c1e> |
| `0x0c` | Panorama | <https://b3yond.d3vl.com/duml/#550e0466020102044002e10c5a79> |

- The enum is sparse and unordered — table it, never compute it. On-screen carousel order: Video → Photo → TimeLapse → HyperLapse → SuperNight → SlowMo.
- Readback: `0x02/0x80` byte `@57`, same encoding ([§18](#18-camera-status)).

### 13b. Pocket 3 playback entry (`0x01/0x01`)
- Cmd Set / ID: `0x01` / `0x01` (`SPECIAL Control`)  ·  App → Camera(`0x01`)  ·  `cmd_type 0x00`  ·  no reply

A Pocket 3 answers `e0` to `0x02/0x0c` and stays in capture. Send two payloads in order:

| # | payload | repeat |
|---|---|---|
| 1 | `03 00000000 04000000 07 01` | ~6 frames at ~20 Hz |
| 2 | `00 00000000 04000000 04 01` | ~20 Hz until the playback bit sets |

- The playback bit ([§20b](#20b-camera-state-flags-0x020x80)) sets ~350 ms into payload 2; the gimbal folds. The bit is the only completion signal.
- Try `0x02/0x0c` first on any body; fall through to this when the bit does not set within ~1 s.
- No exit command. The camera returns to capture on its own a few seconds after the link drops.

### 14. Camera parameters
- Cmd Set / ID: `0x02` / `0x8E`  ·  App → Camera(`0x01`)  ·  `cmd_type 0x40`  ·  BLE or datalink

```
GET = 00 01 <pid:u16-LE>                    -> 00 00 01 <pid:u16-LE> <len:u8> <value…>
SET = 01 01 <pid:u16-LE> <len:u8> <value…>  -> 00
```

A GET for a pid not valid in the current state answers a single error byte (`e3`, `df`, `d9`).

| pid | field | values |
|---|---|---|
| `0x0009` | field of view | `05` = Natural-Wide · `01` = Wide |
| `0x000f` | ISO limit | `04` = 100–800 · `05` = 100–1600 |
| `0x0014` | *(polled by the official app ~15 Hz)* | |

- DUML example (GET `0x0009`): <https://b3yond.d3vl.com/duml/#551104920201020440028e00010900778d>
- DUML example (SET `0x0009` = `01`, Wide): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010900010189d4>
- DUML example (SET `0x0009` = `05`, Natural-Wide): <https://b3yond.d3vl.com/duml/#551304030201020440028e010109000105ad92>
- DUML example (SET `0x000f` = `04`, ISO 100–800): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010f000104bec8>
- DUML example (SET `0x000f` = `05`, ISO 100–1600): <https://b3yond.d3vl.com/duml/#551304030201020440028e01010f00010537d9>
- DUML example (GET `0x0014`): <https://b3yond.d3vl.com/duml/#55110492020100a040028e00011400a858>

### 15. Camera state query
- Cmd Set / ID: `0x02` / `0xA0`  ·  App → Camera(`0x01`)  ·  cmd_type PUSH  ·  empty payload
- Response: 28 B, `recording_time_s` = `u16-LE @ 6`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002a0f5c3>

### 16. Camera status poll
- Cmd Set / ID: `0x02` / `0x61`  ·  App → Camera(`0x01`)  ·  cmd_type PUSH  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002617014>

### 17. Set time & timezone
- Cmd Set / ID: `0x00` / `0x6A`  ·  App → **receiver `0x28`** (system/RTC). Receiver `0x01` silently drops it.
- Payload: `01 00` · `[unix seconds : u64-LE]` · `[UTC offset minutes : i16-LE]` · `[tz len : u8]` · `[IANA tz id, ASCII]`

| bytes | field | example (`Europe/Madrid`, +120 min) |
|---|---|---|
| `00-01` | prefix | `01 00` |
| `02-09` | unix seconds, `u64-LE` | `ce 2a 66 6a 00 00 00 00` |
| `10-11` | UTC offset minutes, `i16-LE` | `78 00` (= 120) |
| `12` | tz-id length, `u8` | `0d` (= 13) |
| `13..` | IANA tz id, ASCII | `45 75 72 6f 70 65 2f 4d 61 64 72 69 64` |

- Response `55 … C0 00 6A 00 01 00 …` — first payload byte `0x00` = OK.
- Send right after registration on every connect; recorded file timestamps follow the camera clock.
- DUML example: <https://b3yond.d3vl.com/duml/#55270415022828f740006a0100ce2a666a0000000078000d4575726f70652f4d61647269640c0e>

---

## Status pushes (camera → app)

### 18. Camera status
- Cmd Set / ID: `0x02` / `0x80`  ·  Camera → App  ·  ~10 Hz push, 60 B

| offset | type | field | idle → recording |
|---|---|---|---|
| `@0` | `u8` bitfield | **bit7 = recording** | `01` → `81` |
| `@4` | `u8` | `1` = video-ish mode, `0` = Photo | |
| `@5` | `u32-LE` | active store total, MiB | |
| `@9` | `u32-LE` | active store free, MiB | falls while recording |
| `@13` | `u16-LE` | photos remaining | `0` outside Photo |
| `@17` | `u16-LE` | remaining recordable seconds | counts down; `0` in Photo |
| `@29` | `u16-LE` | elapsed record time, seconds | counts up |
| `@57` | `u8` | current shooting mode ([§13a](#13a-set-shooting-mode) encoding) | |

| offset | Video | SlowMo | Photo |
|---|---|---|---|
| `@57` | `01` | `00` | `05` |
| `@4` | `01` | `01` | `00` |
| `@17` | 1050 | 953 | 0 |
| `@13` | 0 | 0 | 5048 |

> [!NOTE]
> `@17` and `@13` are mutually exclusive; check `@4` or `@57` before rendering either.

### 19. SD / storage
- Cmd Set / ID: `0x02` / `0xDC`  ·  Camera → App  ·  push, 22 B (one store) or 40 B (two stores)

| offset | type | field |
|---|---|---|
| `@2` | `u8` | store count (`1` or `2`); `@5` mirrors it |
| `@6` | `u32-LE` | first store total, MiB (`0` = no card) |
| `@10` | `u32-LE` | first store free, MiB |
| `@24` | `u32-LE` | built-in total, MiB (absent on 22 B) |
| `@28` | `u32-LE` | built-in free, MiB |
| `@32–39` | | unmapped |

- Gate the decode on `size >= 22` for the first block, `>= 32` for the second. Never on an exact length.
- Card present = first-store total `> 0`. Byte 0 is not an SD-inserted flag.

```
Nano  22 B  00 12 01 00 00 01 | e7ed0000 09e10000 | …                                    60903/57609 MiB
Xtra  40 B  11 12 02 00 00 02 | 00000000 00000000 | … 0101 | 54bf0000 16bf0000 | a8850000 00000000
                                 ^ no card                    ^ 48980/48918 MiB built-in
```

> [!WARNING]
> A Nano can push `0/0` in a well-formed 22 B frame with a card in and files on internal. Keep the
> last non-zero values until a later push supersedes them.

### 20. Battery / power
- Cmd Set / ID: `0x0D` / `0x02`  ·  Battery(`0x05`, id 0) → App  ·  ~1 Hz push, 34 B

| offset | type | field |
|---|---|---|
| `@1` | `u16-LE` | pack voltage, mV |
| `@5` | `i32-LE` | current, mA (`+` charging, `−` discharging) |
| `@17` | `u16-LE` | temperature, °C×10 `(unconfirmed)` |
| `@20` | `u8` | charge percent |
| `@27` | `u8` | dock attached (`0x40` docked) — Nano only |
| `@32` | `u8` | taking charge (`1`/`0`) — Nano only |

The dock is not a separate DUML device; `@27`/`@32` are its only signal. The dock's own charge level
and its SD capacity are not reported.

### 20a. Gimbal position telemetry
- Cmd Set / ID: `0x04` / `0x05` (`GIMBAL GetPushParams`)  ·  Camera → App  ·  ~10 Hz push, layout unmapped

> [!NOTE]
> Fixed heartbeat, not a motion signal: the rate is the same folded, live, or after a refused mode change.

### 20b. Camera state flags (`0x02/0x80`)
- Cmd Set / ID: `0x02` / `0x80` (`GetPushStateInfo`)  ·  Camera → App  ·  `u32-LE` flags word at `@0`

| bit | mask | meaning |
|---|---|---|
| 0 | `0x00000001` | connected |
| 15–16 | | firmware-error code (enum) |
| 18 | `0x00040000` | photo capture enabled (`0` when enabled) |
| 22–23 | | encryption status (enum) |
| 28 | `0x10000000` | tracking mode |
| 29 | `0x20000000` | hyperlapse mode |
| **30** | **`0x40000000`** | **in playback mode** |

Bit 30 is the only reliable playback indicator: `1` within ~200 ms of the mode actually changing.

---

## Connection (BLE control)

### Waking a sleeping camera

A sleeping camera keeps advertising `ADV_IND`. The wake is a command sequence over GATT `fff5`:

| # | write | receiver | note |
|---|---|---|---|
| 1 | `0x00/0x2b` `04 00` | `0xF0` | before pairing |
| 2 | `0x07/0x45` SetPairingPIN | `0x07` | [§22](#22-setpairingpin) |
| 3 | `0x00/0x2b` `01 01` | `0xF0` | then ~1 Hz forever (keepalive) |
| 4 | `0x53/0x10` `00 00 00 00` | `0x1C` | camera answers `01 00 00 00` and wakes |

Space consecutive `fff5` writes by at least the BLE connection interval (~10 ms; 100–500 ms is a safe
margin). ConnectToWiFi ([§23](#23-connecttowifi)) is not part of this flow.

### 21. Session wake / keepalive
- Cmd Set / ID: `0x00` / `0x2b`  ·  App → `0xF0` (type `0x10`, id 7)  ·  BLE
- Payload: `04 00` = open the session (once, pre-pairing) · `01 01` = keepalive (~1 Hz)
- DUML example (`04 00`): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b04009ab9>
- DUML example (`01 01`): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b0101abd6>

> [!NOTE]
> The Nano drops an idle paired link after ~5–6 s; keep the `01 01` ping running all session.

### 22. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`)  ·  BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`)
- Response: `00 01` = already paired · `00 02` = approval required. Approval arrives as a `0x07/0x46` **request** (flags `0x40`) — ACK it; it is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

| field | camera | drone |
|---|---|---|
| token | `"osmo"` (any value pairs) | `"DJI FLY"` — anything else pairs but the WiFi getters return nothing |
| identifier | 32 chars | 32 chars; the key the device stores its approval under |

> [!IMPORTANT]
> Mint one identifier per install and persist it. A known identifier skips the approval prompt; a new
> one prompts every time and burns a remembered slot. Send the same identifier on retries.

Approval on a drone: LEDs chase, then a power-button hold (2 s most models, 3 s newest; Mini 3 = three
quick presses instead).

```
-> 0x07/0x45  SetPairingPIN(token="DJI FLY", id=…)
<- 0x07/0x45  [00 02]        +1.4 s   approval required, LEDs chase
<- 0x07/0x46  [01]           after the button hold (request, flags 0x40)
<- 0x07/0x0e                 +1.6 s   passphrase released
```

### 23. ConnectToWiFi
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`)  ·  BLE  ·  fallback only
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's own credentials
- Response: `00 00` = ok; AP up ~15 s later
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 24. GetWifiSsid
- Cmd Set / ID: `0x07` / `0x07`  ·  App → WiFi(`0x07`)  ·  BLE  ·  empty payload
- Response: `[status:1][PackString ssid]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472>

### 25. GetWifiPassword
- Cmd Set / ID: `0x07` / `0x0e`  ·  App → WiFi(`0x07`)  ·  BLE  ·  empty payload
- Response: `[status:1][PackString passphrase]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

> [!NOTE]
> Leave a beat after GetWifiSsid (`fff5` is write-without-response). A Nano may not return a password here; fall back to saved credentials.

### 26. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`)  ·  BLE  ·  empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>

---

## DJI Drone QuickTransfer media offload

Everything below is the Mavic 3 family (Mavic 3, Classic, Pro) unless stated.

| | Mavic 3 | Neo 2 |
|---|---|---|
| BLE pair, `DJI FLY` token | yes | yes |
| WiFi creds over `0x07/0x07` + `0x07/0x0e` | yes | yes |
| Datalink | udp/9003 | udp/9003 |
| Handshake reply | 9 B | 15 B ([§27a](#27a-neo-2--the-same-transport-a-different-unlock)) |
| Serial tag in the `0x51/0x13` beacon | `0x11` | `0x24` |
| Answers `0x51/0x02` session-open | yes | **no** |
| Media list | yes | not reached |

Differences from an Osmo camera:

| | Osmo camera | DJI drone |
|---|---|---|
| Pairing token | `osmo` | `DJI FLY` |
| Datalink | UDP `9004` + TCP-7001 poke (Xtra: `10004`) | UDP `9003`, no poke, bind local `9003` |
| Session | handshake → registration → commands | handshake → `0x51` session-open ([§27](#27-session-open-0x51--required-before-anything-else-mavic-3)) |
| Media addressing | paths, `/v2?storage=N&path=…` | DCF indices, `/v1?file_index=…` ([§29](#29-http-media-api-v1--dcf-indexed)) |
| Registration | `0x00/0x81`, `0x00/0x88`, `0x03/0xda`, param subs | none |

Addressing byte unchanged (App `0x02`, Camera `0x01`). The `0x51` channel uses its own endpoints
(`0xee` app, `0xe9` drone).

### 27. Session open (`0x51`) — required before anything else *(Mavic 3)*
- Cmd Set / ID: `0x51` / `0x02` open · `0x08` challenge · `0x06` identity · `0x13` beacon  ·  App(`0xee`) ⇄ Drone(`0xe9`)  ·  datalink
- Wrapper: every `0x51` frame = inner DUML frame + 22 trailing bytes, carried as the payload of an outer `0x51/0x01` frame (target `0xe93b`)
- DUML example (`0x51/0x02` open, outer frame): <https://b3yond.d3vl.com/duml/#553504683be90100005101551204c7eee97c004051020501040100619639fdb2ae020100000079102e9b010000000000000000f340>

| step | dir | frame | flags | inner payload |
|---|---|---|---|---|
| 1 | → | `0x51/0x13` | `0x00` | app identity (answers the beacon) |
| 2 | → | `0x51/0x02` | `0x40` | `05 01 04 01 00` |
| 3 | ← | `0x51/0x08` | `0x40` | drone serial + app id (challenge) |
| 4 | → | `0x51/0x08` | `0xC0` | `00 00 <tag> <serial:20> 00` |
| 5 | → | `0x51/0x06` | `0x40` | `04 02 00 <appid:19> 00 00 00 <tag> <serial:20> 00` |
| 6 | ⇄ | `0x51/0x06` | `0xC0` | serial echo, both directions |

- **Serial** = 20 uppercase alphanumerics in the drone's `0x51/0x13` beacon, preceded by a tag byte (`0x11` Mavic 3, `0x24` Neo 2). Find it by shape, echo the tag in steps 4–5.
- **Trailing bytes** `39fdb2ae 02 <ctr> 00 00 00 79102e9b 01 00×8` — `ctr` must increase on every `0x51` frame sent; a repeat is dropped as a replay.
- **Outer DUML message id** is a per-frame counter from `1`.
- Before the session opens the drone emits ~2 frames/s; after, ~1200 frames/s. The jump is the tell.

> [!NOTE]
> The routing header's `r0-1` on a received packet is not a running ack, and the sequence window is
> not enforced on aircraft.

### 27a. Neo 2 — the same transport, a different unlock

Pairing, credentials, join and handshake on udp/9003 all complete. The aircraft then ignores
`0x51/0x02` (beacons only, frame rate stays ~5/s) and serves no media.

- Handshake reply is 15 B: the 9 B form plus `01 0f 00 05 05 40 1f`, constant across sessions. Ignorable.
- DJI Fly never sends `0x51/0x02` to a Neo. Its init is ~86 `0x00/0x99` subscriptions plus 14 `0x03/0xcd` upload chunks (`01 00` … `01 0d`), and the aircraft opens only after the whole sequence lands. Its `0x51` tunnels carry `51/13`, `51/17`, `03/f9`, `03/cd`.
- The AP drops ~16 s after joining while the session is unopened.

Unlock sequence: unknown. [§29](#29-http-media-api-v1--dcf-indexed) has not been exercised on a Neo 2.

### 28. Get media list (drone)
- Cmd Set / ID: `0x00` / `0x26`  ·  App → Camera(`0x01`)  ·  datalink  ·  response `0x00/0x27`
- Payload (newest page): `4a002110 0c00 00000000 01000000 2d 000d0100 ffffffffffffffff 000100000000`
- Response: chunked `0x00/0x27` frames, subtype `0x01`, on `pktType 0x03`
- DUML example: <https://b3yond.d3vl.com/duml/#552e04a7020177c94000264a0021100c0000000000010000002d000d0100ffffffffffffffff000100000000c085>

`0x4a` envelope (both directions, all subtypes):

| off | size | field |
|---:|---|---|
| +0 | u8 | `0x4a` |
| +1 | u8 | subtype |
| +2 | u16 | low 12 bits = payload length; bit `0x1000` = **final chunk** |
| +4 | u16 | seq (reply echoes the query's) |
| +6 | u32 | chunk index |
| +10 | u32 | *(list reply chunk 0)* total file count |
| +14 | u32 | *(list reply chunk 0)* total manifest bytes |

> [!WARNING]
> `+2` is a `u16`. Read as `u8` it corrupts every long frame.

#### Transfer lifecycle

Subtypes form a family per transfer kind: `+0` query, `+1` reply, `+2` proceed, `+3` state, `+4`
release. Media list = `0x00`–`0x04`, thumbnail = `0x20`–`0x24`. `seq` is one counter shared by both.

| subtype | dir | meaning | bytes |
|---:|---|---|---|
| `0x00` / `0x20` | → | query | 33 B list · 48 B thumb |
| `0x01` / `0x21` | ← | data, chunked | |
| `0x02` | → | proceed, answering a state frame | `4a020f10 <seq:u16> 00000000 0000000000` |
| `0x03` / `0x23` | ← | transfer state, before the data and after it ends | `4a030a00 <seq:u16> 00000000` |
| `0x04` / `0x24` | → | release the transfer | `4a040e10 <seq:u16> 00000000 01000000` |

> [!CAUTION]
> A transfer holds a slot until released, and slots are finite. Release every transfer — empty ones and
> abandoned ones included — or the drone stops answering media queries while telemetry keeps streaming.

- A state frame arriving before any data must be answered with `0x02`.
- Reassembly: strip each packet's 8 B transport + 12 B routing header before concatenating; frames straddle packet boundaries.
- Query `@14` (`0x2d` = 45) = page size. Cursor = query `@10–13` `u32-LE`: `1` = newest; an older page passes the oldest `file_index` of the previous page (replayed as the first record — dedup by index). No playback mode, no fresh session.

#### Record — fixed 94 bytes, newest first

| off | size | field |
|---:|---|---|
| +0 | u32 | mtime, FAT/DOS packed |
| +4 | u32 | file size, bytes |
| +8 | u32 | **`file_index`**, packed ([§29](#29-http-media-api-v1--dcf-indexed)) |
| +12 | u16 | duration, whole seconds (`0` = still) |
| +14 | u8 | fps code |
| +15 | u8 | resolution code |
| +19 | u8 | favourite, `1` = starred (byte after the constant `4c 03` pair) |

No filename is transmitted; reconstruct it from the index. Fields past `+19` are unmapped. The code
tables below are the drone's own set, distinct from the Osmo tables in [§1](#1-get-media-list).

#### Frame-rate codes (`+14`)

| code | fps | | code | fps | | code | fps |
|---|---|---|---|---|---|---|---|
| `0x01` | 24 | | `0x0A` | 100 | | `0x14` | 400 |
| `0x02` | 25 | | `0x0B` | 96 | | `0x15` | 8 |
| `0x03` | 30 | | `0x0C` | 180 | | `0x16` | 20 |
| `0x04` | 48 | | `0x0D` | 24 | | `0x18` | 120 |
| `0x05` | 50 | | `0x0E` | 30 | | `0x19` | 96 |
| `0x06` | 60 | | `0x0F` | 48 | | `0x1A` | 72 |
| `0x07` | 120 | | `0x10` | 60 | | `0x1B` | 72 |
| `0x08` | 240 | | `0x11` | 90 | | `0x1C` | 75 |
| `0x09` | 480 | | `0x12` | 192 | | `0x1D` | 15 |
| | | | `0x13` | 200 | | | |

`0x0D`–`0x10` and `0x18`–`0x1B` are decimal-corrected rates (23.976, 29.97, …). `0x17` (8.7 fps) is unmapped.

#### Resolution codes (`+15`)

| code | px | | code | px | | code | px |
|---|---|---|---|---|---|---|---|
| `0x00` | 640×480 | | `0x22` | 3840×1572 | | `0x3B` | 4096×1712 |
| `0x02` | 1280×640 | | `0x23` | 5760×3240 | | `0x3C` | 8192×5456 |
| `0x04` | 1280×720 | | `0x24` | 6016×3200 | | `0x3D` | 5576×2952 |
| `0x06` | 1280×960 | | `0x25` | 2048×1080 | | `0x3E` | 5248×2952 |
| `0x08` | 1920×960 | | `0x26` | 336×256 | | `0x3F` | 2560×1440 |
| `0x0A` | 1920×1080 | | `0x27` | 5120×2880 | | `0x40` | 2560×1920 |
| `0x0C` | 1920×1440 | | `0x2C` | 5440×2880 | | `0x41` | 4096×3072 |
| `0x0E` | 3840×1920 | | `0x2D` | 2688×1512 | | `0x42` | 1080×1920 |
| `0x10` | 3840×2160 | | `0x2E` | 640×360 | | `0x43` | 1512×2688 |
| `0x12` | 3840×2880 | | `0x30` | 4000×3000 | | `0x44` | 5472×3648 |
| `0x14` | 4096×2048 | | `0x32` | 2880×1620 | | `0x45` | 864×480 |
| `0x16` | 4096×2160 | | `0x34` | 2720×2040 | | `0x46` | 720×1280 |
| `0x18` | 2704×1520 | | `0x36` | 720×576 | | `0x5F` | 2688×2016 |
| `0x1A` | 640×512 | | `0x37` | 7680×4320 | | `0x60` | 8192×3424 |
| `0x1B` | 4608×2160 | | `0x38` | 5472×3078 | | `0x61` | 5120×2700 |
| `0x1C` | 4608×2592 | | `0x39` | 8192×4320 | | `0x62` | 1440×1080 |
| `0x1F` | 2720×1530 | | `0x3A` | 8192×3456 | | | |
| `0x20` | 5280×2160 | | | | | | |
| `0x21` | 5280×2972 | | | | | | |

Interlaced, RAW and aspect-only codes in between carry no pixel size and are unmapped.

```python
import struct, datetime

def fat_to_datetime(v):
    date, time = v >> 16, v & 0xFFFF
    return datetime.datetime(
        1980 + (date >> 9), (date >> 5) & 0x0F, date & 0x1F,
        time >> 11, (time >> 5) & 0x3F, (time & 0x1F) * 2)

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

`lighttpd/1.4.55`, TCP 80, no auth. Responses carry `Accept-Ranges: bytes`, `Content-Range` and a
`Last-Modified` matching the manifest's FAT mtime.

```
GET /v1?file_index=<u32>&file_subtype=<S>&file_seg_subindex=<G>
```

- All three parameters are required; a missing one closes the connection.
- `file_seg_subindex` selects a part of a segmented recording (`0` = whole file). It is a per-file value from the record, not a constant.
- A missing file closes the connection with no response (no 404). Every URL other than `/v1` / `/v2` does the same.
- The URL carries no extension; the server probes `.JPG .jpg .MP4 .mp4 .MOV .mov .DNG .dng` for ORIGIN and `.LRF/.lrf`, `.THM/.thm`, `.SCR/.scr` for the rest.

`file_index` packed field:

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

`file_subtype`:

| `file_subtype` | name | content | on-card path |
|---:|---|---|---|
| 0 | ORIGIN | original full-res | `DCIM/<dir>MEDIA/DJI_<n>` |
| 1 | THUMBNAIL | `.thm` | `MISC/THM/<dir>/DJI_<n>` |
| 2 | SCREEN | `.scr` | `MISC/THM/<dir>/DJI_<n>` |
| 17 | AIS | sensor data | `MISC/THM/<dir>/DJI_<n>` |
| 18 | PROXY | `.lrf` low-res proxy | `DCIM/<dir>MEDIA/DJI_<n>` |

Remaining values: 3 CLIP · 4 STREAM · 5 PANO · 6 PANOSCREENNAIL · 7 PANOTHUMBNAIL · 8 TIMELAPSESCREENAIL ·
9 FILE · 10 CUSTOM_DATA · 11 PHOTO_METADATA · 12 USER_CTRL_INFO · 13 JSON · 14 PAYLOAD_WIDGET_JSON ·
15 PROXY_MOOV · 16 ORIGIN_MOOV. The `_MOOV` subtypes serve an MP4's `moov` atom alone; support is
per-model (Neo 2 answers "Not support this subtype yet!" for 3–16).

Extensions per type: `.jpg .dng .mov .mp4 .pano .tiff .log.lz4 .seq .tiff.seq .lrf .thm .scr`.

> [!NOTE]
> On a Mavic 3 a video has a THM; a still has only the original (subtypes 1, 2, 17, 18 close the
> connection). Thumbnail a still by fetching the first 64 kB of the original with `Range` and reading
> the EXIF `APP1` JPEG (starts ~1.5 kB in). The official app builds `/v1` only with `file_subtype=0`
> and fetches renditions by path over `/v2`.

The LRF proxy is ~7× smaller than the original and decodes at 1280×720 — use it for preview and scrubbing.

```python
def pack_file_index(storage, dir_index, file_no):
    return (storage << 30) | (dir_index << 16) | file_no

ORG, THM, SCR, AIS, LRF = 0, 1, 2, 17, 18

def url(index, subtype=ORG, seg=0):
    return "/v1?file_index=%d&file_subtype=%d&file_seg_subindex=%d" % (index, subtype, seg)

url(pack_file_index(0, 100, 554), LRF)   # /v1?file_index=6554154&file_subtype=18&file_seg_subindex=0
```

### 30. Drone status pushes

Pushes are wrapped inside `0x51/0x01` tunnel frames; scan byte-at-a-time with both CRCs verified.
Field layouts are identical to the camera frames ([§19](#19-sd--storage), [§20](#20-battery--power)):

| Cmd Set / ID | field | offset |
|---|---|---|
| `0x0d`/`0x02` | battery percent | `u8 @ 20` |
| `0x0d`/`0x02` | pack voltage, mV | `u16-LE @ 1` |
| `0x0d`/`0x02` | current, mA (signed, `−` = discharging) | `i32-LE @ 5` |
| `0x0d`/`0x03` | per-cell voltages, mV | `u16-LE × 4 @ 2` |
| `0x02`/`0xdc` | SD total / free, MiB | `u32-LE @ 6` / `@ 10` |
| `0x02`/`0xdc` | internal total / free, MiB | `u32-LE @ 24` / `@ 28` |
| `0x02`/`0x80` | active store total / free, MiB | `u32-LE @ 5` / `@ 9` |

### 31. Drone uplink stream

The official app sends `0x02/0x82` (42 B), `0x02/0xdc` (40 B) and `0x04/0x1c` (`38`) at ~860/s to
`0x1c01`/`0x1c04` with sender `0x01`. Not required to open the session or browse media.
