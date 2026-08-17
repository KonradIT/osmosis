# Osmosis — Media & camera DUML commands

An implementation reference for browsing, fetching and controlling media on DJI Osmo cameras (WiFi UDP
datalink + BLE control) — enough to write a client from scratch, in any language. Everything here was
reverse-engineered and verified against real hardware or a capture; where something is inferred rather
than measured, it says so.

Transports: **BLE** = write GATT `fff5`, notify `fff4` (the `[6:8]` msg-id round-trips either way — encode/decode it **little-endian** and the camera echoes the bytes back, so its true endianness is moot for request/response matching).

> ⚠️ **A bare-metal BLE client needs the GATT setup below before the camera will act on anything.** Get it wrong and the camera ATT-acks every write, silently ignores it, and answers nothing — which reads exactly like an unsupported command, so you will hunt the wrong layer for days. Required:
> - **Subscribe the CCCDs of BOTH `fff4` and `fff5`** (0xFFF5's is easy to miss if service discovery is range-limited to the write characteristic).
> - **Write `01 00` to the `fff4` characteristic VALUE** (not its CCCD), with response, after the CCCDs and before any `fff5` traffic, then let it settle ~200 ms.
> - **`fff5` is WRITE_NO_RSP only** (`props=0x36`) — a Write Request on it is a spec violation.
> - **Every app→camera frame needs `cmd_type` `0x40`**, never `0x00`.
> - **MTU 500.** Negotiating 517 makes the camera stop answering *every* request (its NimBLE buffers are sized for 500) — raise the buffer config too or leave it alone.
> - **Wait for the `0x07/0x45` pairing reply before sending the wake.** It can take ~+232 ms, far later than the ~+21 ms a Mimo capture suggests.
>
> **LE encryption/bonding is NOT required**
**Datalink** = UDP (DJI-standard `9004` + TCP-7001 poke first — Nano, Action 5/6, Pocket 3, Pocket 4, Pocket 4 Pro; the **Xtra Edge Pro**
rebrand alone speaks `10004` with no poke), DUML wrapped
in `[8B udp hdr][12B routing hdr][frame]`. Addressing byte `(id<<5)|type`: App `0x02`, Camera `0x01`,
Gimbal `0x03`, Battery `0x05`, WiFi `0x07`, DM368 `0x08`, plus two session endpoints that are **not** the
camera — `0xF0` (type `0x10`, id 7) and `0x1C` (type `0x1C`, id 0). Address the wake commands below to the
camera by mistake and it answers `e0` (reject) and stays asleep; nothing else hints at what went wrong.

---

## Per-model reference

Almost everything in this document is model-agnostic: the DUML frame and its CRCs, pairing, the
`0x00/0x26` → `0x00/0x27` list exchange and its decode, `/v2` HTTP, and the status pushes. What varies
is small but will stop a client dead if assumed: **the UDP port, the handle geometry, which store maps
to which `/v2?storage=` index, and the proxy extension.**

Confidence is marked throughout: ✅ exercised on hardware, ⚠️ partial or single-observation, ❌ known
not to work, `(unconfirmed)` no data.

### Identification and transport

The model id is a `u16-LE` in the BLE manufacturer data under DJI's company id `0x08AA`
([§1 of the protocol map](docs/01-protocol-map.md#1-device-identification-ble-advertisement)). Resolve
by id first: cameras are frequently renamed, and a renamed body has no usable name to match on.

| Camera | model id | BLE local name | Datalink | TCP-7001 poke | WiFi |
|---|---|---|---|---|---|
| Osmo Action (1) | `0x0006` ⚠️ | `OsmoAction` | 9004 | yes | WPA2 |
| Osmo Action 2 | `0x0010` | `OsmoAction2` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | WPA2 `(unconfirmed)` |
| Osmo Action 3 | `0x0012` | `OsmoAction3` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | WPA2 `(unconfirmed)` |
| Osmo Action 4 | `0x0014` | `OsmoAction4` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | WPA2 |
| Osmo Action 5 Pro | `0x0015` | `OsmoAction5Pro` | 9004 | yes | WPA2 |
| **Xtra Edge Pro** | `0x0015` | `XtraEdgePro` | **10004** | **no** | WPA2 |
| Osmo 360 | `0x0017` | `Osmo360` | 9004 `(unconfirmed)` | yes `(unconfirmed)` | **WPA3** |
| Osmo Action 6 | `0x0018` | `OsmoAction6` | 9004 | yes | WPA2 |
| Osmo Nano | `0x0019` | `OsmoNano` | 9004 | yes | WPA2 |
| Osmo Pocket 3 | `0x0020` | `OsmoPocket3` | 9004 | yes | WPA2 |
| Osmo Pocket 4 | `0x0021` | `OsmoPocket4` | 9004 | yes | WPA2 |
| Osmo Pocket 4 Pro | `0x0022` | `OsmoPocket4P` | 9004 | yes | WPA2 |
| Mavic 3 | `0x0070` | *(varies)* | **9003** | **no** | WPA2 |
| DJI Neo 2 | `0x007e` | *(varies)* | **9003** | **no** | WPA2 |

Where a body behaves differently from the rest of the line:

- **Osmo Action (1)** speaks the older [index-based list](#1-get-media-list) and addresses media by
  numeric index, not by path.
- **Osmo Action 4** and the **Osmo 360** pair and hand over credentials, but their AP never appears, so
  neither reaches the datalink. The 360 is the only body advertising an extra `fff7` characteristic.
- **Mavic 3** and **Neo 2** are aircraft: `udp/9003`, no poke, and a `0x51` session-open before anything
  ([§27](#27-session-open-0x51--required-before-anything-else-mavic-3),
  [§27a](#27a-neo-2--the-same-transport-a-different-unlock)).

- **The Xtra rebrand shares the DJI model id.** An Xtra Edge Pro is an Action 5 Pro and advertises
  `0x0015`, but its firmware moves the datalink to **10004 with no poke**. Distinguish it by its own OUI
  `EC:9E:EA`, not by id or name. It also **answers nothing on camera-control cmdset `0x02`**
  ([§10–17](#camera-control)) while still pairing, waking and streaming status normally.
- **Two advert formats are in use.** The Pocket 4 carries a classic model byte; the Pocket 4 **Pro**
  uses the newer form where a flag bit at payload byte 5 marks a 16-bit product type at bytes 10–11
  (`218` = Pocket 4 Pro). A client reading only the classic field sees `0x0000` for the Pro.
- Ports marked `(unconfirmed)` are the fallback for an unrecognised body (9004 + poke + WPA2), not a measurement.
  Retrying the alternate config (`9004`+poke ⇄ `10004`/no-poke) covers a wrong guess.

### Media layout

| Camera | Path shape | Handle base / step | Store → `/v2?storage=` | Proxy ext | Star byte `@+9` |
|---|---|---|---|---|---|
| Osmo Nano | `DCIM/DJI_001/DJI_…_D` | internal `0x40100000` / `0x40` | internal → **1**, dock SD → **0** | `.LRF` | ✅ real flag, `0`/`1` |
| Osmo Pocket 4 | `DCIM/DJI_001/DJI_…_D` | internal `0x40100000` / `0x40` | internal → **1** | none listed | all `0` (nothing favourited) |
| Osmo Pocket 4 Pro | `DCIM/DJI_001/DJI_…` | `0x00100000` / `0x40` ⚠️ | ⚠️ 45 → **0**, 1 → **1** | `(unconfirmed)` | `(unconfirmed)` |
| Osmo Action 5 Pro | `DCIM/DJI_001/DJI_…_D` | SD `0x00040000`, internal `0x40040000`, step `0x10` | SD → **0**, internal → **1** | `.LRF` | `(unconfirmed)` |
| Xtra Edge Pro | `DCIM/CAM_001/CAM_…_D` | SD `0x00040000`, internal `0x40040000`, step `0x10` | SD → **0**, internal → **1** | `.XRF` | ❌ `44`/`48` — a length |
| Osmo Action 6 | `DCIM/DJI_001/DJI_…` | `0x4010xxxx` | internal (only store) → **1** | `(unconfirmed)` | `(unconfirmed)` |
| Osmo Pocket 3 | `DCIM/DJI_001/DJI_…_D_OP3` | `0x00040000` / `0x10` | microSD (only store) → **0** | `(unconfirmed)` | ❌ `48` — a length |

- **Path-addressed bodies only.** Index-addressed devices have no paths, handles or stores to tabulate:
  the Osmo Action 1 is in [§1](#1-get-media-list) ("Parsed — index-based") and the drones in
  [§28](#28-get-media-list-drone).
- **Fit `base + seq × step` from the manifest's own handles**, per store, rather than hardcoding a row
  above. Geometry is per body *and* per store, and the Pocket 4 shows why the model name is no guide:
  it uses the **Nano's** `0x40` step, not the Pocket 3's `0x10`.
- **The proxy is never listed in the manifest.** Every body above decodes with zero proxy paths; the
  preview URL is built by swapping the extension on the media path (`.LRF`, or `.XRF` on the Xtra).
  A proxy *size* is available at `marker + 30`.
- **Naming does not identify the family.** Only the Xtra rebrand writes `CAM_…`; genuine Action and
  Pocket bodies all write `DJI_…`. Custom Folder/File prefixes decode identically
  ([§1](#1-get-media-list)), so never parse a name to decide anything.
- The **manifest count header reads `0` on the Action 5 and 6** — count records instead. Nano, Xtra,
  Pocket 3 and Pocket 4 all write a true count.

### Storage frame and power, per body

| Camera | `0x02/0xdc` shape | Notes |
|---|---|---|
| Osmo Nano | 22 B, `stores=1` | ⚠️ can report `0/0` with a card in and files on internal |
| Osmo Pocket 3 | 22 B, `stores=1` | the 22 B body is why the decode gate is `>= 22`, not `>= 32` |
| Xtra Edge Pro / A5P | **40 B**, `stores=2` | e.g. `60776/58151` SD + `48980/44807` built-in |
| Osmo Action 6 | `(unconfirmed)` | `@6/@10` = `121785/109748` MiB, matching its own screen |
| Osmo Pocket 4 | **40 B**, `stores=2` | two-store body **even with no card** — first block reads `0/0` |

- **Dock and charging bytes (`@27`, `@32`) were mapped on a Nano and are not portable.** A Pocket 4
  reports `docked` set while discharging and not charging, so treat those two fields as Nano-specific
  until confirmed elsewhere. Voltage, current and percent are consistent across bodies.

### Behavioural quirks worth knowing before debugging

| Camera | Quirk |
|---|---|
| Osmo Nano | Dock SD reads cut a long HTTP transfer around **757–774 MB**; resume and continue ([§29](#29-http-media-api-v1--dcf-indexed) applies the same way to `/v2`). Internal streams >1.4 GB uncut. |
| Osmo Nano | Reads the dock SD **only when seated lens-away from the dock screen**; the other way round it answers the SD query with a `start` frame and no data. |
| Osmo Pocket 3 | Answers `e0` to the `0x53/0x10` wake, yet its AP still comes up via the `0x00/0x2b` session — the wake is belt-and-braces here. |
| Osmo Pocket 4 | Folds its gimbal and shows the album screen when playback is held — yet `0x04/0x05` telemetry keeps streaming at the same rate throughout. The telemetry says nothing about the motors. |
| Osmo Pocket 4 | May need **two `0x02/0x0c` attempts** before it confirms playback. |
| Osmo Pocket 4 / 3 | Seen holding a session from a previous connection: the handshake succeeds, the peer answers on its own sequence channel, and the media query is never answered. Re-handshake, or power-cycle. |
| Action-family bodies | HTTP `404` and `500` are **transient** during a long transfer and do not mean the file is missing. |

---

## Media

### 1. Get media list
- Cmd Set: `0x00`
- Cmd ID: `0x26`  (response `0x00/0x27`)
- Dir / transport: App → Camera(`0x01`), datalink
- Payload (page 1): `4a002a10 01000000 0000 01000000 2d00 0d0100 ffffffffffffffff 0001000000000000 000000`
- Response: chunked `0x00/0x27` frames, each payload = `[10B sub-header][chunk]`. **Strip the sub-header, concat chunks in arrival order** → the manifest.

| sub-header | field |
|---|---|
| `+0` | `0x4A` |
| `+1` | subtype: `0x04` stream start · **`0x01` data chunk** · `0x03` stream end. Only `0x01` carries manifest bytes; the other two are 10 bytes of sub-header and nothing else. |
| `+4` | **the request counter, echoed from byte 4 of the `0x00/0x26` that asked for this chunk** — see [per-store split](#two-stores-answered-separately-and-labelled-for-free) |
| `+6` | `u16-LE` seq (restarts per page, so concatenate in arrival order, never seq-sorted) |

⚠️ **Select chunks by the DUML command (`0x00/0x27`), not by the `4A 01` payload prefix.** The 11-byte
frame header is `[55][len:2][crc8][target:2][id:2][type][set][cmd]`, so the command is available without
inspecting the body. `4A 01` alone also matches parameter-subscription pushes ([§8](#8-subscribe-param--the-settings-surface-over-ble)),
which will corrupt the manifest of any client subscribed to more than a handful of parameters.
- DUML example: <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>

#### Paginate the full library

One `0x00/0x26` returns only the **newest ~45 files** (the `2d` = 45 count at payload byte 14). To reach older files the request carries a **cursor = a 4-byte little-endian file *handle* at payload bytes 10-13** — the same handle the record exposes for delete ([§2](#2-delete-media), `u32-LE @ head`). Two things make it page:

1. **Enter playback mode first** — the list only paginates in playback; without it a query re-returns the newest 45.
   - Cmd Set / ID: `0x02` / `0x0c`  ·  App → Camera(`0x01`), datalink
   - Payload: `01 01 00 01` = enter playback · `01 01 00 00` = leave
   - DUML example (enter): <https://b3yond.d3vl.com/duml/#55110492020100a040020c01010001b63b>
2. **Per page send three frames** — `query(cursor=1)` → `trigger` → `query(cursor=pageCursor)`. The **second query's cursor selects the page**; the first (`cursor = 0x00000001`) and the trigger (`4a040e10`) prime the stream. Give the two queries **different counters at byte 4** (e.g. 1 and 2) — that is what lets the single reply stream be split back into per-store answers.

| page | cursor @ bytes 10-13 (u32-LE) | returns |
|------|-------------------------------|---------|
| newest | `0x00000001` — `01 00 00 00` (or the `0x40000001` sentinel) | newest ~45 |
| next older | the **oldest video handle** of the previous page (`0x40xxxxxx`, e.g. `80 2b 10 40` = `0x40102b80`) | next ~45, older |
| … | repeat with each page's oldest video handle | until a page adds nothing new |

- Only handles **`≥ 0x40000000`** (video records) advance the cursor — a stray low-namespace handle (a `0x0010xxxx` photo) is skipped so it can't jerk the cursor to the bottom and stall paging.
- Consecutive pages overlap by exactly the one boundary file, so **dedup by media path** (≈ 44 new per page).
- **End of the library = a short page.** Ask for 45 (`0x2d` at byte 14) and count the records that come
  back: fewer than 45 means there are no older files. Mimo instead reads a per-record `isPageLastFile`
  flag, but that flag sits at **no fixed marker-relative offset** — comparing a known-final page against a
  known-continuing one separates them at no position — so the record count is the reliable test.
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
| ⭐ starTag | `u8 @ [ff\|fe] 19 06 + 9` | favourite flag — **Nano only**; test `== 1`, never `!= 0` (see below) |

##### Two stores answered separately and labelled for free

**The cursor's top bit is the store selector, and the response counter hands the answer back labelled.**
Cursor `0x00000001` enumerates the SD card, `0x40000001` the internal store — DJI's own `FileLocation`
(`SD_CARD=0`, `INTERNAL_STORAGE=1`), which is the *same integer* `/v2?storage=` wants. Send the two
queries under **different counters at byte 4**, and every `0x00/0x27` chunk echoes that counter at
sub-header byte 4, so one collected blob splits cleanly into the two stores it contains:

```
-> 0x00/0x26  byte4=1  cursor=0x00000001     "list the SD card"
-> 0x00/0x26  byte4=2  cursor=0x40000001     "list internal"
<- 0x00/0x27  sub-header byte4=1  …          these chunks are the SD answer
<- 0x00/0x27  sub-header byte4=2  …          these chunks are the internal answer
```

This costs no extra round trip and no HTTP `HEAD`. Measured: Nano + dock SD → `SD 1, internal 38`;
Edge Pro → `SD 31, internal 45`.

Fall back to the handle rule below in the two cases where the split cannot be trusted: a camera that
**doesn't echo the counter**, and one that answers **both queries with the same list** (a single-store
body, where there is nothing to attribute). An empty slice is normal — it means that store held nothing.

**Fallback — the handle's `0x40000000` bit:** set → internal → `storage=1`; clear → SD → `storage=0`.
It is **not** the manifest list ordinal; a single-store camera's one list is group 0 yet can mount at
`storage=1`. Handle bases also drive the burst-expand and favourite queries, so fit `base + seq × step`
per store from the manifest's own handles rather than hardcoding a body's numbers:

| camera | store | handle base / step | `storage=` | source |
|--------|-------|--------------------|-----------|--------|
| Osmo Nano | internal | `0x40100000` / `0x40` | `1` | `nano_45.bin` |
| Osmo Pocket 4 | internal | `0x40100000` / `0x40` | `1` | tester log, 2026-08-08 |
| Action 6 | internal | `0x4010xxxx` | `1` | |
| Xtra Edge Pro / Action 5 Pro | SD | `0x00040000` / `0x10` | `0` | |
| Xtra Edge Pro / Action 5 Pro | internal | `0x40040000` / `0x10` | `1` | `xtra_13.bin` |
| Pocket 3 | microSD (only store) | `0x00040000` / `0x10` | `0` | `op3_15.bin` (`0x00040010`–`0x000400f0`) |

The Pocket 3 is the one that looks like an outlier and isn't: the rule was never "single store → 1", it
is *which physical store*. Its one store is a microSD → `SD_CARD` → 0, while the Nano's and Action 6's
single store is internal → 1. Shipping `storage = list ordinal` instead blanked every Nano thumbnail.

**Two stores in one blob = two lists back to back** — **SD first, then internal** (query order), each
opening with its own `[u32-LE count][u32-LE size][u32-LE ts]…` header. The leading count covers only the
*first* list. Proven by dumping the same camera with and without a card: the no-card manifest is
byte-identical to the mixed manifest's second list.
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
| `fileName` | String | e.g. `DJI_…_D.MP4` — the `0d` field |
| `fileType` | enum `MediaFileType` | photo/video/… → the extension category |
| `fileSize` | **Long** | the real byte size — **mapped**: `u32-LE @ marker − 12` |
| `duration` | **Long** | video length (ms) |
| `frameRate` | enum `VideoFrameRate` | **mapped**: `u8 @ marker − 2`; the fps rational carries the same value |
| `resolution` | enum `VideoResolution` | **mapped**: `u8 @ marker − 1` (table below) |
| `date` | `DateTime` | capture time |
| `starTag` | enum | favourite / marked flag — **mapped**: `u8 @ [ff\|fe] 19 06 + 9` |
| `orientation`, `cameraOrientation` | enum | rotation |
| `photoType`/`videoType`/`panoType`, `videoEncodeType`, `videoSpeedRatio`, `timeLapseInterval` | enum/int | mode metadata |
| `dirIndex`, `fileIndex`, `subIndex`, `segSubIndex`, `fileGroupIndex` | int | DCF indices |
| `proxyInfo`, `hasProxy`, `EXIFInfo` (`physicalPathInfo`), `dcfInfo` | nested | proxy/exif/DCF; the `DCIM/…`,`MISC/…` strings live in these nested `physicalPath`s |

##### Enum value tables (mined from the DJI app dex — for decoding the record's int fields)


**Star / Heart / Favorite** — the byte at `[ff|fe] 19 06` + 9 is DJI's `MediaFileStarTag`: `0 = NONE`,
`1 = TAGGED`. **Read it strictly as `== 1`.** On the Nano it is a real flag — `nano_delete.bin` splits
**19 unstarred / 26 starred** — but on the Action family that byte is a *length* and never 0 or 1:

| fixture | camera | byte @ +9 |
|---|---|---|
| `nano_delete.bin` | Nano | `0` ×19, `1` ×26 — the flag |
| `nano_45.bin` | Nano | `0` ×45 (captured before anything was favourited) |
| `xtra_13.bin` | Xtra Edge Pro | `44` ×13 |
| `xtra_delete.bin` | Xtra Edge Pro | `44` ×41, `48` ×4 |
| `op3_15.bin` | Pocket 3 | `48` ×15 |

A `!= 0` test therefore marks **every** file on an Action-family body as starred. Writing a favourite
works on those bodies ([§3](#3-favorite--star-media)); only the read-back offset is unmapped there, so a
client should show the star on the Nano and treat it as unknown elsewhere rather than trust `+9`.

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
- **Handle** — u32-LE at the record head, located by anchoring on the constant record marker `03 ff 19 06` (at head + 8, so `handle = u32 @ marker − 8`). Nano (361 B records) `0x40100000 + seq × 0x40`; the Action family — Xtra Edge Pro, Action 5/6, Pocket 3 (272 B records) — `base + seq × 0x10`, base `0x40040000` internal / `0x00040000` SD. **Fit base and step from the handles the manifest already exposes** rather than hardcoding either: both are per camera *and* per store. Anchoring on the marker is also what makes this safe — searching for a `0x40`-aligned dword finds the right value on a Nano and the wrong one on an Xtra, which the camera rejects with `0xd6`. (Naming doesn't track the family: only the Xtra rebrand writes `CAM_…`, while genuine Action/Pocket units use `DJI_…`.) Photo records lack the marker → non-deletable (fail-safe).
- ⚠️ **Reject duplicate handles.** A fitted base/step can collide when a manifest mixes stores or a record decodes short. Since the delete is irreversible, treat a handle held by more than one file as non-deletable for *all* of them rather than choosing between them.
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
- Example replies: a 2-mark clip → `4000, 7000` ms; a 3-mark clip → `1000, 3000, 5000` ms. Handles: Xtra `0x4004xxxx`, Nano `0x4010xxxx` (same command). The UI that consumed this is parked on branch `highlights`.

---

## Datalink session (sent before the list, over UDP)

### Holding playback mode for a whole browse session

Playback is a **camera-wide mode**, not a per-command flag. Pagination requires it, some commands
require it, and while it is held a gimballed body stops filming. The camera **drops the mode about a
second after it is set unless the app keeps beating**, so entering it is not enough — it has to be held.

| | frame | when |
|---|---|---|
| enter | `0x02/0x0c` payload `01 01 00 01` | once, after registration — **not on a Pocket 3**, see [§13b](#13b-pocket-3-playback-entry-0x010x01) |
| beat | `0x00/0x88` sub-cmd `0x17` (14 B, ASCII `APP` at bytes 5-7) | every ~1 s, all session |
| re-assert | `0x02/0x0c` payload `01 01 00 01` | every ~10 s (optional, idempotent) |
| leave | `0x02/0x0c` payload `01 01 00 00` | teardown only |

```
1. handshake + register                              §4–§7
2. send 0x02/0x0c 01 01 00 01
3. wait up to ~900 ms for the 0x02/0x0c reply
      no reply -> resend, up to 3 attempts
4. loop, ~1 Hz, until teardown:  0x00/0x88 sub-cmd 0x17
5. every ~10 s:                  0x02/0x0c 01 01 00 01
6. teardown only:                0x02/0x0c 01 01 00 00
```

**Wait for the reply at step 3.** The camera does not always answer the first enter — a Pocket 4 took
two attempts. The official app also sends the enter twice, 0.6 s apart, on re-entry.

⚠️ **An answered enter does not mean the mode changed.** A Pocket 3 replies `status 0` to
`0x02/0x0c` and stays in capture — the reply says the command was received, nothing more. Confirm
on bit 30 of `0x02/0x80` ([§20b](#20b-camera-state-flags-0x020x80)), which is the camera's own
answer, and treat that bit as the definition of "held".

**The beat is mandatory.** Without a ~1 Hz frame the mode is dropped about a second after it is set; with
one it holds indefinitely. The distinctive symptom of a missing beat is playback appearing, lasting ~1 s,
vanishing, and reappearing on the next re-assert.

⚠️ **Do not poll `0x02/0x8E` while holding playback.** It looks like a heartbeat — the official app sends
it ~15 Hz over BLE — but it is a keyed parameter GET ([§14](#14-camera-parameters)), and on the datalink
it takes the camera **out of** playback about a second later.

The restriction is on polling it *during* playback, not on the command. Captures of the official app
performing the **same** operation show two different strategies, per model: on a Nano it enters playback
once and sends `0x02/0x8E` zero times in 49 s; on an Xtra Edge Pro it sends 486 of them and never enters
playback at all. Either is coherent — what breaks the mode is doing both at once.

**Hold the mode; do not toggle it.** Leaving after each operation makes the mode flap and races anything
that assumes it is held. Mimo enters once and holds for 128 s, leaving only when the user closes the
album:

```
 1.10s  APP->CAM  02/0c  01010001      enter
 1.33s  CAM->APP  02/0c  00            confirmed
        ... 48 s of browsing, thumbnails and a DELETE — no further 0x02/0x0c at all ...
```

**The ~10 s re-assert is optional.** The mode stays on its own; the re-assert only covers being knocked
out of it by something outside the protocol, such as a button press on the body.

**What playback does *not* gate:** status pushes (`0x02/0x80`, `0x02/0x82`) arrive unprompted once
registered — 493 and 480 times in that 49 s session — so battery and storage need no polling either way.
Neither does the **first** page of the media list: only pagination needs playback, so a client that shows
just the newest 45 files can skip this section entirely.

**Confirming the camera really entered playback:** read bit 30 of the flags word in `0x02/0x80`
([§20b](#20b-camera-state-flags-0x020x80)). Do **not** infer it from gimbal telemetry
([§20a](#20a-gimbal-position-telemetry)) — that rate is constant whatever the mode.

**Alternative beat:** Mimo sends the `0x17` announce only twice (t=0.115 s, 0.595 s) and then beats
`0x00/0x88` sub-cmd **`0x1a`** (`1a 00 00 00 01`, 5 B) at ~1 Hz instead. Untested here; `0x17` at 1 Hz
holds the mode on every camera tried.

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
`0xFFFF` and is *independent* of the camera. `ackSeq` is the **last of the app's own seqs the camera echoed
back** — it lags `ownSeq` by 8–150, and **stays in the app's seq space**. Separately, an ACK packet (`0x04`, seq 0)
carries `[camSeq][camSeq]` to acknowledge the camera's telemetry stream.

**Do not** put the camera's telemetry seq in a command's `ackSeq`: the camera floods telemetry ~10×
faster than the app's commands and its seq wraps to a different phase, so an `ackSeq` tracking it diverges
from `ownSeq` and the receiver window **silently drops writes** (reads stay lenient). Correct value:
**`ackSeq = ownSeq − 8`** (the previous command seq).

**Inline commands:** the keep-alive thread owns the socket, so a command that needs a reply must be
**queued** for that thread — see `CameraSession.runCommand` / `runManifestQuery`. Skip the empty-payload
transport ACK the camera sends *before* the real reply. Playback mode is held for the whole browse
session (some inline reads/writes need it), not entered per-fetch.

⚠️ **A registered session stops accepting inline WRITES after ~40–70 s, and the two cameras disagree:**

```
Nano       ok at 45 s, 57 s, 66 s   ·  no reply at 74 s, 94 s, 124 s, 142 s
Edge Pro   no reply at 51.6 s
```

**Reads are unaffected** on both — a pagination query at 82 s in the Nano session returned normally. So a
long browse keeps listing happily and then silently drops the next delete or favourite, with no error.
The workaround is to track the session's age and **re-register before a write** once it exceeds a
threshold below the shortest observed failure (40 s covers both cameras above). The underlying cause is
unidentified, so treat the threshold as empirical.

Note this contradicts [§27](#27-session-open-0x51--required-before-anything-else-mavic-3), where the
sequence window is *not* enforced — that measurement is from an aircraft. Cameras enforce something here.

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

Cmd Set `0x02`, App → Camera (`0x01`). **App→camera frames in this cmdset use `cmd_type` `0x40`** (request; `0xC0` = response), and a `0x00` frame in cmdset `0x02` is silently dropped before the dispatcher. That is **not** a rule about the whole protocol: `0x01/0x01` ([§13b](#13b-pocket-3-playback-entry-0x010x01)) is sent with `cmd_type` `0x00`, expects no reply, and is what puts a Pocket 3 into playback. The upstream repos' `0x02/0x20`/`0x21` record commands answer `e0` (unsupported) on Osmo firmware; **`0x02/0x02` is the record control** ([§11](#11-start-recording)/[§12](#12-stop-recording)).

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
| `0x0c` | Panorama | confirmed on an Osmo Pocket 3 (capture, 2026-08-17) |

- **Verified end to end on an Osmo Pocket 3** (capture, 2026-08-17): a labelled run through Photo, Panorama, SuperNight, SlowMo, TimeLapse and HyperLapse set `05 0c 28 00 02 0a` in that order, and every one came back at `@57` within 0.2–0.9 s.
- **The enum is sparse and unordered — table it, never compute it.** The camera's on-screen carousel order is Video → Photo → TimeLapse → HyperLapse → SuperNight → SlowMo, which is *not* the numeric order.
- **Readback:** the camera echoes the current mode in its `0x02/0x80` push at **byte `@57`**, same encoding — so mode is both settable and observable, and a remote stays in sync when the user changes it on the camera.

### 13b. Pocket 3 playback entry (`0x01/0x01`)
- Cmd Set / ID: `0x01` / `0x01` (`SPECIAL Control`) · App → Camera(`0x01`) · **`cmd_type 0x00`** · no reply

⚠️ **`0x02/0x0c` does not put an Osmo Pocket 3 into playback.** It answers `status 0` and changes
nothing: the screen stays on the live view and the gimbal stays unfolded. A capture of the official app
on a Pocket 3 (2026-08-17) contains **no `0x02/0x0c` at all**. What it sends instead, in the 1.35 s
around the transition and nowhere else in a 128 s session:

| payload | count | when |
|---|---|---|
| `03 00000000 04000000 07 01` | ×6 | t=126.74–126.99, ~20 Hz |
| `00 00000000 04000000 04 01` | ×22 | t=127.04–128.09, ~20 Hz |

The camera's playback bit ([§20b](#20b-camera-state-flags-0x020x80)) sets at t=127.40, 358 ms into the
second payload, and the gimbal folds. Two fields differ between the payloads — byte 0 (`03`→`00`) and
byte 9 (`07`→`04`) — and which of them carries the mode is **not yet established**; the pair is
reproduced verbatim because that is what the evidence supports.

Notes that matter for a client:
- It is **repeated at ~20 Hz while switching**, not sent once, and it is `cmd_type 0x00` with no reply —
  so there is nothing to wait for. Confirm the transition on the state bit, never on an ack.
- Nothing else in the capture uses cmdset `0x01`, so this is not a general-purpose channel that happens
  to carry a mode: on this body it appears only to change into playback.
- The Nano and Xtra *do* enter playback on `0x02/0x0c` (verified — the bit flips 200 ms later), so this
  is per model, and a client should verify with the bit rather than assume either route.

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
- The camera clock snaps to the sent value and recorded file timestamps follow. Send it right after
  registration on every connect — a camera that has been off for a while will otherwise stamp files wrong.
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
- **Byte 2 = store count**, and byte 5 mirrors it. One `[total][free]` block per store. Measured
  payload lengths: **22 B single-store**, **40 B two-store** — so gate the decode on `size >= 22` for
  the first block and `>= 32` for the second, never on an exact length. (A `>= 32` gate on the whole
  frame dropped the Pocket 3's 22 B body and it never reported storage at all.)

| offset | type | field |
|--------|------|-------|
| `@2`  | `u8` | store count (`1` or `2`) |
| `@6`  | `u32-LE` | first store **total** MiB (`0` = no card) |
| `@10` | `u32-LE` | first store **free** MiB |
| `@24` | `u32-LE` | built-in **total** MiB (absent on a 22 B frame → report `0`) |
| `@28` | `u32-LE` | built-in **free** MiB |
| `@32`–`@39` | | present on a 40 B body, **unmapped** (one Xtra reads `34216`, `0`) |

- **Card present = first-store total > 0**, not a flag byte. Byte 0 is *not* an "SD inserted" bit: it
  reads `0x11` on a card-less Xtra and `0x00` on a card-less Nano, so it tracks something else entirely.
- Verbatim fixtures:
  ```
  Nano  22 B  00 12 01 00 00 01 | e7ed0000 09e10000 | …      count=1, 60903/57609 MiB
  Xtra  40 B  11 12 02 00 00 02 | 00000000 00000000 | … 0101 | 54bf0000 16bf0000 | a8850000 00000000
                                   ^ no card                    ^ 48980/48918 MiB built-in
  ```
- Examples: an Action 6 reads `@6/@10` = 121785/109748 MiB (= its on-screen 118.9/107.2 GB); an Action 5
  Pro and its Xtra rebadge both report 48980 MiB built-in; a Pocket 4 reports real capacity too.
- ⚠️ **A Nano can report `0/0`** with a card in and files on internal, in an otherwise well-formed 22 B
  body — the same shape carries real numbers in other captures. Don't read a zeroed frame as "no
  storage"; keep the last non-zero values until a later push supersedes them.

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

### 20a. Gimbal position telemetry
- Cmd Set / ID: `0x04` / `0x05` (`GIMBAL GetPushParams`)  ·  App ← Camera, continuous push

The payload layout is unmapped.

⚠️ **Its arrival rate is not a motion signal, and cannot be used as one.** The rate is a fixed
heartbeat: a Pocket 4 that had physically folded its gimbal on entering playback went on pushing at
9.3/s, and a camera that had just *refused* playback pushed at 10.0/s — the same reading in both
states. Inferring "the motors are running" from it reports motion in every state, and reading playback
state that way sent one investigation down the wrong path entirely. To know whether the camera is in
playback, read the flags word instead ([§20b](#20b-camera-state-flags-0x020x80)).

### 20b. Camera state flags (`0x02/0x80`)
- Cmd Set / ID: `0x02` / `0x80` (`GetPushStateInfo`)  ·  App ← Camera, continuous push, unprompted

The payload opens with a **`u32-LE` flags word at offset 0**. Bits confirmed:

| bit | mask | meaning |
|---|---|---|
| 0 | `0x00000001` | connected |
| 18 | `0x00040000` | photo capture enabled (**0** when enabled) |
| 28 | `0x10000000` | tracking mode |
| 29 | `0x20000000` | hyperlapse mode |
| **30** | **`0x40000000`** | **in playback mode** |

Bits 15–16 carry a firmware-error code and 22–23 an encryption status; both are enums, not flags.

**Bit 30 is the only reliable way to know the camera is in playback.** Entering playback
([§13](#13-playback-mode)) is a command whose reply says the command was *received*, not that the mode
changed — a body that answers and then stays in capture is indistinguishable from one that complied.
This bit is the camera's own answer, and it arrives without being asked. Verified on a Nano: `0`
before the mode change, `1` two hundred milliseconds after, with the body's screen agreeing.

The same push carries the active store's capacity — `u32-LE` MiB total at byte 5, free at byte 9 —
so a client that reads this frame needs no status polling at all.

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
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval required. Approval then arrives as a **`0x07/0x46` request** (flags `0x40`), not a response — it must be ACKed like any other request, and it is the "go" signal.

**Both fields matter, and they gate different things.**

| field | camera | drone |
|---|---|---|
| token | `"osmo"` — any value pairs | **`"DJI FLY"`** — anything else pairs but the WiFi getters return nothing |
| identifier | 32 chars; the generic one is accepted | 32 chars; **this is what the device remembers** |

The **identifier is the key a device stores its approval under** — proven on a Mavic 3 by rotating it: the same aircraft that had been re-pairing silently (`0x45` → `0x01`) for days answered `0x45` → `0x02` and demanded confirmation the moment it saw a string it hadn't approved. Present a known identifier and it skips the approval entirely.

Two consequences:
- An app should mint **one identifier per install and persist it**, as DJI Fly does. A constant shared across installs is silent only for whoever's device already approved it; a fresh one per launch prompts every time and burns a remembered slot each time.
- **Send the same identifier on retries.** `fff5` is write-without-response, so a first write can drop; a retry carrying a different identifier reads as a second app asking to pair.

**Confirming, on hardware without a screen.** A camera prompts on its own display. A drone flashes its LEDs and waits for a power-button hold — 2 s on most models, 3 s on the newest, while the **Mini 3** has no hold at all and instead needs three quick presses to enter QuickTransfer mode. Full sequence measured on a Mavic 3:

```
11:44:50.447  -> 0x07/0x45  SetPairingPIN(token="DJI FLY", id="c7f10a83…")
11:44:51.894  <- 0x07/0x45  [00 02]   approval required — LEDs start chasing
11:45:01.886  <- 0x07/0x46  [01]      (request, flags 0x40) — after the button hold
11:45:03.497  <- 0x07/0x0e            passphrase released
```
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
- Quirks: **give it a beat after GetWifiSsid** (`fff5` is write-without-response; Mimo actually spaces these only a few tens of ms — see [Waking a sleeping camera](#waking-a-sleeping-camera) — so ~500 ms is just a safe margin). The Nano may not surface a password here — fall back to its saved credentials.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 26. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>

---

## DJI Drone QuickTransfer media offload

> **Everything below is the Mavic 3 family** (Mavic 3, Classic, Pro) unless a heading says otherwise —
> that is the only aircraft this has been made to work on end to end.

**"A drone" is not one thing.** A Neo 2 shares the transport and the credential path with a Mavic 3 and
then diverges completely at the point of unlocking the link, so the two are tracked separately here:

| | Mavic 3 | Neo 2 |
|---|---|---|
| BLE pair, `DJI FLY` token | ✅ | ✅ |
| WiFi creds over `0x07/0x07` + `0x07/0x0e` | ✅ | ✅ |
| Datalink port | ✅ udp/9003 | ✅ udp/9003 |
| Handshake | ✅ 9-byte reply | ✅ **15-byte** reply ([§27a](#27a-neo-2--the-same-transport-a-different-unlock)) |
| Serial in the `0x51/0x13` beacon | ✅ tag `0x11` | ✅ **tag `0x24`** |
| Answers `0x51/0x02` session-open | ✅ | ❌ **ignores it** |
| Media list | ✅ | ❌ never reached |

So a Neo 2 gets as far as a live, authenticated link and then serves nothing. It is *not* a `/v1`-vs-`/v2`
question — no manifest is ever reached, and §29 has never been exercised on one.

A drone runs the same DUML stack as an Osmo, with four differences that break a camera client outright:

| | Osmo camera | DJI drone |
|---|---|---|
| Pairing token | `osmo` | **`DJI FLY`** — any other token pairs but yields **no WiFi creds** |
| Datalink | UDP `9004` + TCP-7001 poke (Xtra: `10004`) | **UDP `9003`, no poke**, bind local port `9003` (symmetric) |
| Session | handshake → registration → commands | handshake → **`0x51` session-open** ([§27](#27-session-open-0x51--required-before-anything-else-mavic-3)) — Mavic 3 only; a Neo 2 unlocks differently ([§27a](#27a-neo-2--the-same-transport-a-different-unlock)) |
| Media addressing | paths, `/v2?storage=N&path=…` | **DCF indices**, `/v1?file_index=…` ([§29](#29-http-media-api-v1--dcf-indexed)) |
| Registration | `0x00/0x81`, `0x00/0x88`, `0x03/0xda`, param subs | **none** — go straight to commands |

Addressing byte is unchanged: App `0x02`, Camera `0x01`. The `0x51` channel uses its own endpoints
(`0xee` app, `0xe9` drone) outside the `(id<<5)|type` scheme.

### 27. Session open (`0x51`) — required before anything else *(Mavic 3)*

A Mavic 3 answers **no command at all** until this completes. Before it, it emits ~2 DUML frames/s of empty
keepalive; one second after, ~1200 frames/s and every command works. **This exchange is Mavic-specific —
see [§27a](#27a-neo-2--the-same-transport-a-different-unlock) before assuming it generalises.**

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

- **Serial** = a run of uppercase alphanumerics in the drone's own `0x51/0x13` beacon — 20 characters on both aircraft seen so far. **Do not key on the tag byte in front of it:** a Mavic 3 uses `0x11`, a Neo 2 uses `0x24`, and anchoring on `0x11` silently rejects the Neo entirely. Find it by shape, remember the tag, and echo that tag back in steps 4–5 rather than a literal `0x11`.
- **Trailing bytes** `39fdb2ae 02 <ctr> 00 00 00 79102e9b 01 00×8` — **`ctr` (byte 5) must increase on every `0x51` frame sent**. A repeated or decreasing value is dropped as a replay, with no reply at all.
- **Outer DUML message id** is a per-frame counter from `1`, not a constant.
- DUML example (`0x51/0x02` open, outer frame): <https://b3yond.d3vl.com/duml/#553504683be90100005101551204c7eee97c004051020501040100619639fdb2ae020100000079102e9b010000000000000000f340>

Two fields that look like flow control but are not: the routing header's `r0-1` on a **received** packet
is not a running ack (it repeats the handshake channel and only moves when a reply lands), and the
sequence window is not enforced — the reference app runs ~1600 packets ahead of it.

### 27a. Neo 2 — the same transport, a different unlock

Everything up to and including the datalink works. The aircraft pairs on the `DJI FLY` token, hands over
SSID and passphrase on `0x07/0x07` / `0x07/0x0e`, joins, and completes the handshake on udp/9003. Its
serial reads out of its beacon correctly once the tag assumption above is dropped. And then nothing.

```
datalink: handshake OK on udp/9003
datalink: session=0xcefb base=0x56f0 channel=0x56f0
datalink: drone serial 1581FA6Q…………CHVJQ (20 chars, tag 0x24)
datalink: 51/02 open sent, len=40
datalink: 51-channel replies: 51/13×3            <- beacons only; a Mavic answers 51/08, 51/06, 51/80, 51/82 …
datalink: drone session-open sent — drone frames/s now 5      <- a Mavic reaches ~268 here
datalink: drone list FAILED … after 0B data; rx [pkt01×225]
```

**It does not answer `0x51/0x02` at all** — not an error, not a rejection, just more beacons. The frame
rate staying at ~5/s is the tell: the Mavic's jump to the hundreds *is* the session opening.

That is consistent with what the official app does. In a full DJI Fly ↔ Neo capture (287 packets from
cold start to flight), **`0x51/0x02` does not appear once**. What it sends instead is a long init whose
*repetition* is load-bearing — ~86 `0x00/0x99` capability subscriptions and 14 `0x03/0xcd` upload chunks
(`01 00` … `01 0d`) — and the aircraft only opens up once the whole thing has landed. A curated
first-occurrence-of-each subset (which is what a 30-command prelude is) leaves those bursts incomplete
and the drone withholds. Its `0x51` tunnels carry `51/13`, `51/17`, `03/f9`, `03/cd`; no `51/02`.

Other differences worth recording, none of them yet shown to matter:

- **The handshake reply is 15 bytes, not 9.** Same structure and the same `01` ACK byte, then six extra:
  `01 0f 00 05 05 40 1f`. **Byte-identical across sessions with different session ids**, so it is a fixed
  property of the aircraft or firmware — a version or capability descriptor, not a nonce or a challenge.
  Meaning unknown; it can be ignored and the link still comes up.
- **The AP drops ~16 s after joining**, twice, both times just before the list query went out. Plausibly
  downstream of the session never opening, but that is a guess.

Unresolved, and the honest state of it: the serial is **necessary but not sufficient**. Whether replaying
the full init unlocks a Neo 2 is untested, and the reference capture is from a **Neo 1** during a *flight
control* session rather than a media one — so it may not transfer.

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
([§19](#19-sd--storage--both-stores-in-one-frame), [§20](#20-battery--power-also-the-only-place-the-dock-reports-in)):

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
