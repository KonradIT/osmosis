# Osmosis — Media DUML commands

Every DUML command we use to browse / fetch / manage media on DJI Osmo cameras (the **media path** —
WiFi UDP datalink + BLE control). **Not R-SDK** (that's a separate `0xAA` protocol — see `ROADMAP.md` #9).
Sourced from our app (`duml/`, `net/DatalinkClient`) and `reference/osmo-download`. Each **DUML example**
is a full, valid frame (correct CRC8+CRC16) — paste it into <https://b3yond.d3vl.com/duml/> and it decodes.

Transports: **BLE** = write to GATT `fff5`, notify `fff4` (frame `[6:8]` msg-id is **big-endian**).
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
- Response: chunked `0x00/0x27` frames, each payload = `[10B sub-header 4A 01 xx xx <seq:u16LE@6> 00 00][chunk]`. **Strip the sub-header, concat chunks in order** → reassembled manifest = `[u32-LE count][records]`.
- DUML example: <https://b3yond.d3vl.com/duml/#553704f9020100a04000264a002a10010000000000010000002d000d0100ffffffffffffffff0001000000000000000000000000008185>
- Quirks: **Nano** = `DJI_` names, fixed 361 B/record. **Xtra / Action 5 Pro** = `CAM_`, 272 B/record. **Older Osmo Action (1/2/3)** = an *index* format instead: `[u32 count][u32 total_size]` + fixed 65 B records, **no path strings** (files keyed by numeric `FileIndex`). Paths carry no extension — only the filename field does.

### 2. Delete media  — ❓ cmdId unconfirmed (see `ROADMAP.md` #4)
- Cmd Set: `0x00` (same file-mgmt set as the list)
- Cmd ID: **unknown** — RE'd to native `xtra::sdk::FileTransferManager::DeleteFiles` → `SendCompositePack<delete_file_req, MediaFile>`, but the cmdId byte isn't isolated yet.
- Payload: composite/batch — storage selector `pair<u8,u8>` + `MediaFile` refs, versioned `XTRA_V1_CMD_VERSION`.
- DUML example: — (blocked on the cmdId)

---

## Datalink session (sent before the list, over UDP)

### 3. Handshake  *(not DUML — routing payload)*
- UDP packet type `0x00`, payload `b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102`
- Response: type `0x00` echo → session established. Then drain heartbeats, learn `camera_channel` (heartbeat routing `[8:10]`); app UDP seq starts at `camera_channel + 8`.

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
- Response: NUL-separated ASCII `sdk\0name\0firmware` — we scrape the `NN.NN.NN.NN` firmware string.
- DUML example: <https://b3yond.d3vl.com/duml/#550d0433024800a0800000017e>

---

## Status pushes (camera → app, decoded not sent)

### 9. Camera status
- Cmd Set / ID: `0x02` / `0x80`  (~10 Hz push)
- Fields we read: **storage total** = `u32-LE MiB @ byte 5`, **free** = `@ byte 9`. (Byte 0 low-nibble is *not* the shooting mode — that was a wrong guess, removed.)
- Quirks: reports the **active store only** (internal vs SD). Nano + Xtra.

### 10. SD inserted
- Cmd Set / ID: `0x02` / `0xDC`  ·  `byte0 & 0x01` = SD present

### 11. Battery
- Cmd Set / ID: `0x0D` / `0x02`  ·  percent = `byte 20`
- Quirks: camera-unit battery only (DUML sender `0x05`). The **Nano dock** battery is *not* in this push — needs a separate query (`ROADMAP.md` #5).

---

## Connection (BLE control — prerequisites to reach media)

### 12. SetPairingPIN
- Cmd Set / ID: `0x07` / `0x45`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(identifier)` + `PackString(token)` (`PackString` = `[len:u8][utf8]`; token `"osmo"`)
- Response: `0x07/0x45` payload `00 01` = already paired · `00 02` = approval popup on camera; approval then arrives as a **`0x07/0x46` request** (flags `0x40`), which is the "go" signal.
- DUML example: <https://b3yond.d3vl.com/duml/#553304c2020700a0400745203238346165356238643736623333373561303461363431376164373162656133046f736d6f8c02>

### 13. ConnectToWiFi (wake AP)
- Cmd Set / ID: `0x07` / `0x47`  ·  App → WiFi(`0x07`), BLE
- Payload: `PackString(ssid)` + `PackString(password)` — the camera's *own* creds
- Response: `0x07/0x47` `00 00` = ok; AP comes up ~15 s later
- DUML example (password redacted): <https://b3yond.d3vl.com/duml/#5528040d020700a04007470d4f736d6f4e616e6f2d433244380c78787878787878787878787827e1>

### 14. GetWifiSsid / GetWifiPassword / GetWifiMac
- Cmd Set / ID: `0x07` / `0x07` · `0x07` / `0x0e` · `0x07` / `0x0c`  ·  App → WiFi(`0x07`), BLE, empty payload
- Response: `[status:1][PackString]` (SSID / passphrase) · `[status:1][6-byte MAC]`
- Quirks: **pace the queries ~500 ms apart** (`fff5` is write-without-response). Verified on Xtra / Action 5 Pro; Nano rides the saved-password fallback.
- DUML examples: SSID <https://b3yond.d3vl.com/duml/#550d0433020700a04007077472> · password <https://b3yond.d3vl.com/duml/#550d0433020700a040070eb5ef> · MAC <https://b3yond.d3vl.com/duml/#550d0433020700a040070ca7cc>
