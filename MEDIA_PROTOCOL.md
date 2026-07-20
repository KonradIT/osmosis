# Osmosis — Media & camera DUML commands

Every DUML command we use / know for browsing, fetching, and controlling media on DJI Osmo cameras
(WiFi UDP datalink + BLE control). From our app (`duml/`, `net/DatalinkClient`) and the reference repos
(`reference/osmo-download`, `reference/DJI-Wifi-Connect/pocket3`). Each **DUML example** is a full, valid
frame (correct CRC8+CRC16) — paste it into <https://b3yond.d3vl.com/duml/> and it decodes.

Transports: **BLE** = write GATT `fff5`, notify `fff4` (frame `[6:8]` msg-id is **big-endian**).
**Datalink** = UDP (Nano `9004` + TCP-7001 poke first; Action 5 Pro / Xtra `10004`, no poke), DUML wrapped
in `[8B udp hdr][12B routing hdr][frame]`. Addressing nibble `(id<<5)|type`: App `0x02`, Camera `0x01`,
Gimbal `0x03`, WiFi `0x07`, DM368 `0x08`.

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

fps is present; **resolution / size / duration are not** — read those from the MP4 `moov` + HTTP `HEAD`.

**Parsed — index-based** (older Osmo Action 1/2/3): header `[u32-LE count][u32-LE total_size]`, then fixed **65 B** records, **no path strings** (files keyed by numeric `FileIndex`):

| offset | type | field |
|--------|------|-------|
| `[0:4]`   | u32-LE   | Unix timestamp |
| `[8:12]`  | u32-LE   | **FileIndex** (`0x640251`…`0x640241`) |
| `[10:14]` | 2×u16-LE | DCF dir / file number (`100` = `100MEDIA`) |
| `[19:23]` | u32-LE   | video UUID (Amba `DjiMovDmx`) |
| `[38:42]` | u32-LE   | size-ish (~KB; a photo record reads ~0.6 MB) |

---

## Datalink session (sent before the list, over UDP)

### 2. Handshake  *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo. Then drain heartbeats, learn `camera_channel` (heartbeat routing `[8:10]`); app UDP seq starts at `camera_channel + 8`.

### 3. Device info
- Cmd Set / ID: `0x00` / `0x81`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Payload: `00 "APP" 00×37 02 00×8 02 08 00×10` (64 B)
- DUML example: <https://b3yond.d3vl.com/duml/#554b0402024800a08000810041505000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000020800000000000000000000ad80>

### 4. Register
- Cmd Set / ID: `0x00` / `0x88`  ·  App → DM368(`0x08`, id 1)
- Payload: `170008237b41505000000000000002`
- DUML example: <https://b3yond.d3vl.com/duml/#551c041b022800a0400088170008237b41505000000000000002d9e6>

### 5. Init
- Cmd Set / ID: `0x03` / `0xDA`  ·  App → Gimbal(`0x03`)
- Payload: `05ffffffff`
- DUML example: <https://b3yond.d3vl.com/duml/#551204c7020300a04003da05ffffffff4490>

### 6. Subscribe param
- Cmd Set / ID: `0x00` / `0x99`  ·  App → DM368(`0x08`, id 1)
- Payload: `02020000 <sub_id:u32LE> 00000000 <len:u16LE> 00 <name_len:u8> 00 <name padded to 20> 00000000`
- Sent once per param: `camcap_mode_profile`, `camcap_video_format`, `camcap_fov`, `camcap_iso`, `camcap_photo_storage_format`, `camcap_color_mode`, `cam_storage`, `cam_status`
- DUML example (`cam_status`): <https://b3yond.d3vl.com/duml/#5536043d022800a040009902020000df690000000000001a00000a0063616d5f7374617475730000000000000000000000000000ffe6>

### 7. Get version
- Cmd Set / ID: `0x00` / `0x00`  ·  App → DM368(`0x08`, id 2), cmd_type `4`
- Response: NUL-separated ASCII `sdk\0name\0firmware` — scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Camera control

From `reference/DJI-Wifi-Connect/pocket3` + `reference/osmo-download`. Cmd Set `0x02`, App → Camera(`0x01`,
id 0), over the datalink. **Derived from the DJI protocol standard — cmdIds solid, payloads may need
per-model adjustment; not yet verified on our Nano/Xtra.**

### 8. Take photo
- Cmd Set / ID: `0x02` / `0x01`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002017677>

### 9. Start recording
- Cmd Set / ID: `0x02` / `0x20`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a0000220fd47>

### 10. Stop recording
- Cmd Set / ID: `0x02` / `0x21`  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002217456>

### 11. Set mode
- Cmd Set / ID: `0x02` / `0x02`  ·  payload `[mode:u8]` — `0` Photo, `1` Video, `2` Playback, `3` SlowMo, `4` Timelapse, `5` Panorama
- DUML example (Video): <https://b3yond.d3vl.com/duml/#550e0466020100a0000202017bb8>

### 12. Camera heartbeat  *(Mimo sends ~15 Hz to keep the camera awake)*
- Cmd Set / ID: `0x02` / `0x8E`  ·  cmd_type PUSH  ·  payload `00 01 14 00`
- DUML example: <https://b3yond.d3vl.com/duml/#55110492020100a040028e00011400a858>

### 13. Camera state query
- Cmd Set / ID: `0x02` / `0xA0`  ·  cmd_type PUSH  ·  empty payload
- Response: 28 B — `recording_time_s` = `u16-LE @ byte 6`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002a0f5c3>

### 14. Camera status poll
- Cmd Set / ID: `0x02` / `0x61`  ·  cmd_type PUSH  ·  empty payload
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020100a00002617014>

---

## Status pushes (camera → app, decoded not sent)

### 15. Camera status
- Cmd Set / ID: `0x02` / `0x80`  (~10 Hz push, 60 B)
- Fields we read: **storage total** = `u32-LE MiB @ byte 5`, **free** = `@ byte 9`. `recording` = `byte1 & 0x01` (per Pocket 3 repo).
- Quirks: reports the **active store only** (internal vs SD). Nano + Xtra.

### 16. SD / storage
- Cmd Set / ID: `0x02` / `0xDC`  (22 B)  ·  `byte0 & 0x01` = SD present

### 17. Battery
- Cmd Set / ID: `0x0D` / `0x02`  (34 B)  ·  percent = `byte 20`, millivolts = `u16-LE @ byte 1`

---

## Connection (BLE control — prerequisites to reach media)

### 18. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`; token `"osmo"`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval popup on camera; approval then arrives as a **`0x07/0x46` request** (flags `0x40`), which is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

### 19. ConnectToWiFi (wake AP)
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's *own* creds
- Response: `0x07/0x47` `00 00` = ok; AP comes up ~15 s later
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 20. GetWifiSsid
- Cmd Set / ID: `0x07` / `0x07`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString ssid]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472>

### 21. GetWifiPassword
- Cmd Set / ID: `0x07` / `0x0e`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString passphrase]`
- Quirks: **pace after GetWifiSsid by ~500 ms** (`fff5` is write-without-response). Verified on Xtra / Action 5 Pro; Nano rides the saved-password fallback.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef>

### 22. GetWifiMac
- Cmd Set / ID: `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][6-byte MAC]`
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>
