# Osmosis — Roadmap

Planned work, in rough priority order. For the reverse-engineered protocol details referenced
below, see [docs/01-protocol-map.md](docs/01-protocol-map.md).

**Working today:** Osmo Nano (datalink UDP 9004) and Xtra Edge Pro / DJI Action 5 Pro
(datalink UDP 10004) — full pipeline: BLE pair → wake AP → media list → thumbnail grid →
LRF preview + download queue → resumable high-res `/v2` downloads. fps comes from the DUML
manifest; resolution from the MP4 `moov`.

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
- **Verified on hardware:** Osmo Nano (`0x19`, 9004, WPA2), Osmo Action 5 Pro / Xtra Edge Pro
  (`0x15`, 10004, WPA2) — both re-confirmed after the brand→model switch.
- **Coded, unverified (no unit):** Osmo 360 (`0x17`, 9004, **WPA3** via `setWpa3Passphrase`) and
  Osmo Pocket 3 (`0x20`, 9004) — the Pocket 3 broadcasts **no** BLE manufacturer data, so detection
  falls back to the BLE local name. Pocket 4 (`0x21`) added to the map.
- **Best-effort default (no data source):** Osmo Action 2 / 3 / 4 / 6 — these are Mimo cameras, so they get the
  9004/poke/WPA2 default and the `~experimental` tag. Confirming each needs a PCAPdroid capture of
  Mimo or the unit itself.

Shared, already model-agnostic: pairing (`osmo` token), `/v2` HTTP, `DJI_`/`CAM_` naming, storage
auto-detect, and the preview/trim/stream flow. **Remaining:** hardware/pcap verification of the 360,
Pocket 3, and the Action 3/4/6 ports.

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

## 4. Delete a specific file on the camera — 🔬 researched, one byte short (2026-07-14)

Confirmed possible; the RE is ~90 % done. Delete is a DUML command in the **same file-management
cmdset as the file list** (`0x00`, where list = `0x00/0x26`), reverse-engineered from the real
camera SDK `libxtrasdk_jni.so` (pulled from the phone: `split_dynamic_pack_csdk.apk` →
`assets/white-dymlibs.zip` → `libxtrasdk_jni.so`).

- **Request type:** `xtra::core::delete_file_req`.
- **Entry points** (`xtra::sdk::FileTransferManager`): `DeleteFiles` (specific files),
  `DeleteMediaSubFiles` (a clip's main + sub/proxy together), `DeleteFilesAll` (all of a `FileType`).
- **Transport:** `SendCompositePack<delete_file_req, MediaFile>` — batch/composite (many files per
  op), versioned `XTRA_V1_CMD_VERSION`, takes a storage selector `pair<u8,u8>`, `MediaFile` refs, and
  an async `FileActionResponse` callback. (The dex only has the Kotlin `MediaDeleterFactory` /
  `Normal|Loop|ShotsMediaDeleter` → CSDK `MediaFileEx` bridge; the wire command is native.)
- **Missing:** the exact **cmdId byte**. It's written several call-levels below `SendCompositePack`
  in the native frame builder; `llvm-objdump` grep only surfaced struct sizes/flags
  (`0x30/0x18/0x40/0x80`), not the cmd.
- **⏸️ PARKED (2026-07-15) — both remaining paths blocked:**
  - *Live datalink capture* of the official app: **infeasible** on this setup. The command rides the
    WiFi/UDP datalink (not BLE), the phone is **not rooted** (no `tcpdump`), and PCAPdroid's VPN
    conflicts with the app's `bindProcessToNetwork` (severs the camera link / can't see the bound
    traffic). Only an over-the-air monitor-mode capture (laptop + WPA2 PSK) or root would work.
  - *Ghidra trace* of the native frame builder for the cmdId — not yet done.
- **When implemented:** gate behind an explicit confirm and test on a throwaway clip first — deletes
  are irreversible on the SD card.

## 5. Osmo Nano: surface the dock's stats

The Nano is a two-part device (camera unit + dock); the status pill currently shows only the camera
unit's battery and the active store's space. Two dock-specific readouts to add:

- **Dock SD card space** — the SD card lives in the dock. The camera's `0x02/0x80` storage frame
  reports the *active* store only (internal vs SD); need to query/select the dock's SD store and show
  its free/total alongside (or instead of) the camera-body figure.
- **Dock battery %** — the camera unit (DUML sender `0x05`) pushes `0x0D/0x02` battery for **itself
  only** (observed 100 % camera vs 24 % dock). The dock's battery isn't in the pushed frames, so it
  needs a **separate query** (a second battery source / component id). Deferred earlier for exactly
  this reason; pick the right sender/component to poll.

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
