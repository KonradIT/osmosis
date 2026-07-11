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
[MediaDownloader.downloadTrimmed](app/src/main/java/com/chernowii/osmosis/net/MediaDownloader.kt),
[MediaPreviewActivity](app/src/main/java/com/chernowii/osmosis/ui/MediaPreviewActivity.kt).

## 2. Support the rest of the Osmo line — ⚙️ framework done, verification hardware-gated (2026-07-10)

Built a per-model capability table
([CameraModel](app/src/main/java/com/chernowii/osmosis/ble/CameraModel.kt)) keyed on the BLE model
byte: datalink port + TCP-poke, WiFi security, and a `verified` flag. The runtime resolves it from
the scan ([OsmoScanner](app/src/main/java/com/chernowii/osmosis/ble/OsmoScanner.kt) now passes the
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
([SavedCameras](app/src/main/java/com/chernowii/osmosis/core/SavedCameras.kt),
[CameraListAdapter](app/src/main/java/com/chernowii/osmosis/ui/CameraListAdapter.kt)) — saved
cameras first (📶 in range / 🚫 out of range, from the live BLE scan), then newly-scanned ones
tagged **NEW**. Tapping an in-range camera connects → grid; a first-time camera prompts for the WiFi
password once and is remembered per-MAC (with its model byte, so the Pocket 3's name-fallback
survives). Long-press → re-enter password / forget. If the camera drops the BLE link while the
gallery is open (status=19, vs. the benign handoff status=8), the session tears down and returns to
the selector, which rescans and shows it 🚫.

## 3. Retrieve the WiFi password over BLE

Today the passphrase is entered once per camera in-app (stored per-MAC, never hardcoded). Goal:
pull it automatically over the BLE DUML channel so no manual entry is needed.

- **Reality check:** on the Osmo 360 and Nano the passphrase is **not** leaked by the pairing
  flow — fresh pairing returns only `0x01` after approval, and the protocol map notes the 360
  "never leaks the WiFi" over BLE (the password is shown on the camera screen). So this is genuine
  research, not a known command we've skipped.
- **Investigate:**
  - A dedicated credential-query DUML command (e.g. a `GetWiFi*`/SSID+password getter in the
    WiFi cmdset `0x07`) that the official app issues **after** app-level pairing establishes trust.
    RE the unpacked Xtra app (`reference/xtra/`) and/or capture a PCAPdroid trace of Mimo
    auto-connecting to the camera to see if such a request/response exists.
  - Whether newer models (Action 5/6) expose creds where older ones don't — test per model.
  - The dormant `credprobe` intent hook was an earlier stab at this; fold in whatever the RE finds.
- **Fallback if creds are truly never exposed:** keep the one-time in-app entry, but improve it
  (QR-scan of the camera's on-screen connection QR, if present, decodes SSID+password directly).
- **Note:** the R-SDK/accessory family (`Osmo-GPS-Controller-Demo`) uses a different pairing with
  numeric `verify_data` and is control-only — not a media/credential path.