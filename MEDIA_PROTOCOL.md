# Osmosis — Media & camera DUML commands

Every DUML command we use / know for browsing, fetching, and controlling media on DJI Osmo cameras
(WiFi UDP datalink + BLE control). From our app (`duml/`, `net/DatalinkClient`) and the reference repos
[osmo-download](https://github.com/SemiConscious/osmo-download) and
[DJI-Wifi-Connect/pocket3](https://github.com/sniffingpickles/DJI-Wifi-Connect/tree/main/pocket3). Each **DUML example** is a full, valid
frame (correct CRC8+CRC16) — paste it into <https://b3yond.d3vl.com/duml/> and it decodes.

Transports: **BLE** = write GATT `fff5`, notify `fff4` (frame `[6:8]` msg-id is **big-endian**).
**Datalink** = UDP (Nano `9004` + TCP-7001 poke first; Action 5 Pro / Xtra `10004`, no poke), DUML wrapped
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
- Paging: page 2 `4a040e10 01000000 0000 01000000` · page 3 `4a002a10 02000000 0000 01000040 2d00 …`
- Response: chunked `0x00/0x27` frames, each payload = `[10B sub-header 4A 01 xx xx <seq:u16LE@6> 00 00][chunk]`. **Strip the sub-header, concat chunks in arrival order** → the manifest.
- DUML example: <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>

**Parsed — path-based** (Nano `DJI_` 361 B / Xtra `CAM_` 272 B, fixed stride): `[u32-LE count][record × count]`. Each record = an enum block (holds the fps rational) + length-prefixed strings. Anchor on the filename — the only field with an extension:

```
… 0b "DJI"/"CAM" 00×9   8A 01 <idx>   … <fps> … 12 01 00 13 00
  0d <len:u8> <filename>              # DJI_20260329115359_0211_D.MP4  (has the extension)
  1a <len:u8> 00 00 00 01 <media>     # DCIM/DJI_001/DJI_…_D           (NO extension)
  1a <len:u8> 00 00 00 02 <thumb>     # MISC/THM/DJI_001/DJI_…_D
  1a <len:u8> …                       # optional .LRF/.LRV proxy (Nano)
```

| token | field |
|-------|-------|
| `0b "DJI"/"CAM" 00×9` | record start |
| `8A 01 <idx>` | record index |
| `<u32-LE num><u32-LE den>` | fps rational, e.g. `a861 0000 e803 0000` = 25000/1000 = **25 fps** |
| `0d <len> <name>` | filename (only field carrying the extension → record anchor) |
| `1a <len> 00000001 <path>` | DCIM media path (no ext) |
| `1a <len> 00000002 <path>` | MISC/THM thumb path |

fps is present, and **byte size** (`u32-LE @ record +38`, video records) — no HTTP `HEAD` needed; **resolution / duration are not** — read those from the MP4 `moov`.

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
- **Handle** — u32-LE at the record head, located by anchoring on the constant record marker `03 ff 19 06` (at head + 8, so `handle = u32 @ marker − 8`). Nano (`DJI_`, 361 B records) handles start `0x40104000` step `0x40`; Xtra / Action (`CAM_`, 272 B records) start `0x40040000` step `0x10`. Photo records lack the marker → non-deletable (fail-safe).
- **Session** — accepted only on a **freshly-registered** datalink: the browse keep-alive advances our UDP seq past the camera's write window (reads still answer, writes are silently dropped), so tear keep-alive down and re-run the datalink-session open (handshake → register → subscribe) before sending. ~9 s. Verified on Nano + Xtra (`status 0000`, file removed).
- DUML example (delete handle `0x40104480`): <https://b3yond.d3vl.com/duml/#551f044e020100a0400028018044104001000000000100000001010000a0d1>

---

## Datalink session (sent before the list, over UDP)

### 3. Handshake  *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo. Then drain heartbeats, learn `camera_channel` (heartbeat routing `[8:10]`); app UDP seq starts at `camera_channel + 8`.

### 4. Device info
- Cmd Set / ID: `0x00` / `0x81`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Payload: `00 "APP" 00×37 02 00×8 02 08 00×10` (64 B)
- DUML example: <https://b3yond.d3vl.com/duml/#554b0402024800a08000810041505000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000020800000000000000000000ad80>

### 5. Register
- Cmd Set / ID: `0x00` / `0x88`  ·  App → DM368(`0x08`, id 1)
- Payload: `170008237b41505000000000000002`
- DUML example: <https://b3yond.d3vl.com/duml/#551c041b022800a0400088170008237b41505000000000000002d9e6>

### 6. Init
- Cmd Set / ID: `0x03` / `0xDA`  ·  App → Gimbal(`0x03`)
- Payload: `05ffffffff`
- DUML example: <https://b3yond.d3vl.com/duml/#551204c7020300a04003da05ffffffff4490>

### 7. Subscribe param
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1)
- Payload: `02020000 <sub_id:u32LE> 00000000 <len:u16LE> 00 <name_len:u8> 00 <name padded to 20> 00000000`
- Sent once per param: `camcap_mode_profile`, `camcap_video_format`, `camcap_fov`, `camcap_iso`, `camcap_photo_storage_format`, `camcap_color_mode`, `cam_storage`, `cam_status`
- DUML example (`cam_status`): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

### 8. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Response: NUL-separated ASCII `sdk\0name\0firmware` — scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

From [DJI-Wifi-Connect/pocket3](https://github.com/sniffingpickles/DJI-Wifi-Connect/tree/main/pocket3) + [osmo-download](https://github.com/SemiConscious/osmo-download). Cmd Set `0x02`, App → Camera(`0x01`,
id 0), over the datalink. **Derived from the DJI protocol standard — cmdIds solid, payloads may need
per-model adjustment; not yet verified on our Nano/Xtra.**

### 9. Take photo
- Cmd Set / ID: `0x02` / `0x01`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002017677>

### 10. Start recording
- Cmd Set / ID: `0x02` / `0x20`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a0000220fd47>

### 11. Stop recording
- Cmd Set / ID: `0x02` / `0x21`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002217456>

### 12. Set mode
- Cmd Set / ID: `0x02` / `0x02`  ·  payload `[mode:u8]` — `0` Photo, `1` Video, `2` Playback, `3` SlowMo, `4` Timelapse, `5` Panorama
- DUML example (Video): <https://b3yond.d3vl.com/duml/#550e0466020100a0000202017bb8>

### 13. Camera heartbeat  *(Mimo sends ~15 Hz to keep the camera awake)*
- Cmd Set / ID: `0x02` / `0x8E`  ·  cmd_type PUSH  ·  payload `00 01 14 00`
- DUML example: <https://b3yond.d3vl.com/duml/#55110492020100a040028e00011400a858>

### 14. Camera state query
- Cmd Set / ID: `0x02` / `0xA0`  ·  cmd_type PUSH  ·  empty payload
- Response: 28 B — `recording_time_s` = `u16-LE @ byte 6`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002a0f5c3>

### 15. Camera status poll
- Cmd Set / ID: `0x02` / `0x61`  ·  cmd_type PUSH  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002617014>

---

## Status pushes (camera → app, decoded not sent)

### 16. Camera status
- Cmd Set / ID: `0x02` / `0x80`  (~10 Hz push, 60 B)
- Fields we read: **storage total** = `u32-LE MiB @ byte 5`, **free** = `@ byte 9`. `recording` = `byte1 & 0x01` (per Pocket 3 repo).
- Quirks: reports the **active store only** (internal vs SD). Nano + Xtra.

### 17. SD / storage
- Cmd Set / ID: `0x02` / `0xDC`  (22 B)  ·  `byte0 & 0x01` = SD present

### 18. Battery / power *(also the only place the dock reports in)*
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
  (#16) covers the **active** store only.

---

## Connection (BLE control — prerequisites to reach media)

### Waking a sleeping camera

A sleeping Osmo Nano **keeps advertising `ADV_IND`** under its own name, so there is no wake *broadcast*
to send — DJI documents a `WKP` manufacturer-data advertisement, but an HCI snoop of Mimo waking a Nano
shows Mimo never advertises at all. The wake is an ordinary **command sequence** over GATT `fff5`:

| # | write | receiver | note |
|---|-------|----------|------|
| 1 | `0x00/0x2b` `04 00` | `0xF0` | first thing Mimo writes, **before** pairing |
| 2 | `0x07/0x45` SetPairingPIN | `0x07` | see #22 |
| 3 | `0x00/0x2b` `01 01` | `0xF0` | then repeating ~1 Hz, forever, as the keepalive |
| 4 | `0x53/0x10` `00 00 00 00` | `0x1C` | camera answers `01 00 00 00` and **wakes** |

Pace the writes ~100–500 ms apart: `fff5` is write-without-response, so back-to-back frames are dropped.
Mimo does **not** send ConnectToWiFi (#23) anywhere in this flow.

### 19. Session wake / keepalive
- Cmd Set / ID: `0x00` / `0x2b`  ·  App(`0x02`) → **`0xF0`** (type `0x10`, id 7), BLE
- Payload: `04 00` = open the session (sent once, pre-pairing) · `01 01` = keepalive (repeat ~1 Hz)
- Quirks: the Nano drops an idle paired link after ~5–6 s, so the `01 01` ping must keep running for the
  whole session. Re-sending SetPairingPIN instead (what we used to do) is noisier and gets a sleeping
  camera to drop you.
- DUML example (`04 00`, verbatim from a Mimo capture): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b04009ab9>
- DUML example (`01 01` keepalive): <https://b3yond.d3vl.com/duml/#550f04a202f01bcb40002b0101abd6>

### 20. Wake camera
- Cmd Set / ID: `0x53` / `0x10`  ·  App(`0x02`) → **`0x1C`** (type `0x1C`, id 0), BLE
- Payload: `00 00 00 00`
- Response: `01 00 00 00` — the camera wakes ~2–3 s later and brings its AP up on its own
- Quirks: this is the command that actually correlates with the wake. Addressed to Camera(`0x01`) it
  answers `e0`. Send it **after** pairing; the same `rcv_type 28` shows up on the UDP datalink for
  `0x53/0x15`, so `0x53` is a session/system set rather than a camera one.
- DUML example (verbatim from a Mimo capture): <https://b3yond.d3vl.com/duml/#55110492021c1dcb40531000000000894a>

### 21. WiFi enable *(does **not** work)*
- Cmd Set / ID: `0x07` / `0x39`  ·  App → WiFi(`0x07`), BLE
- Quirks: Mimo sends this, but the camera rejects it (`e0`) **for Mimo too**, so it is not load-bearing
  for the wake and we don't send it. Listed only so it isn't re-derived from a capture as a lead.

### 22. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`; token `"osmo"`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval popup on camera; approval then arrives as a **`0x07/0x46` request** (flags `0x40`), which is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

### 23. ConnectToWiFi (AP bring-up — fallback only)
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's *own* creds
- Response: `0x07/0x47` `00 00` = ok; AP comes up ~15 s later
- Quirks: **Mimo never sends this**, and on a *sleeping* camera it correlated with the link being
  terminated (GATT `status=19`). The wake sequence above brings the AP up on its own, so keep this
  only as a fallback for models that never surface creds over BLE (#24/#25).
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 24. GetWifiSsid
- Cmd Set / ID: `0x07` / `0x07`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString ssid]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472>

### 25. GetWifiPassword
- Cmd Set / ID: `0x07` / `0x0e`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString passphrase]`
- Quirks: **pace after GetWifiSsid by ~500 ms** (`fff5` is write-without-response). Verified on Xtra / Action 5 Pro; Nano rides the saved-password fallback.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 26. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>
