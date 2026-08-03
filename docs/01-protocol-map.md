# Osmosis — DJI Osmo protocol map (BLE + WiFi)

Ground truth for how Osmosis talks to DJI Osmo cameras with **no DJI SDK**. Everything here is
**hardware-verified** on an **Osmo Nano** and an **Xtra Edge Pro (= DJI Osmo Action 5 Pro)** unless a
line is explicitly marked *inferred* (from the open-source reference repos for the 360 / Pocket 3 /
Action 4-6, in `reference/`). Where the two verified cameras differ, the difference is called out; a
per-model summary table is at the end.

The pipeline is always: **BLE pair → get WiFi creds → wake the AP → join it → pull the media list over
the DUML datalink → fetch thumbnails/files over HTTP.**

---

## 1. Device identification (BLE advertisement)

The camera is found by a BLE scan on service `fff0` + its manufacturer data. The **model byte** (not
the brand) decides the datalink port and WiFi security.

| model | BLE name | model byte | notes |
|-------|----------|-----------|-------|
| Osmo Nano | `OsmoNano-XXXX` | `0x19` | DJI mfr id `0x08AA`; adv payload `19 00 00 <6-byte MAC> 03` |
| Xtra Edge Pro / Action 5 Pro | `XtraEdgePro-XXXX` | `0x15` | Xtra OUI `EC:9E:EA`, company id `0xAAF7` (vs DJI `0xAA08`) |
| Osmo 360 | `Osmo360-XXXX` | `0x17` | *inferred*; WPA3 AP |
| Osmo Pocket 3 | (BLE local name only) | `0x20` | *inferred*; broadcasts no mfr data |

The Xtra brand is a covert DJI shell company; the Edge Pro is a rebadged Action 5 Pro on DJI firmware
(confirmed by string/dexdump sweep of its app in `reference/xtra/` + live traffic). It resolves by its
model byte to the Action 5 Pro profile, so a DJI-branded Action 5 would behave identically.

---

## 2. Transport: BLE GATT

- Service **`fff0`**, characteristics `fff3, fff4, fff5, fff7`. Request **MTU 517** (notifications
  otherwise cap ~23 B; they reach ~256 B after the raise).
- **Write DUML commands to `fff5`** — *Write-Without-Response*. (Writing DUML to `fff3` is silently
  dropped — a known footgun.)
- **Arm pairing** by writing `[0x01, 0x00]` to `fff4`.
- **Notifications** arrive on `fff4` (and `fff5`). On the Pocket 3 *all* notifications land on `fff4`.
- BLE is the **control** channel only — it wakes the camera, pairs, hands out WiFi creds, and turns the
  AP on. Bulk data (media list, thumbnails, files) never goes over BLE.
- **Idle drop:** the camera tears the BLE link (`status=19`) ~5–6 s after pairing if the app idles —
  by design, you're meant to move to WiFi. `ConnectToWiFi` (0x07/0x47) both wakes the AP and keeps BLE
  alive a bit longer.

---

## 3. DUML frame (SOF 0x55)

The same DUML framing used drone-side, over GATT/UDP instead of UART.

```
off sz  field
0   1   SOF = 0x55
1   1   len_lo               ) total_len = 13 + payload_len (incl. both CRCs)
2   1   (ver<<2)|len_hi[9:8] ) ver=1  -> byte reads 0x04 when len < 256
3   1   CRC8 over bytes[0:3]
4   1   sender   = (sender_id<<5)|sender_type    ; App = 0x02 (id0,type2)
5   1   receiver = (receiver_id<<5)|receiver_type
6   2   msg id / seq         -- BLE: BIG-ENDIAN msg id ; UDP datalink: LE seq
8   1   cmd flags = (cmd_type<<5)|encrypt
        0x40 = request  0xC0 = response/ack  0x00 = notify
9   1   CmdSet
10  1   CmdId
11  N   payload
11+N 2  CRC16 over bytes[0 : 11+N]  (LE)
```

- **Address nibbles** (`(id<<5)|type`): App `0x02`, Camera `0x01`, Gimbal `0x03/0x04`, **WiFi
  subsystem `0x07`**, DM36x media proc `0x08` (`0x28`=id1, `0x48`=id2). "Target 0x0702" = App→WiFi.
- **BLE quirk:** the `[6:8]` field is a **big-endian message id** on BLE (verified across 3 impls); on
  the UDP datalink it's the usual LE sequence.
- **CRC** (identical to drone DUML, two equivalent parameterizations):
  - CRC8: reflected `init=0x77 poly=0x8C` ≡ spec `init=0xEE poly=0x31 refin/refout`.
  - CRC16: reflected `init=0x3692 poly=0x8408` ≡ spec `init=0x496C poly=0x1021 refin/refout`.
  - We vendor the unit-tested Kotlin impl from `dimadesu/dji-remote` (`DjiCrc.kt`).
- **`PackString(s)` = `[len:u8][utf8 bytes]`** — used for identifier / token / SSID / password.

---

## 4. Pairing (BLE, CmdSet 0x07)

App-level pairing replaces OS Bluetooth bonding — there is no numeric BT pairing.

```
1. write [0x01,0x00] -> fff4                     arm pairing
2. SetPairingPIN   flags0x40 07/45 -> WiFi(0x0702)
     payload = PackString(identifier) + PackString(token)
3. <- PairingStatus flags0xC0 07/45:
        00 01 = ALREADY PAIRED     -> proceed to WiFi
        00 02 = APPROVAL REQUIRED  -> camera shows the token on its screen for approve/deny
4. (if 00 02) user taps approve on the camera
   <- 07/46 flags0x40 payload 0x01   ← arrives as a REQUEST, not a 0xC0 response
   -> ack: 07/46 flags0xC0 payload 0x00
      The approval REQUEST is the "go" signal — treat it as pairing-complete and start offload.
```

- **`identifier`** = the string the device stores its approval **under**, so it recognises us next time
  ("already paired"). Any of the shapes seen work (15-digit or 32-hex UUID). Proven by experiment on a
  Mavic 3: rotating it on an aircraft that had been re-pairing silently for days forced a full approval
  round, and a known one skips it again. We send a **fixed constant on cameras** and a **per-install
  UUID, minted once and persisted, on drones** ([DronePairing](../app/src/main/java/dev/konraditurbe/osmosis/drone/DronePairing.kt)) —
  a shipped constant makes every install share one identity and contend for a single remembered slot.
  Retries must carry the **same** identifier: `fff5` is write-without-response, so a first write can
  drop, and a retry with a different identity reads as a second app asking to pair.
- **`token`** — cosmetic on a **camera**, load-bearing on a **drone**. A camera displays it verbatim on
  first use and the on-screen *approve* tap is the real gate; we send `"osmo"` and the screen shows
  `OSMO`. A drone approves any token but releases WiFi credentials only for **`"DJI FLY"`**, so an
  aircraft paired under the wrong token looks successful and then hands out nothing.
- **Confirming without a screen.** A drone has no display: it flashes its LEDs and waits for a
  power-button hold — 2 s on most models, 3 s on the newest, while the **Mini 3** has no hold at all and
  needs three quick presses to enter QuickTransfer mode.
- The first-time `00 02` flow is confirmed on a factory-reset Xtra / Action 5 Pro. The already-paired
  `00 01` fast path is confirmed on both units. The handler must treat the `07/46` **request** as the
  completion signal — the `07/45 = 00 01` fast path alone only covers the already-paired case.

---

## 5. WiFi credentials, AP activation, and join

### 5a. Get the AP creds over BLE (CmdSet 0x07)

The camera hands out its **own AP SSID + passphrase** over BLE — no manual entry, no on-screen reading.

| dir | flags | set/id | name | reply payload |
|-----|-------|--------|------|---------------|
| →   | 0x40 | 07/07 | **GetWifiSsid** | `[status:1][PackString ssid]` (e.g. `00 12 "XtraEdgePro-2DCA16"`) |
| →   | 0x40 | 07/0e | **GetWifiPassword** | `[status:1][PackString passphrase]` |
| →   | 0x40 | 07/0c | GetWifiMac | `[status:1][6-byte MAC]` |

- **Verified on the Xtra / Action 5 Pro** (found by HCI-snooping the official app): query `07/07`
  then `07/0e` after pairing and the camera returns both.
- **Pace the two queries ~500 ms apart.** `fff5` is write-without-response, so a back-to-back second
  query drops, and the first must not race the pairing-approval ACK.
- The retrieved passphrase is **cached per-MAC** and never logged (only its length). It appears static
  per device. Falls back to a saved value / one-time prompt for any model that doesn't answer.
- Nano status: not re-verified against these exact getters, so it currently rides the saved-password
  fallback; the getters very likely work there too.

### 5b. Wake the AP

| dir | flags | set/id | name | payload |
|-----|-------|--------|------|---------|
| →   | 0x40 | 07/47 | ConnectToWiFi | `PackString(ssid) + PackString(pass)` — you send the camera **its own** creds; the AP comes up ~15 s later |
| ←   | 0xC0 | 07/47 | WiFiConnectResult | `00 00` = ok |

The AP **sleeps when idle** and must be woken per session this way. IP: camera `192.168.2.1`, client
gets `192.168.2.x` via DHCP. Security: **WPA2-PSK** on Nano and Xtra (WPA3-SAE on the 360, *inferred*),
5.8 GHz.

### 5c. Join from Android

- API 29+ **`WifiNetworkSpecifier`** → `ConnectivityManager.requestNetwork(...)` with a
  `NetworkCallback`. On `onAvailable(network)` call **`bindProcessToNetwork(network)`** so our HTTP/UDP
  sockets use the internet-less AP instead of falling back to cellular.
- `setWpa2Passphrase` for Nano/Xtra; try `setWpa3Passphrase` first for the 360.
- **Keepalive:** the AP drops ~10 s idle. Hold it with an active HTTP download, a UDP datalink
  handshake ping every ~2 s, or a TCP-7001 heartbeat (Nano).

---

## 6. HTTP media API

The camera runs **lighttpd/1.4.55**. Files are fetched by explicit path — **there is no
directory-listing endpoint** (any non-`/v2` URL is connection-reset), so the file list comes from the
DUML datalink (§7), not HTTP.

```
GET /v2?storage={0|1}&path=<path>      0 = internal, 1 = SD
HEAD supported (Content-Length) ; Range supported (206 partial)
```

- **Storage id is not in the list paths.** Auto-detect by HEAD-probing the first file at `storage=1`
  then `storage=0`; fetch the rest with the winner. (On the Nano test unit media was on SD = `1`;
  internal `0` returned 404.)
- **Thumbnails:** `MISC/THM/.../<name>.scr` (same storage id), served by `/v2`; the `.scr` decodes as a
  JPEG (`BitmapFactory` renders it directly).
- Both verified cameras are `/v2` servers. The `/v1?file_index=…&file_subtype=…&file_seg_subindex=…`
  form exists in DJI apps for other/older models; on the Xtra it is connection-reset. See ROADMAP #6
  for the older Osmo Action generation (different, index-based list — parked, code on the
  `add-osmo-action-support` branch).

---

## 7. Media list over the DUML datalink (UDP)

The manifest is a DUML `0x00/0x26` request → `0x00/0x27` response over a **UDP datalink**. The port is
model-dependent:

| model | datalink | poke |
|-------|----------|------|
| Osmo Nano | **UDP 9004** | TCP-7001 pre-poke, then UDP handshake |
| Xtra Edge Pro / Action 5 Pro | **UDP 10004** | none (TCP 7001 refused; no 9004) |

The handshake payload is **identical** across models (`…seed… 64 00 64 00 c0 05 …`); only the port
differs. So the client tries the handshake on 9004 **and** 10004 and runs the file-list flow on
whichever answers.

### 7a. UDP wire format

```
UDP pkt = [8B udp hdr][12B routing hdr][DUML frame]
 udp hdr : (0x8000|totlen):u16le  session:u16le  seq:u16le  type:u8  xor:u8
   type  0x00 handshake / 0x01 telemetry / 0x03 acked-data / 0x04 ack / 0x05 command
 routing : last_cam_seq:u16le  this_seq:u16le  00000000  counter:u8  01 00 00
 seq rule: app UDP seq MUST start at (camera_channel + 8);
           camera_channel = heartbeat routing[8:10]
```

### 7b. Flow (Nano; the Action 5 is the same minus the TCP poke, on 10004)

```
TCP7001 poke -> UDP handshake (40B payload) -> drain heartbeats / learn channel
  -> devinfo   0x00/0x81 -> DM368:2 (cmd_type4)
  -> register  0x00/0x88 -> DM368:1
  -> init      0x03/0xDA -> Gimbal
  -> subscribe 0x00/0x99 -> DM368:1 (per-param)
  -> list      0x00/0x26 -> Camera:0
list req payload (page 1):
  4a00 2a10 010000000000 01000000 2d000d0100 ffffffffffffffff 0001000000000000 000000
```

Responses are `0x00/0x27` frames carrying the file list. Naming:

- **Nano:** `DCIM/DJI_001/DJI_<YYYYMMDDHHMMSS>_<NNNN>_D.<ext>` (videos `.MP4`).
- **Xtra / Action 5:** `DCIM/CAM_001/CAM_<YYYYMMDDHHMMSS>_<NNNN>_D.MP4`.
- Thumbs: `MISC/THM/.../<name>.scr`.

### 7c. Reassembly — strip the per-frame sub-header

The manifest is too big for one DUML frame, so it's chunked across ~17 `0x00/0x27` frames. **Each
frame's payload is `[10-byte sub-header][chunk]`**, where the sub-header is
`4A 01 xx xx <seq:u16-LE @6> 00 00`. `byte1 == 0x01` marks a data chunk; the bracketing control frames
are `4A 04…` (start) / `4A 03…` (end) — 10 bytes of sub-header, no chunk.

**Strip the sub-header (`payload[10:]`) before concatenating chunks in arrival order.** Concatenating
the raw payloads instead injects those 10 bytes mid-string wherever a path straddles a frame boundary
(`DCIM/DJI_` + `J….001/…`); the path is then unparseable and that one file silently drops — and which
file drops depends on packet layout, so the loss looks random run-to-run. The reassembled manifest
opens with a `u32-LE` file count. Don't seq-sort across pages: the per-file counter restarts per page.
See [`manifestBytes`](../app/src/main/java/dev/konraditurbe/osmosis/camera/CameraSession.kt).

### 7d. Record layout

The reassembled manifest is `[u32-LE file count][record × count]`, **fixed-stride** records (Nano
`DJI_` = 361 B, Xtra `CAM_` = 272 B — constant within a device). Each record is an **enum block**
(carries the fps rational as `u32-LE num` + `u32-LE den`, e.g. `a8 61 00 00  e8 03 00 00` = 25000/1000)
followed by **length-prefixed strings**. Per-record signature:

```
… 0b "DJI"/"CAM" 00×9   8A 01 <idx>   … 12 01 00 13 00
  0d <len:u8> <filename>
  1a <len:u8> 00 00 00 01 <DCIM media path>
  1a <len:u8> 00 00 00 02 <MISC/THM thumb path>
```

- `0d` = filename tag; `1a` = path tag with a 4-byte discriminator (`…01` media, `…02` thumb); an
  optional `.LRF`/`.LRV` proxy is a further field.
- **Key fact: the media/thumb path fields carry NO extension — only the filename field does.** So a
  primary-extension name token (`_D.MP4` / `.JPG`, not `.LRF`) appears **exactly once per record**,
  making the filename the reliable record anchor and the leading `u32` count a checksum.

### 7e. Decode, don't scrape

[`decodeManifest`](../app/src/main/java/dev/konraditurbe/osmosis/camera/CameraSession.kt) anchors on each
primary-extension filename, scopes that record's media/thumb/proxy paths and fps to its own byte window
(no cross-record joins, no `±220 B` fps guessing), and **asserts decoded record count == the header
`u32` count**. That assertion is the safety net — a record dropped by a reassembly bug fails the check
and logs, instead of silently shipping a short grid. It falls back to a whole-blob regex scrape
(`parseFlat`) for layouts that don't validate. Regression-locked by `DatalinkManifestTest` against the
real 45-record Nano and 13-record Xtra blobs.

---

## 8. What the manifest carries — and what it doesn't

- **fps: yes.** A rational `num/den` (den ∈ {1000, 1001}) just before the filename field —
  `25000/1000` = 25, `30000/1001` = 29.97; round to the nearest standard.
- **Resolution: no.** Pixel dimensions are in no field, and the KV enums can't separate resolutions (a
  2.7K and a 4K clip carry identical `0x2c`/`0x36`/`0x37` values). **Read resolution from the MP4
  `moov`** (`tkhd` last 8 bytes = width,height as 16.16 fixed) via the same Range fetch used for
  duration.
- **Size / duration: no** (byte-diffed newest vs oldest against ground truth — no field matches in any
  unit). Size from HTTP `HEAD` (`Content-Length`); duration from the MP4 `mvhd`/`tkhd` via Range
  requests (`HttpURLConnection` respects the process network binding, unlike `MediaMetadataRetriever`).

---

## 9. Low-res preview

- **Nano** lists a `.LRF` proxy clip per video in the manifest
  (`DCIM/DJI_001/DJI_<ts>_<seq>_D.LRF` — a 960×720 MP4 with `moov` at the **end**; ~0.6 MB for ~1 s up
  to ~95–190 MB for long clips). **Read the proxy path from the manifest, don't derive it** —
  extension/availability vary by model (`.LRF` vs `.LRV`, or none).
- **Xtra / Action 5** lists a `.XRF` file, they swapped the L for an X, which is typical for a dji shell company to do. Same as the Osmo Nano.
- Preview by streaming: `VideoView.setVideoURI("http://192.168.2.1/v2?…&path=<proxy-or-full>")`. Native
  `MediaPlayer` HTTP **does** honour `bindProcessToNetwork`, so it reaches the internet-less AP and
  range-fetches the `moov` off the end — any length, full scrub, zero download. Gotcha: a
  `visibility=gone` `VideoView` has no Surface and never prepares (no `onPrepared`/`onError`) — make it
  visible **before** `setVideoURI`.

---

## 10. Per-model summary

| | Osmo Nano | Xtra Edge Pro / Action 5 Pro |
|--|-----------|------------------------------|
| Model byte | `0x19` | `0x15` |
| Datalink | UDP **9004** (+ TCP-7001 poke) | UDP **10004** (no poke) |
| WiFi security | WPA2-PSK | WPA2-PSK |
| Creds over BLE | fallback (getters untested) | **verified** (`07/07` + `07/0e`) |
| HTTP API | `/v2` | `/v2` |
| Naming | `DCIM/DJI_001/DJI_…` | `DCIM/CAM_001/CAM_…` |
| Record stride | 361 B | 272 B |
| Low-res proxy | `.LRF` listed | `.XRF` listed |

Shared and model-agnostic: pairing (`osmo` token), the DUML frame + CRC, the `0x00/0x26`→`0x00/0x27`
list flow and its reassembly/decode, `/v2` HTTP, storage auto-detect, moov-derived resolution/duration,
and the streaming preview.

---

## 11. DJI drones (QuickTransfer) — the same stack, a different manifest

A drone reaches the *same* `0x00/0x26` → `0x00/0x27` list flow described in §7, but four things change.
Cracked from a PCAPdroid capture of DJI Fly ↔ a real Mavic 3 (2026-08-01).

| | Osmo camera | DJI drone (Mavic 3 `0x0070`) |
|--|-------------|------------------------------|
| Pairing token | `osmo` | **`DJI FLY`** — anything else is approved but gets **no WiFi creds** |
| Datalink | UDP 9004 (+poke) / 10004 | **UDP 9003, no poke** |
| List reply body | CompositePack TLV, carries paths | **flat 94-byte records, no filename** |
| Media URL | `/v2?storage=N&path=…` | **`/v1?file_index=<u32>&file_subtype=0`** |
| Thumbnails | HTTP `.scr`/`.thm` | **video:** HTTP `/v1` subtype 1 · **still:** EXIF `APP1` out of the original |
| Playback mode to page | required | **not needed** |
| Session | handshake → registration → commands | handshake → **`0x51/0x02` session-open**, before which the aircraft answers *nothing* |
| Delete / favourite | `0x00/0x28` / `0x02/0xbf` by manifest handle | **not available** — DCF records carry no handle |

The `0x4a` envelope is shared with the camera path, but note its length field is **12 bits with
`0x1000` as a FINAL-chunk flag** — reading it as a `u8` parses short frames correctly and silently
corrupts every long one, which is exactly where the manifest lives.

Record (94 B, newest first): `+0 u32` mtime · `+4 u32` size · `+8 u32` `file_index` · `+12 u16` duration
seconds (`0` ⇒ photo); fields past `+14` are unmapped. No name is transmitted;
`DCIM/100MEDIA/DJI_0554.MP4` is reconstructed from the index. Paging passes the **oldest index of the
previous page** as the cursor and the drone replays that file first, so callers dedup by index.

`file_index` is **packed, not flat** — mask the directory as 16 bits and the storage bits fold in, which
silently hides every file on internal storage:

```
bits 31:30 = storage (0 SD, 1 internal eMMC, 2 SSD)
bits 29:16 = DCF directory, 14 bits (100 -> 100MEDIA)
bits 15:0  = DCF file number (554 -> DJI_0554)
```

Full detail, ground truth and open questions: **[ROADMAP #14](../ROADMAP.md)**; implementation in
[DcfRecords.kt](../app/src/main/java/dev/konraditurbe/osmosis/dcf/DcfRecords.kt), pinned by
[DroneManifestTest](../app/src/test/java/dev/konraditurbe/osmosis/drone/DroneManifestTest.kt) against
the captured frames.
