# Osmosis — Roadmap

Planned work, in rough priority order. For the reverse-engineered protocol details referenced
below, see [docs/01-protocol-map.md](docs/01-protocol-map.md).

**Working today:** Osmo Nano (datalink UDP 9004) and Xtra Edge Pro / DJI Action 5 Pro
(datalink UDP 10004) — full pipeline: BLE pair → wake AP → media list → thumbnail grid →
LRF preview + download queue → resumable high-res `/v2` downloads. fps **and byte size** come from the
DUML manifest (no HTTP `HEAD`); resolution from the MP4 `moov`. Also shipped (v0.5): **delete a file off
the camera** (long-press → confirm, DUML `0x00/0x28` — #4) and **R-SDK GPS sync** (🛰️ toggle, streams the
phone's GPS into the camera over BLE — #9, hardware-verified on an Action 5 Pro).

---

## 1. In-preview trimming → trimmed high-res download — ✅ DONE (2026-07-10)

Set in/out points on the paused preview scrubber (`[` / `]` buttons); "Add to Queue (trimmed)"
queues that window, and Download writes just that slice of the **high-res** clip to the gallery
(e.g. `DJI_…_0247_D_28-48s.MP4`). Never the LRF, never the whole file. Verified on the Nano.

**How it works:** reverse-engineering the Xtra app settled the key question — the camera has **no
server-side trim**. Its download URL builder (`HttpUrlHelper`) only takes
`file_index`/`file_subtype`/`file_seg_subindex`, no time range; `clip_start`/`clip_end` live in the
local segment *editor*, not the download path. So the cut is client-side, but still only pulls the
window off the camera: `MediaExtractor.setDataSource(<full-res HTTP URL>)` →
`seekTo(startUs, PREVIOUS_SYNC)` → stream-copy samples into a `MediaMuxer` MediaStore file until
`endUs`. MediaExtractor honours the process network binding and range-fetches, so only the window's
bytes (+ `moov`) come off the AP — no whole-file download, no MP4 surgery. Stream copy = no
re-encode; the start snaps to the nearest keyframe ≤ the in-point (frame-accurate would need
re-encoding, deliberately out of scope). Code:
[MediaDownloader.downloadTrimmed](app/src/main/java/dev/konraditurbe/osmosis/net/MediaDownloader.kt),
[MediaPreviewActivity](app/src/main/java/dev/konraditurbe/osmosis/ui/MediaPreviewActivity.kt).

## 2. Support the rest of the Osmo line — ⚙️ framework done, verification hardware-gated (2026-07-10)

Built a per-model capability table
([CameraModel](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt)) keyed on the BLE model
byte: datalink port + TCP-poke, WiFi security, and a `verified` flag. The runtime resolves it from
the scan ([OsmoScanner](app/src/main/java/dev/konraditurbe/osmosis/ble/OsmoScanner.kt) now passes the
model byte up), so port/poke/WPA come from the **model, not the brand** — the Xtra resolves to
Action 5 Pro (`0x15`) → 10004 by its model byte, exactly as a DJI-branded Action 5 would.
Unrecognized models fall back to the common config (9004 + poke + WPA2) so **any DJI Osmo is
attempted, not refused**; the selector tags unverified models `~experimental`.

Status per model:
- **Verified on hardware:** Osmo Nano (`0x19`, 9004 + poke, WPA2) and **Xtra Edge Pro** (`0x15`,
  **10004, no poke**, WPA2).
- **⚠️ Correction (2026-07-23):** we previously listed "Osmo Action 5 Pro" as verified on 10004, but
  that was **only ever confirmed on the Xtra Edge Pro** — a covert DJI rebrand which advertises the
  *same* model id `0x15` yet has its **own OUI `EC:9E:EA`**. The port change looks like a rebrand
  firmware change, not a DJI one, so a **genuine DJI Osmo Action 5 Pro is now treated as unverified**
  and gets the DJI-standard 9004 + poke. Resolution is brand-aware
  ([CameraModel.resolve](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt) takes
  [Brand](app/src/main/java/dev/konraditurbe/osmosis/ble/Brand.kt)), pinned by
  [CameraModelBrandTest](app/src/test/java/dev/konraditurbe/osmosis/ble/CameraModelBrandTest.kt).
  Since either guess can be wrong on an untested unit, the datalink now **retries the alternate
  config** (9004+poke ⇄ 10004/no-poke) when the handshake doesn't land, and logs which port answered
  — so any test on a real Action 5 Pro / Action 4 / Action 6 self-corrects *and* reports the truth.
- **✅ Port confirmed (2026-07-24):** a **genuine DJI Action 5 Pro, Action 6, and Pocket 3 all
  handshake on `udp/9004 + poke`** (tester log, real hardware). That settles the OA5 question — the
  brand-aware guess was right, and **`10004` is Xtra-exclusive**. The OA6 model byte `0x18` is also
  confirmed on hardware (mfr `1800…`).
- **⚙️ Media list, unsolved per model (2026-07-24):** handshake ≠ working grid.
  - **Action 5 Pro / Action 6:** list request answered but decoded **0 files** (`declared=0`, no
    paths in a 375–445 B manifest) — a list layout our `0x00/0x27` parser doesn't read.
  - **Pocket 3:** lists **15 paths** but with **no filename/extension token** (`media exts=[]`), so
    the grid has no thumbnails, size shows "—", preview says "No preview for ." and download 404s on
    the extension-less path. `/v2` itself works (it 404s, not refuses).
  - Both are blocked on the raw manifest bytes; the datalink now **hex-dumps an undecodable manifest**
    to the log ([dumpManifest](app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt)) so a
    single test run captures the format. Paths/filenames only — safe for the shared log.
- **Osmo 360 (`0x17`):** the **WPA3-SAE join failed** on the test phone (Android 10 tablet) so it
  never reached the datalink; port/format still unknown. The join now **retries once as WPA2** on that
  failure, which also self-corrects the table if the 360 is actually WPA2.
- **Best-effort default (no data source):** Osmo Action 2 / 3 / 4 — Mimo cameras that get the
  9004/poke/WPA2 default and the `~experimental` tag. Confirming each needs a PCAPdroid capture of
  Mimo or the unit itself.

Shared, already model-agnostic: pairing (`osmo` token), `/v2` HTTP, `DJI_`/`CAM_` naming, storage
auto-detect, and the preview/trim/stream flow. **Remaining:** the per-model **media-list layouts**
above (OA5/OA6 empty-decode, Pocket 3 missing extension), and the 360's datalink once it joins.

**Onboarding UI (2026-07-10):** the app now launches into a **camera selector**
([SavedCameras](app/src/main/java/dev/konraditurbe/osmosis/core/SavedCameras.kt),
[CameraListAdapter](app/src/main/java/dev/konraditurbe/osmosis/ui/CameraListAdapter.kt)) — saved
cameras first (📶 in range / 🚫 out of range, from the live BLE scan), then newly-scanned ones
tagged **NEW**. Tapping an in-range camera connects → grid; a first-time camera prompts for the WiFi
password once and is remembered per-MAC (with its model byte, so the Pocket 3's name-fallback
survives). Long-press → re-enter password / forget. If the camera drops the BLE link while the
gallery is open (status=19, vs. the benign handoff status=8), the session tears down and returns to
the selector, which rescans and shows it 🚫.

## 3. Retrieve the WiFi password over BLE — ✅ DONE (2026-07-14)

The camera hands out its own AP SSID + passphrase over BLE once paired — no manual entry needed.
Cracked by **HCI-snooping the official Xtra app** (`adb bugreport` → `btsnoop_hci.log`), which
revealed a credential getter in the WiFi cmdset `0x07` that we'd missed:

| cmd | request | response payload |
|-----|---------|------------------|
| `0x07/0x07` | GetWifiSsid | `[status:1][PackString ssid]` |
| `0x07/0x0e` | **GetWifiPassword** | `[status:1][PackString passphrase]` |
| `0x07/0x0c` | GetWifiMac  | `[status:1][6-byte MAC]` |

Why the earlier `credprobe` sweep missed it: it swept `0x40–0x5F`, but the getters live at the
**low cmdIds `0x07`/`0x0c`/`0x0e`**. The creds are never pushed unsolicited (nothing during pairing) —
you have to query them.

**Implementation** ([`onPaired`](app/src/main/java/dev/konraditurbe/osmosis/ui/MainActivity.kt)): after
the `0x07/0x45`/`0x46` pairing completes, query `0x07/0x07` then `0x07/0x0e`, parse the
`[status][PackString]` replies, and feed SSID/password straight into the WiFi join (the native
`WifiNetworkSpecifier` "join XtraEdgePro-…" dialog). **Pacing matters**: `fff5` is
write-without-response, so the two queries must be spaced out (~500 ms) or the second drops — and the
first must not race the pairing-approval ACK. Falls back to the saved password / one-time prompt for
models that don't answer. The retrieved passphrase is cached per-MAC and **never logged or committed**
(only its length is logged). Verified on the Xtra Edge Pro / Action 5 Pro: fresh camera →
approve → creds over BLE → grid, zero manual entry.

- **Untested:** whether the Nano/360 answer the same `0x07/0x07`/`0x0e` getters (they likely do — we
  only ever swept the wrong range there too). The fallback keeps them working regardless.
- **Note:** the R-SDK/accessory family (`Osmo-GPS-Controller-Demo`) uses a different pairing with
  numeric `verify_data` and is control-only — not a media/credential path.

## 4. Delete a specific file on the camera — ✅ DONE (2026-07-21)

Long-press a grid cell → confirm → the file is deleted off the camera's card. Verified on **both**
the Osmo Nano and the Xtra Edge Pro / Action 5 Pro (`status 0x0000`, file gone). Irreversible, so it's
behind an explicit confirm dialog showing the filename + handle, and only offered for files we could
resolve a handle for.

**What cracked it:** a live **Mimo↔Nano capture** (PCAPdroid on an Android 10 tablet — the "infeasible"
note was wrong; it grabs the UDP/9004 datalink DUML in the clear). The RE had it right: delete is
`SendCompositePack<delete_file_req, MediaFile>` in the **same `0x00` cmdset as the list** — the missing
byte is **cmdId `0x28`** (list = `0x00/0x26`, delete = `0x00/0x28`).

- **Wire:** `0x00/0x28`, App→Camera. Payload `[count:u8][handle:u32-LE …][count:u32] 00 [count:u32]
  01 01 00 00` (the tail is a storage selector, verbatim from the capture); reply `0x00/0x28` = `0000`
  = OK. For one file both counts = 1.
- **Handle:** a per-file object id at the **head of each manifest record**, read straight from the list
  we already fetch. Anchored on the constant record marker `03 ff 19 06` (`handle = u32 at marker-8`) —
  works for both layouts: Nano (`DJI_`, 361-byte records, handles step `0x40`) and Xtra/Action (`CAM_`,
  272-byte records, handles step `0x10`). A `0x40`-alignment heuristic worked on the Nano but grabbed a
  stray dword on the Xtra → `0xd6` "no such handle"; the marker anchor fixed it.
  ([DatalinkClient.recordStart / deleteFiles](app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt))
- **The catch — writes need a fresh session.** The browse keep-alive loop advances our `udpSeq` past the
  window the camera accepts; reads still get answered but writes are silently dropped, and re-registration
  itself seems to grant write access. So a delete tears the keep-alive session down and re-opens +
  registers a fresh datalink (the same path the list fetch uses), issues the delete there, then keeps that
  session for browse. Cost ≈ **9 s** per delete (the re-handshake; the delete itself is ~0.2 s); the
  status pill freezes until the next reconnect (the delete session skips status subscriptions).

**Known gaps:** **photos (both cameras) are non-deletable by design.** Video records carry the
`03 ff 19 06` marker we anchor the handle on; photo records don't have it and lay out differently (the
handle sits elsewhere, e.g. ~120 B before the name). Widening the search to reach it would instead latch
onto the *neighbouring video's* marker → a wrong-file, irreversible delete, so we fail-safe to "can't
delete" instead. (The 400 B window is tuned for this: it reaches a video's own marker at ~195 B but stops
short of the next record's at ~473 B.) Safe photo support needs a dedicated photo-record anchor verified
on both families. Also: delete is single-shot per handle (a delete reshuffles the camera's table, so we
zero the remaining handles and require a reconnect to delete more). Batch/multi-select is a follow-up.

## 5. Osmo Nano: dock / power stats — ✅ DONE (2026-07-23)

The status pill now shows the camera's **pack voltage, charge/draw current, and whether it's docked /
charging** (⚡ next to the percentage, plus a `4.42 V · charging 352 mA` line). Decoded from the
`0x0d/0x02` battery push, which we were previously reading exactly one byte of.

**The dock's own battery % is NOT exposed by the camera** — that part of this item is closed as
*not possible*, not as *not done*. All three plausible homes were checked and excluded:

| hypothesis | result |
|---|---|
| dock is its own DUML device (a second battery address) | ❌ docked vs undocked shows **only** `type 0x05, id 0` |
| dock appears in the subscribable params | ❌ all **53** params enumerated; none battery/dock-related |
| dock charge is a field in the battery frame | ❌ nothing in the 34 bytes tracks it (dock was at 25 %) |

**What the battery frame does carry** — mapped by docking/undocking mid-session and watching which
bytes moved (far stronger than comparing separate connections, since only the dock state varies):

| offset | field | evidence |
|--------|-------|----------|
| `u16 @1` | pack voltage (mV) | 4421 charging ↔ 4297 under load |
| `i32 @5` | current (mA, signed) | **+372/+352/+272 docked**, **−455/−474 on battery** |
| `@20` | battery % | (already used) |
| `u16 @17` | temperature? (45.0/47.0 °C) | plausible, unconfirmed |
| `@27` | **dock attached** | `64` whenever physically docked |
| `@32` | **charging** | `1` docked / `0` not, across 3 cycles |

`@27` and `@32` are genuinely different signals: one transition showed `@27=64` (attached) with
`@32=0` and only −175 mA — docked but not yet drawing charge. Hence `docked` and `charging` are
separate flags in [CameraStatus](app/src/main/java/dev/konraditurbe/osmosis/core/CameraStatus.kt).

**Still open (was the other half of this item):** *dock SD-card space*. The `0x02/0x80` frame reports
the **active** store only; selecting/querying the dock's store is untouched and independent of the
battery work above.

**How the "dock is a separate device" theory died:** a temporary probe logged every DUML device address
(`(id << 5) | type`, from the sender byte) the first time it spoke, on both transports, across docked
and undocked connections. No second battery (`type 0x05`, `id != 0`) and no new address ever appeared —
the dock speaks only *through* the camera's own battery frame. The probe was removed once it had
answered that; it's in git history if a new accessory ever needs identifying.

## 6. Support the older Osmo Action generation (index-based list) — ⏸️ PARKED (2026-07-15)

The Osmo Action (1) connects but its media list decoded empty. The **list is solved and shipped**;
the **download is understood in outline but not confirmed on this camera**. Parked here for a clean
resume. Diagnosis came from an app log + a decrypted **RTOS** log. Below, findings are split by confidence.

### ✅ Proven (hardware-verified and/or cross-confirmed against the camera's RTOS log)
- **Connection is fine**: BLE pair → WiFi join → datalink handshake all succeed on the Action 1.
- **The camera sends the list successfully**: RTOS `FileNumToSend: 7, SizeToSend: 463` →
  `SendList Frag Finish, FileSended: 7`; our app receives exactly those 463 B.
- **The list is an older index-based format**: reassembled `0x00/0x27` = `[u32 count][u32 total_size]`
  then fixed **65-byte records** (8 + 7×65 = 463), no path/filename strings. Per record:

  | offset | type | field |
  |--------|------|-------|
  | `[0:4]`   | u32-LE   | Unix timestamp |
  | `[8:12]`  | u32-LE   | **FileIndex** (`0x640251`…`0x640241`) |
  | `[10:14]` | 2×u16-LE | DCF dir / file number (`100` = `100MEDIA`) |
  | `[19:23]` | u32-LE   | video UUID (Amba `DjiMovDmx`) |
  | `[38:42]` | u32-LE   | size-ish (~KB; a photo record reads ~0.6 MB) |

  Cross-confirmed: RTOS `FileIndexSending: 0x640251`(first)/`0x640241`(last) and all 7
  `current video uuid:0x…` match our decode exactly, in order.
- **List parser shipped + hardware-verified** (`decodeIndexList`; round-3 grid shows all 7 clips) and
  unit-test-locked (`DatalinkManifestTest`, fixture `action1_7.bin`). Collect-loop early-exit fixed to
  count the index header.
- **HTTP `:80` is refused** on the Action while WiFi + datalink are both up (round-3 `ConnectException`).
- Paging bug: our `batch==2` `0x40` offset → RTOS `GISInfo Offset out of range` (harmless — page 1
  already had all 7 — but wrong for index lists).

### ⚠️ Inferred (NOT verified on the Action 1, which is an older/different generation)
- Older cameras serve media over **static lighttpd/1.4.55 on `:80`** (`libdcam_http_server.so`,
  `document-root=/mnt/media_rw/emulated`) as **plain DCF paths** (`GET /DCIM/100MEDIA/DJI_XXXX.MP4`) —
  **not** `/v1?file_index=` or `/v2?storage=` (those are newer-camera API wrappers). Dir-listing is
  disabled, so filenames can't be browsed.
- The HTTP server is **state-gated**: enabled by the `duss_proxy` plugin (`http_server_enable=1`,
  `activate_check=true`); `dji_media_server` handles `enter_playback` / "switch workmode". So `:80`
  should only listen once the camera is **activated + in playback mode** → the likely cause of the
  `:80` refusal.
- ⇒ our round-3 `/v1?file_index=` download is almost certainly wrong; the real flow is probably a
  workmode-switch DUML command → static DCF `GET`, with the filename built from the record's DCF
  dir/file.
- **Core open question:** is the file *transfer* actually HTTP (static, post-workmode) or DUML over the
  datalink (`DjiTransSrv`, the same service that sent the list)? Both are plausible; unverified.

### ⬜ Remaining (to tackle on resume)
- **Confirm on the Action 1 itself** — the transport, the workmode/playback trigger, and the exact URL +
  filename. Cleanest source: **DJI Mimo** (the app that does this). *Blocked* one WiFi capture of Mimo↔Action hands us the command + URL directly.
- Deep-RE fallback: Ghidra the camera-service binaries for the `enter_playback`/workmode DUML
  cmd.
- Rework the index-camera download from the placeholder `/v1?file_index=` to the confirmed mechanism.
- Fix the AP keepalive (doesn't hold the Action's AP; `onLost` ~40 s after the list).
- Skip the `/v2` storage auto-detect for index-based cameras (it fires two failing HEADs).

**Resume artifacts:** `reference/osmo-action/` (raw list blob + RTOS log).

## 7. Retrieve highlight / moment markers — 🔬 RE'd to the SDK, cmd id blocked — ⏸️ PARKED (2026-07-15)

DJI "highlight" marks (side-button presses during recording) are **NOT stored in the MP4** — proven
by an exhaustive teardown of an Action 4 + Xtra clip: not in chapters/tags, not in the `dvtm` protobuf
metadata track (per-frame telemetry only — orientation quat, ISO, shutter, WB), not in the `dbgi`
(sensor-debug) track, not in `udta`, not as HEVC SEI. They live **on the camera** and are pulled on
demand — confirmed because the Xtra app shows them in its *trim/editor* view (not playback).

**Mechanism (RE'd from `libxtrasdk_jni.so`):**
- KeyHandler **`PullHighLightAction`** → `SendGetPack<xtra::core::set_camera_expansion_cmd_pack>` — a
  **generic `camera_expansion_cmd`** DUML command with **sub-type `0x4`** (from the disasm:
  `orr w1, wzr, #0x4`). The same generic cmd serves `PullSuperSlowMotionPointAction`,
  `PanoFusionTypeGet`, etc. — different sub-types.
- Response `camera_expansion_cmd_rsp` → `xtra::sdk::HighLightMsg` = a list of
  **`HighLightItem { startTimeMs, duration }`** (each mark is a time *range*, not a point). Surfaced to
  Kotlin as `LctHighLightMsg`/`LctHighLightItem` (`getHighLightStartTimeMs`/`getHighLightDuration`).
- **Read-only** (unlike delete) → safe to probe empirically once the cmd is known.

**Missing:** the numeric **cmdset/cmdid of the generic `camera_expansion_cmd`** (buried in the pack
serialization, same wall as #4). Blocked on the same two paths — a live datalink capture (infeasible,
see #4) or a Ghidra trace. Parked. Resume with a capture (over-the-air/root) of the Xtra app opening a
marked clip's trim view, or Ghidra on `SendGetPack<set_camera_expansion_cmd_pack>`.

## 8. Camera control + live settings (mode / resolution / fps, shutter) — 🔬 R-SDK read done via #9; media-path read + all control unimplemented

Turn Osmosis from a pure offloader into a light remote: **read** the camera's current shooting mode
(video / photo / timelapse), resolution and frame rate, and **control** it — start/stop recording, take a
photo, switch mode. Two halves:

- **Read — already solved on the R-SDK path (#9).** Over R-SDK, `0x1D/0x05`→`0x1D/0x02` streams mode /
  resolution / fps / recording status cleanly (parsed into `RsdkProtocol.CameraStatus`). So on an R-SDK
  camera this is done. The open part is reading the same over the **media path** (WiFi/DUML), for cameras /
  sessions where we're offloading rather than in GPS mode:
- **Read (media-path status/settings).** We already subscribe to the camera-capability params over the datalink
  (`camcap_mode_profile`, `camcap_video_format`, `camcap_fov`, `camcap_iso`, `cam_status`, … — see the
  subscribe list in [`DatalinkClient`](app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt)),
  and the `0x02/0x80` push carries live status. What's missing is the **exact field decode**: which bytes
  give the current mode / resolution / frame rate. (The old `mode` guess — low nibble of `0x02/0x80`
  byte 0 — was wrong and was removed; the real values live in the `camcap_*` params or a different offset,
  and need mapping against ground truth: change mode/res/fps on the camera and diff the frames.)
- **Write (control).** Two routes. On **R-SDK** (#9) the control commands are documented and clean —
  record `0x1D/0x03` (`RsdkProtocol.recordControl` already written), mode switch `0x1D/0x04` — so a control
  UI on top of the R-SDK session is the small remaining piece there. On the **media path**, the command ids
  (camera cmdset `0x02`: take-photo `0x02/0x01`, start-record `0x02/0x20`, stop-record `0x02/0x21`, set-mode
  `0x02/0x02`) are now documented with wire examples in
  [MEDIA_PROTOCOL.md](MEDIA_PROTOCOL.md#camera-control) (from the Pocket 3 + osmo-download repos) — but
  firmware-/reference-derived, **unverified on the Nano/Xtra**, payloads (mode enum, res/fps selector)
  unmapped, over the same UDP datalink as the file list.

**Remaining:** map the status fields against on-device ground truth; verify the `0x02` control commands on
hardware (real-world side-effecting — test on a throwaway state); add a minimal control UI. Reference:
`reference/osmo-download` (cmd ids) + `reference/Osmo-GPS-Controller-Demo` (`docs/protocol_data_segment.md`,
camera control).

## 9. R-SDK GPS push — optional Android background service — ✅ DONE, hardware-verified (2026-07-23, shipped in v0.5)

Push the phone's GPS into the camera (geotag / speed + route overlays), porting DJI's own
**Osmo-GPS-Controller-Demo** (`reference/Osmo-GPS-Controller-Demo`). **Implemented** behind the 🛰️ toggle
next to "Save logs": turning it on and picking a camera connects over R-SDK and starts a foreground service
that streams the phone's GPS.

**Confirmed by reading the demo (not inferred):**
- The R-SDK flow is **BLE-only** — no WiFi AP, no HTTP; a completely separate flow from the media offload.
  It rides the **same GATT** as the media path (fff0 / notify fff4 / write fff5), just different frames.
- Different wire protocol: **SOF 0xAA**, header CRC-16 (SOF→SEQ) + whole-frame CRC-32 (SOF→DATA), both with
  DJI's custom init `0x3AA3`, little-endian. Ported in `rsdk/RsdkProtocol` and **verified byte-for-byte**
  against frames emitted by the demo's own CRC/framing C (`RsdkProtocolTest`).
- Pairing is a documented on-screen **approval** (`0x00/0x19`, `verify_mode`/`verify_data`), *not* a crypto
  wall — replicable, though a different association than our media `0x07/0x45` "osmo" pairing (and the same
  one the reverted BLE wake broadcast needed).
- Camera **mode / resolution / fps / recording status** come free over `0x1D/0x05`→`0x1D/0x02` (see #8).

**Built:** `rsdk/RsdkProtocol` (frames + both CRCs + command build/parse), `rsdk/RsdkController` (the
`connect_logic` handshake → status subscription → GPS push, reusing `GattClient` with `armPairing = false`),
`rsdk/GpsService` (foreground `location` service: LocationManager GPS + GnssStatus sat-count, 1 Hz push,
the `Mode - … - Rec - yes/no - gps: healthy/not healthy` notification + a Stop action). Used
**LocationManager, not FusedLocationProvider** — no Google-Play-Services dependency, and it uniquely exposes
the satellite count the GPS frame carries.

**✅ Verified on a genuine DJI Osmo Action 5 Pro** (2026-07-23, by an external tester): BLE approval
handshake, status decode and GPS acceptance all work, and the track lands in the recording.

**Two bugs the field test exposed — both fixed:**
- *Only the start location was recorded.* We subscribed to `GPS_PROVIDER` alone and seeded `latest` from
  `getLastKnownLocation()`; when that provider doesn't deliver (backgrounded / cold start / ROM-dependent)
  the seed never moved and we pushed **one cached fix at 1 Hz for the whole clip**. Now we subscribe to
  **FUSED (API 31+) + GPS + NETWORK** and gate every push on the fix's *own* timestamp (`freshFix()`, <30 s),
  so a stale seed can never pose as the live position.
- *`gpsTime` read ~1 h behind.* Same root cause, not a timezone bug: we stamp the wall-clock from
  `loc.time`, and that cached fix carried its own hour-old timestamp. (The protocol has **no timezone
  field** at all — DJI's demo hardcodes `hour + 8` — so the camera stores a bare local wall-clock.)

**Proven objectively, not by eyeballing:** the failing clip's `djmd` track decoded to **719 points with
exactly 1 distinct position and 1 distinct timestamp** — a frozen feed, since a mere timezone error would
still advance the clock. That track is **protobuf** (an earlier note here calling it encrypted was wrong);
read it with [`pyosmogps`](https://github.com/francescocaponio/pyosmogps)
(`python -m pyosmogps extract -r discard -f 10 clip.mp4 out.gpx`), which doubles as the regression harness:
a healthy feed shows many distinct positions and advancing timestamps.

**Remaining (optional):** a control UI on top of the live R-SDK session (record `0x1D/0x03`, mode
`0x1D/0x04` — see #8). The Xtra rebrand probably won't honor R-SDK at all.

## 10. Wake a sleeping camera over BLE — ✅ DONE, verified on the Nano (2026-07-23)

Tap a sleeping Nano in the camera list and it wakes and offloads — no reaching for the power button.

**How it actually works (and how two wrong theories died):**
- ❌ *DJI's `WKP` wake broadcast.* DJI documents one (`{10, 0xff, 'W','K','P', mac-reversed}`,
  *Camera Power Mode Settings (001A)*) and we implemented it byte-exactly. It did nothing — an HCI
  snoop of Mimo shows **Mimo never advertises at all**. That path is for the R-SDK *remote*
  deep-sleep case, so the implementation (and its `BLUETOOTH_ADVERTISE` permission) was removed;
  it's recoverable from git history if a remote ever needs it.
- ❌ *`0x07/0x39` as the AP bring-up.* The camera replies `e0` (reject) to **Mimo too**, so it isn't
  load-bearing and isn't sent.
- ✅ **A sleeping Nano keeps advertising `ADV_IND`** under its own name. Mimo simply connects and
  drives it with DUML. The wake is a *command sequence*, not a broadcast.

**The sequence** (mirrored in `onReady`/`onPaired`), and the bug that hid it — the DUML receiver byte
is `(id << 5) | type`, and these two commands are **not addressed to the camera**:

| step | cmd | receiver | reply |
|------|-----|----------|-------|
| 1 | `0x00/0x2b` `04 00` (before pairing) | **`0xF0`** = type `0x10`, id 7 | — |
| 2 | `0x07/0x45` SetPairingPIN | `0x07` | `00 01` |
| 3 | **`0x53/0x10`** `00 00 00 00` | **`0x1C`** = type `0x1C`, id 0 | **`01 00 00 00`** ← wakes it |
| 4 | `0x00/0x2b` `01 01`, ~1 Hz | `0xF0` | keepalive |

Addressed to Camera (`0x01`) instead — as we first did — every one of them is answered `e0` and the
camera never wakes. Pinned by
[SessionCommandsTest](app/src/test/java/dev/konraditurbe/osmosis/duml/SessionCommandsTest.kt), because
the failure is *silent*. `0x07/0x47` ConnectToWiFi (never used by Mimo, and correlated with a sleeping
camera dropping us, `status=19`) is now only a fallback when no creds came over BLE.

**Method note:** cracked by an HCI snoop (`settings put global bluetooth_hci_log 1` → `adb bugreport`
→ `FS/data/misc/bluetooth/logs/btsnoop_hci.log`) of **Mimo and Osmosis back to back, with the wake
button-press wall-clock noted**, then diffing the two frame streams. The timestamps are local
wall-clock (decode with `utcfromtimestamp`). Same technique that cracked #3.
