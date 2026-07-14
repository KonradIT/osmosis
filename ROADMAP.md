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
  (`0x30/0x18/0x40/0x80`), not the cmd. Getting it needs a Ghidra trace of the frame builder, or —
  preferably — a **live datalink capture** of the official app deleting one file (it rides the
  WiFi/UDP datalink, not BLE, so btsnoop won't see it; try PCAPdroid per-app mode). The capture
  doubles as the safety check.
- **When implemented:** gate behind an explicit confirm and test on a throwaway clip first — deletes
  are irreversible on the SD card.
