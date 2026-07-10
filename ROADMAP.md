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

## 2. Support the rest of the Osmo line

Cameras: **Osmo Action 3 / 4 / 6, Osmo 360, Osmo Pocket 3.** Most of the stack is already
brand/naming-agnostic — the datalink handshake, `DJI_`/`CAM_` filename parsing, `/v2` download,
and the LRF/preview flow are shared. What each new model needs:

- **BLE model byte → name** in [BleConstants](app/src/main/java/com/chernowii/osmosis/ble/BleConstants.kt)
  (`MODEL_NAMES`; today `0x15`=Action 5 Pro, `0x19`=Nano) and any [Brand](app/src/main/java/com/chernowii/osmosis/ble/Brand.kt) tells.
- **Datalink port per family:** Osmo 360 / Pocket 3 = **UDP 9004** (same as Nano — should mostly
  work as-is); Action 5 Pro = **UDP 10004**. Action 3/4/6 unknown — likely 9003 or their own
  port; if not in the reference repos, capture it via PCAPdroid like we did for the Action 5.
- **WiFi security:** the **Osmo 360 uses WPA3** (Nano/Action are WPA2). The
  [ApJoiner](app/src/main/java/com/chernowii/osmosis/net/ApJoiner.kt) `WifiNetworkSpecifier` path
  will need a WPA3 branch.
- **Storage + naming quirks** per the protocol map (e.g. `CAM_` vs `DJI_`, internal vs SD).
- **References already cloned under `reference/`:** `dji-remote`, `DJI-Wifi-Connect`,
  `dji_protocol`, `lib-osmo-ble`, `osmo-download`, `reverse-engineering-dji`,
  `Osmo-GPS-Controller-Demo` — mine these for model IDs, ports, and per-model handshakes before
  reaching for a pcap.
- **Approach:** add a small per-model capability table (port, WPA mode, naming, storage) and key
  the runtime off the BLE model byte, so a new camera is mostly a data entry + a verification run.

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

---

### Not planned (yet)

- Frame-accurate re-encode trimming (vs. fast-cut) — only if users ask.
- iOS / desktop clients — this is Android-only by design.
- Live view / camera control — out of scope; Osmosis is an offloader.
