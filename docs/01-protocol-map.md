# Osmosis — DJI Osmo Nano protocol map (BLE + WiFi)

Reverse-engineered from the five `reference/` repos (Osmo 360, Pocket 3, Action 4/5/6)
plus DJI's own docs, then **verified against a real Osmo Nano** (see findings below).
Treat the 360 as the closest sibling (WPA3 AP + internal+SD storage). Items marked ❓ are
still unverified for the Nano.

## Nano runtime findings (verified 2026-07-09, via the Osmosis app on a Pixel 10)

- **BLE advertisement**: name `OsmoNano-C2D8`; DJI manufacturer id `0x08AA`; payload
  `19 00 00 <6-byte MAC> 03`. **Model id = `0x0019`** (new; slots after A6=0x18).
- **GATT**: service `fff0` with chars `fff3, fff4, fff5, fff7`. MTU 517 negotiated fine.
  Notifications enabled on both `fff4` and `fff5`; telemetry arrives after pairing.
- **Pairing**: `SetPairingPIN` (0x07/0x45) with the 32-hex identifier blob + PackString of an
  app-chosen token (we send `"osmo"`; any value works — the moblin default was `"mbln"`) → camera
  replies `0x07/0x45` payload
  `00 01` = **ALREADY PAIRED** (or an approval popup on first use of a new token). The "pesky PIN"
  turned out to be a non-issue here — no numeric code required.
- **Internal model code = `"ow001"`** (ASCII head of the `0x00/0x81` DeviceInfo payload).
- **Post-pairing telemetry** (all SOF 0x55 DUML on the BLE channel):
  `0x02/0x80` CameraStatus (~10 Hz, ~60 B), `0x0D/0x02` gimbal/IMU, `0x00/0x81` DeviceInfo,
  `0x00/0x74` version (`29 01`), `0x00/0xF1` keepalive (`00000000`).
- **Idle drop**: the camera terminates the BLE link (`status=19`) ~5–6 s after pairing if
  the app idles. This is by design — BLE is the control channel; you're expected to move to
  WiFi. Sending `ConnectToWiFi` (0x07/0x47) both wakes the AP and keeps BLE alive longer.
- **WiFi AP**: `OsmoNano-C2D8`, **WPA2-PSK** (not WPA3 like the 360), 5.8 GHz. **Sleeps when
  idle** — must be woken per session by BLE `ConnectToWiFi(ssid, pass)` (its own creds).
  Camera IP `192.168.2.1`, client gets `192.168.2.x` via DHCP.
- **AP password is NOT obtainable over BLE (verified on Nano).** Swept the whole `0x07`
  command range (empty + 1-byte arg payloads) and searched every BLE notification for the
  known passphrase → zero matches. Command responses are only status bytes / `E0`
  (unsupported) / `"Fail"` (e.g. `0x07/4a`, `0x07/4b`). Since the AP is WPA2-PSK, the
  passphrase is mandatory and the camera never sends it — same as the 360. The passphrase
  appears **static per device** (unchanged across all sessions), so a one-time capture (user
  enters it once, or QR) suffices; it can't be pulled from BLE.
- **HTTP**: camera runs **lighttpd/1.4.55**. `GET /v2?storage={0|1}&path=<p>` is the file
  API (path=DCIM → 404, empty → 403, both proper lighttpd). **No directory-listing endpoint**
  — every non-`/v2` URL is connection-reset by the server. So the media **manifest is not
  HTTP** → use the DUML file-list over UDP 9004 (§6C), same as the 360.
- **Manifest via UDP 9004 WORKS** (verified): TCP 7001 poke → handshake → learn camera
  channel (`camCh` from routing[8:10], app seq = camCh+8) → device-info/register/init/
  subscribe → file-list request `0x00/0x26` → camera replies with type-`0x03` packets
  carrying the list. **~37 clips** read off the test unit this way.
- **Nano file naming = `DCIM/DJI_001/DJI_<YYYYMMDDHHMMSS>_<NNNN>_D.<ext>`** (e.g.
  `DCIM/DJI_001/DJI_20260329115359_0211_D.MP4`). NOTE this differs from the 360's
  `DCIM/CAM_001/CAM_…` — folder `DJI_001`, prefix `DJI_`, videos `.MP4`.
- **Storage param matters**: the file-list paths carry no storage id. On the test unit the
  media is on the **SD card = `storage=1`** (internal `storage=0` returns 404). Auto-detect
  by HEAD-probing the first file at `storage=1` then `0`, then fetch all with the winner:
  `GET /v2?storage=<S>&path=DCIM/DJI_001/…`.
- **Thumbnails** are at `MISC/THM/DJI_001/DJI_<ts>_<seq>_D.scr` (same storage id), served by
  `/v2`; the `.scr` decodes as a JPEG (renders fine via `BitmapFactory`).
- **File-list response cmd is `0x00/0x27`** (request is `0x26`). Records are delimited binary
  (protobuf-ish): a per-file KV enum block, capture timestamp, media path, proxy path, thumb path,
  and an fps rational. Strings are length-prefixed (`… <type> <len:u8> DJI_<ts>_<seq>_D.MP4 …`).
- **The `0x00/0x27` payload is fragmented with a 10-byte sub-header per frame** (found 2026-07-13,
  after a video intermittently vanished from the grid). The manifest is too big for one DUML frame,
  so it's chunked across ~17 `0x00/0x27` frames; **each frame's payload is `[10-byte sub-header][chunk]`**,
  where the sub-header is `4A 01 xx xx <seq:u16-LE @6> 00 00`. `byte1 == 0x01` marks a data chunk;
  the bracketing control frames are `4A 04…` (start) / `4A 03…` (end), 10 bytes of sub-header and no
  chunk. Concatenating the `chunk` bytes (`payload[10:]`) **in arrival order** rebuilds the real
  manifest, which opens with a `u32-LE` file count. **You must strip the sub-header before
  concatenating** — otherwise any record whose path straddles a frame boundary gets those 10 bytes
  injected mid-string (`DCIM/DJI_` + `J….001/…`), the path regex misses it, and that one file
  silently drops. Which file drops depends on packet layout, so the loss looks random run-to-run.
  Don't seq-sort across pages: on multi-page lists the counter restarts per page. See
  [`manifestBytes`](../app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt).
- **Record layout (mapped 2026-07-13 against Nano + Xtra captures)**: the reassembled manifest is
  `[u32-LE file count][record × count]`, **fixed-stride** records (Nano `DJI_` = 361 B/record,
  Xtra `CAM_` = 272 B/record — the stride is constant within a device). Each record is an **enum
  block** (holds the fps rational as `u32-LE num` + `u32-LE den` pairs, e.g. `a8 61 00 00  e8 03 00 00`
  = 25000/1000) followed by **length-prefixed strings**. The recurring per-record signature is:
  `… 0b "DJI"/"CAM" 00×9   8A 01 <idx>   … 12 01 00 13 00   0d <len:u8> <filename>   1a <len:u8> 00 00 00 01 <DCIM media path>   1a <len:u8> 00 00 00 02 <MISC/THM thumb path>`
  (`0d` = filename tag, `1a` = path tag with a 4-byte discriminator: `…01` = media, `…02` = thumb;
  an optional `LRF/LRV` proxy is a further field). **Key parsing fact: the media/thumb path fields
  carry NO extension — only the filename field does** — so a primary-extension name token
  (`_D.MP4`/`.JPG`, not `.LRF`) appears **exactly once per record**. That makes the filename the
  reliable record anchor, and the leading `u32` count a checksum.
- **Decode, don't scrape**: [`decodeManifest`](../app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt)
  anchors on each primary-extension filename, scopes that record's media/thumb/proxy paths and fps
  to its own byte window (no cross-record HashMap joins, no `±220 B` fps guess), and **asserts the
  decoded record count == the header `u32` count**. That assertion is the safety net — the dropped
  record from the sub-header bug fails the count check and logs, instead of silently shipping a short
  grid. It falls back to the older whole-blob regex scrape (`parseFlat`) for layouts that don't
  validate (unknown model, or a count that includes proxy entries). Regression-locked by
  `DatalinkManifestTest` against the real 45-record Nano and 13-record Xtra blobs.
- **fps IS in the record; resolution is NOT** (decoded 2026-07-10 against ffprobe ground truth
  across all 43 records). fps is a rational `num/den` (den ∈ {1000,1001}) just before the filename
  field — `25000/1000` = 25, `30000/1001` = 29.97; round to the nearest standard. Pixel dimensions
  appear in **no** encoding, and the KV enums can't separate resolutions (a 2.7K clip and a 4K clip
  carry identical `0x2c`/`0x36`/`0x37` values). The enum `0x36` (∈{2,6}) does **not** track fps
  either (both map to 25 fps clips). So **read resolution from the MP4 `moov`** (`tkhd` last 8 bytes
  = width,height as 16.16 fixed) — the same Range fetch already used for duration.
- **Size/duration are NOT in the file list** (byte-diffed newest vs oldest against ground truth:
  3.58 MB vs 3.04 GB, 0.92 s vs 11.3 min — no field matches, any unit). Get size from HTTP `HEAD`
  (`Content-Length`) and duration/resolution from the MP4 `mvhd`/`tkhd` via Range requests
  (`HttpURLConnection` respects the process network binding, unlike `MediaMetadataRetriever`).
- **Low-res preview proxies**: the Nano lists a `.LRF` proxy clip per video in the manifest
  (`DCIM/DJI_001/DJI_<ts>_<seq>_D.LRF`, a 960×720 MP4 with `moov` at the **end**; size scales with
  clip length — ~0.6 MB for ~1 s up to ~95–190 MB for long clips). **Read the proxy path from the
  manifest, don't derive it** — extension/availability vary by model (`.LRF` vs `.LRV`, or none;
  fall back to the full-res file). Preview by **streaming**:
  `VideoView.setVideoURI("http://192.168.2.1/v2?…&path=<proxy>")`. Native `MediaPlayer` HTTP **does**
  honour `bindProcessToNetwork` (corrects an earlier assumption drawn from `MediaMetadataRetriever`),
  so it reaches the internet-less AP and range-fetches the `moov` off the end — any length, full
  scrub, zero download. Gotcha: a `visibility=gone` `VideoView` has no Surface and **never**
  prepares (no `onPrepared`/`onError`) — make it visible before `setVideoURI`.

## Xtra Edge Pro (= DJI Osmo Action 5 Pro) — deltas from the Nano

The "Xtra" brand is a covert DJI shell company; the Edge Pro is a rebadged Action 5 Pro on
DJI firmware. Verified via the app (`reference/xtra/`, unpacked, string/dexdump sweep) + live.

- **BLE brand tells**: MAC OUI **`EC:9E:EA`** (Xtra's own), manufacturer company id **`0xAAF7`**
  (vs DJI `0xAA08`), BLE model byte **`0x15`** (Action 5 Pro). Name `XtraEdgePro-XXXX`.
- **Pairing identical**: `SetPairingPIN("osmo")` → `ALREADY PAIRED`.
- **WiFi**: SSID = BLE name, WPA2, `192.168.2.x`. Woken by `ConnectToWiFi`.
- **DUML data channel differs**: TCP 7001 REFUSED, UDP-9004 handshake FAILS (unlike Nano). Its
  file-list DUML runs in native `libdjisdk_jni.so` (`native_*file_list*`), not in the base APK.
- **HTTP media API is `/v1`, not `/v2`**:
  `GET http://192.168.2.1/v1?file_index=<N>&file_subtype=<T>&file_seg_subindex=<S>`
  (`file_subtype=0` = origin/full). Extensions: `.mp4 .mov .dng .jpg .pano .tiff .osv .scr .thm .lrf`.
- **File list = local SQLite `files` table** (`_index`=file_index, `seg_sub_index`, `file_type`,
  `file_name`, `length`, `media_type`, …) from the native list.
- **Live test (this unit)**: ports **21/80/5000** open (NOT 7001/9004). lighttpd/1.4.55 serves
  `/v2?storage=&path=` (404/403 as expected); **`/v1` is connection-reset** (it's for other
  models) — so THIS camera is a `/v2` server like the Nano. Only the *list* is blocked.
- **Media preview (verified live 2026-07-10)**: this unit's manifest lists **no proxy clips**
  (`media exts=[JPG, MP4]`, `proxies=[]`) even though `.lrf` appears in the app's abstract
  extension list — so there's no `.LRF` to stream like the Nano. The preview falls back to
  **streaming the full-res `/v2` MP4**, which `MediaPlayer` plays fine off the AP (prepared at
  native `2688×2016`). Resolution/fps come from the same sources as the Nano (moov `tkhd` + the
  manifest fps rational). Naming is `DCIM/CAM_001/CAM_<ts>_<seq>_D.MP4`.
- **Data-link is native UDP** (`xtra/sdk/datalink/wifi/UDPSocketClient` + `MgDatalinkHelper`),
  with ports assigned by the native lib (`JNIRawData.native_StartWifiServicePort` /
  `native_GetWiFiPortFdSet`) — not the fixed UDP-9004 the Nano uses, and not derivable from the
  base APK's Java. There's also a datalink-version switch (`native_changeDatalinkUpgradeToV1`).
  **Replicating the Action-5 file list SDK-free needs `libdjisdk_jni.so`** (in the
  `config.arm64_v8a` split, absent here) or the exact UDP port+handshake it uses.
- **Datalink port = UDP `10004`** (NOT 9004!) — found via a PCAPdroid capture of the official app
  (`reference/xtra/PCAPdroid_09_Jul_*.pcap`). The handshake payload is **identical** to the
  Osmo's (`…seed… 64 00 64 00 c0 05 …`); only the port differs (Osmo=9004, Action 5=10004). Our
  earlier probe swept 9004/9003/…/12345 but never 10004. Fix: try the handshake on 9004 **and
  10004**, run the same file-list flow on whichever answers. (PCAPdroid's VPN broke the LAN
  return path, so the capture shows only outbound + the app re-handshaking — but the port and
  bytes are exactly what we needed; a direct socket has no such issue.)
- Earlier dead ends (before the pcap): 7001 refused, 5000 = JSON RPC "Bad command", /v1 reset.

## 0. The two ground-side protocols (don't confuse them)

Both ride the **same GATT service `fff0`** and are demuxed by the first byte (SOF):

| SOF | Protocol | Used by | Our use |
|-----|----------|---------|---------|
| `0x55` | **DUML** (the one you know from drones) | DJI Mimo app, all `reference/` BLE repos | ✅ this is our path |
| `0xAA` | **R-SDK** (CRC16+CRC32, `0x00/0x19` connection-request pairing with numeric `verify_data`) | `Osmo-GPS-Controller-Demo` (RC/accessory SDK) | ❌ Nano "not supported yet", and it's control-only (no media) |

We use DUML end-to-end. R-SDK is documented only so you recognize `0xAA` frames if the
Nano emits them.

## 1. What's different from drone-side DUML

You know the frame; here are the ground deltas so nothing surprises you:

- **Transport is GATT, not a serial/UART link.** Service `fff0`:
  - `fff4` — write `[0x01,0x00]` here to *arm pairing*; also delivers notifications.
    On Pocket 3 **all** notifications (telemetry + responses) arrive on `fff4`, not `fff5`.
  - `fff5` — write DUML commands here, **Write-Without-Response**. (Writing DUML to `fff3`
    is silently dropped — a documented footgun.)
  - Must raise MTU (request 517); notifications reach ~256 B.
- **BLE DUML framing is the standard 11-byte header**, but two quirks:
  1. The 16-bit field at bytes `[6:8]` is a **message id that is BIG-ENDIAN** on BLE
     (verified in 3 independent impls). On the UDP-9004 path it behaves as the usual LE seq.
  2. `proto_ver` nibble = 1, so byte[2] reads `0x04` for lengths < 256.
- **App-level pairing** replaces OS Bluetooth bonding (there is no numeric BT pairing).
  See §3 — this is the "pesky PIN".
- **The media/data channel is WiFi, not BLE.** BLE only wakes the camera, negotiates
  pairing, and turns the WiFi AP on. Bulk data (file list, thumbnails, files) goes over
  the camera's WiFi AP at `192.168.2.1`.

## 2. DUML frame (SOF 0x55) — exact bytes

```
off sz  field
0   1   SOF = 0x55
1   1   len_lo               ) total_len = 13 + payload_len (incl. both CRCs)
2   1   (ver<<2)|len_hi[9:8] ) ver=1  -> byte reads 0x04 when len<256
3   1   CRC8 over bytes[0:3]
4   1   sender   = (sender_id<<5)|sender_type    ; App = 0x02  (id0,type2)
5   1   receiver = (receiver_id<<5)|receiver_type
6   2   msg id / seq   -- BLE: BIG-ENDIAN id ; UDP9004: LE seq
8   1   cmd flags = (cmd_type<<5)|encrypt
        0x40=request(cmd_type2) 0xC0=response(cmd_type6/ack) 0x00=notify
9   1   CmdSet
10  1   CmdId
11  N   payload
11+N 2  CRC16 over bytes[0 : 11+N]  (LE)
```

Device address nibbles (byte = `(id<<5)|type`): App `0x02`, Camera `0x01`, Gimbal `0x03/0x04`,
WiFi subsystem `0x07`, DM36x media proc `0x08` (`0x28`=id1, `0x48`=id2). "Target 0x0702" in the
osmo repos == sender App(02) → receiver WiFi(07).

### CRC (identical to drone DUML; two equivalent parameterizations)
- **CRC8**: reflected, `init=0x77 poly=0x8C`  ≡ spec `init=0xEE poly=0x31 refin/refout`.
- **CRC16**: reflected, `init=0x3692 poly=0x8408` ≡ spec `init=0x496C poly=0x1021 refin/refout`.
- `reference/dji-remote/.../DjiCrc.kt` is a correct Kotlin impl **with unit tests** — we vendor it.

`PackString(s)` = `[len:u8][utf8 bytes]`. Used for identifier / PIN / SSID / password.

## 3. Pairing — the "pesky PIN" (BLE, CmdSet 0x07)

Sequence (App → Camera unless noted):

```
1. write [0x01,0x00] -> fff4                          arm pairing
2. SetPairingPIN     flags0x40 07/45  -> WiFi(0x0702)
     payload = PackString(identifier) + PackString(pin)
3. <- PairingStatus  flags0xC0 07/45  payload:
        0x00 0x01 = already paired    -> done (proceed to WiFi)
        0x00 0x02 = approval required -> camera shows the app's PIN token on screen for approve/deny
4. (if 0x02) user approves on camera
   <- PairingPINApproved flags0x40 07/46  payload 0x01   ← arrives as a REQUEST, not a 0xC0 response
   -> ack it: flags0xC0 07/46 payload 0x00  (the generic flags-0x40 auto-responder covers this)
      then proceed to WiFi exactly as the 0x01 path — the approval REQUEST is the "go" signal
```

**First-time flow confirmed on the Xtra Edge Pro / Action 5 Pro (2026-07-13, factory-reset unit).**
A fresh camera returns `0x0002` and **displays the app's PIN token verbatim on its screen — it showed
`OSMO`, the exact string we sent** — for a plain approve/deny. So the "PIN" is *not* a camera-generated
code the user types back; it's the app-chosen token echoed for confirmation. `PairingPINApproved`
comes in as a **flags-0x40 request** (`0x07/46` payload `0x01`), so the handler must treat that request
as pairing-complete and start offload — not just the `0x45=0x01` fast path (which is all the
already-paired case ever exercises). Once approved, the camera remembers the token and answers `0x01`
silently thereafter.

`identifier` = a **stable per-install string the app invents** and reuses so the camera
remembers us ("already paired" next time). Two shapes seen in the wild, both accepted —
15-digit (`001749319286102`) and 32-hex UUID (`284ae5b8d76b3375a04a6417ad71bea3`). We'll
generate a UUID once and persist it.

`pin` — **RESOLVED (Xtra, 2026-07-13): hypothesis (a).** The token is app-chosen; the camera just
displays it (we send `osmo`, the screen shows `OSMO`) and the on-screen *approve* tap is the real gate.
No numeric PIN is generated by the camera, so no PIN-entry UI is needed — any stable token works.
- (a) ✅ **Vestigial/echoed token** — confirmed above. `osmo` is fine.
- (b) ~~Real numeric PIN echoed back~~ — ruled out on the Xtra; assume the same on the Nano.
- (c) Uncatalogued status → still log raw and iterate if a different model surprises us.

Osmo360 note: fresh pairing returns only `0x01` after approval and never leaks the WiFi
password over BLE (36 KB of notifications scanned). Assume the same on Nano until disproven.

## 4. WiFi AP activation (BLE, CmdSet 0x07)

| dir | flags | set/id | name | payload / notes |
|-----|-------|--------|------|-----------------|
| →   | 0x40 | 07/44 | GetWifiApStatus | 360 returns 3 bytes ❓ |
| →   | 0x40 | 07/07 | **GetWifiSsid** | reply `[status:1][PackString ssid]` (`00 12 "XtraEdgePro-2DCA16"`) |
| →   | 0x40 | 07/0e | **GetWifiPassword** | reply `[status:1][PackString passphrase]` — the AP password, over BLE |
| →   | 0x40 | 07/0c | GetWifiMac | reply `[status:1][6-byte MAC]` |
| →   | 0x40 | 07/47 | ConnectToWiFi   | `PackString(ssid)+PackString(pass)`. On the **360 this activates the camera's own AP** (you send it its own creds). AP comes up ~15 s later. |
| ←   | 0xC0 | 07/47 | WiFiConnectResult | `0x0000` = ok |
| →   | 0x40 | 07/AB | ScanWiFi (client mode) | list of nearby APs — not needed for offload |

SSID pattern (360) `Osmo360-XXXX` (XXXX = serial tail); Nano pattern: `OsmoNano-XXXX`.
**WiFi creds ARE retrievable over BLE** (found 2026-07-14 by HCI-snooping the official Xtra app):
query `07/07` (SSID) then `07/0e` (password) after pairing — the camera returns them, no on-screen
reading needed. **Pace the two queries** (~500 ms apart): `fff5` is write-without-response, so a
back-to-back second query drops, and the first must not race the pairing-approval ACK. The old
`credprobe` sweep missed these because it swept `0x40–0x5F`, not the low `07/07`/`0c`/`0e`. (The 360
note "not retrievable over BLE" predates this — likely wrong; the getters were just never tried.)

## 5. WiFi join on Android (the clean replacement for macOS `networksetup`)

- API 29+ `WifiNetworkSpecifier` → `ConnectivityManager.requestNetwork(...)` with a
  `NetworkCallback`. On `onAvailable(network)` call `bindProcessToNetwork(network)` so our
  HTTP/UDP sockets use the camera AP even though it has **no internet** (otherwise Android
  bails to cellular). This is very likely how Mimo "connects without typing the password" —
  companion WiFi association, WPA passphrase supplied programmatically.
- Try `setWpa3Passphrase` first (360 is WPA3-SAE), fall back to `setWpa2Passphrase`. Nano uses `setWpa2Passphrase`, not WPA-3.
- Camera IP `192.168.2.1`, client gets `192.168.2.x`.
- **Keepalive**: AP drops after ~10 s idle; camera auto-sleeps. Keep one of: active HTTP
  download, UDP-9004 handshake ping every 2 s, or a TCP-7001 DUML heartbeat.

## 6. Media manifest — three candidate paths (probe in this order)

The manifest (list of files) is the immediate goal. We don't yet know which the Nano
exposes, so the app probes cheapest-first:

**(A) DUML file-list over BLE** — cheapest (no WiFi). Try sending file-list DUML on `fff5`
and read notifications. Likely too low-bandwidth for the full list but worth a shot for a
count/first page. Commands to try: `0x00/0x26` (see C), and camera-set `0x02` media cmds.

**(B) HTTP over the AP** — once joined, the 360 serves files via lighttpd:
```
GET /v2?storage={0|1}&path=DCIM/CAM_001/CAM_YYYYMMDDHHMMSS_NNNN_D.ext   (0=internal 1=SD)
HEAD supported (size), Range supported (206 partial)
```
There is **no documented directory-list endpoint** on the 360 (osmo-download resorts to
HEAD-probing known timestamps). On the Nano, **probe for a listing endpoint** first:
`/`, `/v1/`, `/v2?...`, `/dcim/`, `/DCIM/`, `/desc/`, `/api`, common lighttpd/DJI paths.

**(C) DUML file-list over UDP 9004** — the proven 360 path. Full sequence, then scrape
`CAM_…` paths from response bytes:
```
UDP pkt = [8B udp hdr][12B routing hdr][DUML frame]
 udp hdr : (0x8000|totlen):u16le  session:u16le  seq:u16le  type:u8  xor:u8
   type 0x00 handshake / 0x01 telemetry / 0x03 acked-data / 0x04 ack / 0x05 command
 routing : last_cam_seq:u16le  this_seq:u16le  0x00000000  counter:u8  01 00 00
 seq rule: app UDP seq MUST start at (camera_channel + 8); camera_channel = heartbeat routing[8:10]

 flow: TCP7001 send pair-DUML -> UDP handshake (40B payload) -> drain heartbeats/learn channel
   -> devinfo 0x00/0x81->DM368:2 (cmd_type4) -> register 0x00/0x88->DM368:1
   -> init 0x03/0xDA->Gimbal -> subscribe 0x00/0x99->DM368:1 (per-param) -> list 0x00/0x26->Camera:0
 list req payload (page1):
   4a00 2a10 010000000000 01000000 2d000d0100 ffffffffffffffff 0001000000000000 000000
 responses: 1000–1200B packets; file paths appear as ASCII:
   DCIM/CAM_001/CAM_YYYYMMDDHHMMSS_NNNN_D            (+ .osv 360video / .jpg / .mp4 / .dng / .lrf)
   MISC/THM/CAM_001/CAM_YYYYMMDDHHMMSS_NNNN_D.scr    (thumb)
```
osmo-download only regex-scrapes these (`CAM_\d{14}_\d{4}_D\.\w+`); the real `0x00/0x26`
response TLV structure is undocumented — an opportunity to parse it properly.

## 7. Reusable, already-correct code in `reference/`

Pure-Kotlin, no Android/Compose deps → **vendor into Osmosis with attribution**:
- `dji-remote/.../DjiCrc.kt` (+ `DjiCrcTest.kt`) — CRC8/CRC16
- `dji-remote/.../ByteReader.kt`, `ByteWriter.kt` — LE/BE/u24 helpers
- `dji-remote/.../DjiMessage.kt` (+ `DjiMessageTest.kt`) — DUML encode/decode
- `dji-remote/.../DjiPayloads.kt` — `djiPackString`, pairing/wifi payload builders

Python (portable logic, not code) for the UDP-9004 client + file-list scrape:
- `osmo-download/.../file_list.py`, `duml.py`, `http_client.py`, `ble.py`.
```
