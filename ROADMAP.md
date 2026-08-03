# Osmosis — Roadmap

Planned work, in rough priority order. For the reverse-engineered protocol details referenced
below, see [docs/01-protocol-map.md](docs/01-protocol-map.md).

**Working today — tester-confirmed on real hardware:** Osmo **Nano**, **Action 5 Pro**, **Action 6**
and **Pocket 3** (all datalink UDP 9004 + poke), plus the **Xtra Edge Pro** rebrand (UDP 10004, no poke).
Full pipeline: BLE pair → wake AP → media list → thumbnail grid → LRF preview + download queue →
resumable high-res `/v2` downloads, across **internal *and* SD** (both stores listed and labelled).
fps, **byte size**, **resolution**, **duration** and **⭐ starred** all come from the DUML manifest —
**no MP4 `moov` parse and no HTTP `HEAD`** anywhere in the browse path. Also shipped (v0.5): **delete a file off the camera**
(long-press → confirm, DUML `0x00/0x28` — #4) and **R-SDK GPS sync** (🛰️ toggle, streams the phone's
GPS into the camera over BLE — #9, hardware-verified on an Action 5 Pro).

**Drones work too — Mavic 3, hardware-verified (#14):** BLE pair with the `"DJI FLY"` token → creds →
AP → a `0x51/0x02` session-open the cameras don't need → the whole library paged to the oldest file,
with thumbnails, preview and download. The manifest is flat 94-byte **DCF records** rather than
CompositePack, and media is addressed by packed `file_index` over **`/v1`** rather than by path over
`/v2` — one `MediaAddressing` seam picks between them. Delete and favourite are camera-only.

**Still open:** the **Action 4** (pairs and hands out BLE creds, but its AP never comes up — an OA4-only
`0x07/0x39` probe is on the `osmo-action-4-debugging` branch awaiting a test) and the **Osmo 360**
(parked — never reaches the datalink, and its files are 360-format that needs Mimo to view anyway).

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

## 2. Support the rest of the Osmo line — ✅ Nano / OA5 / OA6 / Pocket 3 + Xtra confirmed; OA4 + 360 open

Built a per-model capability table
([CameraModel](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt)) keyed on the BLE model
byte: datalink port + TCP-poke, WiFi security, and a `verified` flag. The runtime resolves it from
the scan ([OsmoScanner](app/src/main/java/dev/konraditurbe/osmosis/ble/OsmoScanner.kt) now passes the
model byte up), so port/poke/WPA come from the **model, not the brand** — the Xtra resolves to
Action 5 Pro (`0x15`) → 10004 by its model byte, exactly as a DJI-branded Action 5 would.
Unrecognized models fall back to the common config (9004 + poke + WPA2) so **any DJI Osmo is
attempted, not refused**; the selector tags unverified models `~experimental`.

Status per model:
- **Verified on hardware:** Osmo **Nano** (`0x19`, 9004+poke), **Action 5 Pro** (`0x15`, 9004+poke),
  **Action 6** (`0x18`, 9004+poke), **Pocket 3** (`0x20`, 9004+poke), all WPA2 — plus the **Xtra Edge
  Pro** rebrand (`0x15` by model but **10004, no poke** by its own OUI `EC:9E:EA`).
- **The Xtra-vs-OA5 port split (settled 2026-07-24):** we once listed "Action 5 Pro" as 10004, but that
  was **only ever the Xtra Edge Pro** — a covert DJI rebrand sharing model id `0x15` yet with its own OUI.
  A genuine OA5 Pro is now **tester-confirmed on the DJI-standard 9004 + poke**, so `10004` is
  Xtra-exclusive and the brand-aware guess was right. Resolution is brand-aware
  ([CameraModel.resolve](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt) takes
  [Brand](app/src/main/java/dev/konraditurbe/osmosis/ble/Brand.kt)), pinned by
  [CameraModelBrandTest](app/src/test/java/dev/konraditurbe/osmosis/ble/CameraModelBrandTest.kt).
  Since either guess can be wrong on an untested unit, the datalink now **retries the alternate
  config** (9004+poke ⇄ 10004/no-poke) when the handshake doesn't land, and logs which port answered
  — so any test on a real Action 5 Pro / Action 4 / Action 6 self-corrects *and* reports the truth.
- **✅ Confirmed end-to-end (2026-07-24):** a **genuine DJI Action 5 Pro, Action 6, and Pocket 3** all
  handshake on `udp/9004 + poke` **and complete the full pipeline** — grid, preview, download — on real
  hardware across multiple testers. Their `verified` flags are now flipped on
  ([CameraModel](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt)), so they drop the
  `~experimental` tag. That settles the OA5 question (the brand-aware guess was right, **`10004` is
  Xtra-exclusive**), and the OA6 byte `0x18` is confirmed on hardware (mfr `1800…`). Quirk noted: the
  **Pocket 3 answers `e0` (reject) to `0x53/0x10`** yet its AP still comes up via the `0x00/0x2b`
  session — the wake is belt-and-suspenders there.
- **✅ Media list cracked line-wide (2026-07-24):** the `0x00/0x27` payload is DJI's **CompositePack**
  TLV (not protobuf, and no reference repo decodes it — they all regex the paths). Rewrote the decoder
  to read each record's fields by **tag → length → value**
  ([decodeComposite](app/src/main/java/dev/konraditurbe/osmosis/camera/CameraSession.kt), reverse-engineered
  from real Nano/Xtra/OA5/OA6/Pocket 3 manifests): media path `1a [len] 00 00 00 01`, thumb `…02`,
  filename `0d [len]`, delete handle + video size off the `03 ff 19 06` marker. No filename regex at
  all, so the camera's **Naming Management** custom Folder/File prefixes (`_A01`, `_DOA5`, `_OP3`) and
  stock names decode identically — that was the whole blocker: OA5/OA6 scraped to zero on the
  `DJI_001_OA5` folder suffix, the Pocket 3 lost its extension to the `_OP3` name suffix. Decodes
  **all 45 Nano / 13 Xtra / 2 OA5 / 2 OA6 / 15 Pocket 3** files on real bytes (CompositeManifestTest),
  and **all five are now confirmed live** (grid + download) on real hardware.
- **✅ Real media byte size, all cameras (2026-07-24):** it's the `u32-LE` at **`marker − 12`** — pinned
  by correlating a Mimo capture's records against the **camera's SD card mounted over USB** (85/85 Nano
  files byte-exact, varying-per-file on the Action family), so the HTTP `HEAD` is gone. The old `head+38`
  we misread as size is actually the **`.LRF` proxy** size (right-looking on the Nano, a constant on the
  Action family). RE of the DJI app dex named the fields (`xtra.sdk.keyvalue.value.media.MediaFile`:
  `fileSize`/`duration`/`frameRate`/`resolution`/…, see [MEDIA_PROTOCOL.md](MEDIA_PROTOCOL.md)).
- **✅ frameRate, resolution, ⭐ starred — all off the manifest now (2026-07-24):** `frameRate` at
  `marker−2`, `resolution` at `marker−1` (a **DJI-wide** video-format index — Nano and Xtra emit the
  same codes; ground-truthed by ffprobe: `95`=2.7K 4:3, `103`=4K 4:3, `10`=1080p, `16`=4K 16:9,
  `45`=2.7K 16:9, `12`=1080p 4:3), and the `MediaFileStarTag` at `[ff|fe] 19 06 + 9` (Nano videos + photos;
  **the Action family carries no star flag**, so it degrades to "none"). The star renders in the grid.
- **✅ duration — the last field, mapped (2026-07-29):** `u16-LE @ marker−4` (whole **seconds**), sitting
  immediately before the fps/resolution codes: `… [dur:u16][fps:u8][res:u8] 03 ff 19 06 …`. Ground-truthed
  **16/16 on the Nano and 3/3 on the Xtra**. With it the **MP4 `moov` parse is deleted** — every grid/preview
  value now comes from the manifest. (Beware: the Nano *mirrors* duration at `marker+26`, the Xtra does not —
  reading the mirror showed every Xtra clip as `201:21`, which is `"1/"` out of `CAM_001/`. Anchor record
  windows on the **media-path** fields like the decoder does; filename-anchored windows drift a record.)
- **✅ Internal + SD, both stores (2026-07-24):** the manifest is **two concatenated per-storage lists**
  (SD first, then internal), each with its own `[u32 count][u32 size][u32 ts]` header — proven because a
  no-card manifest is byte-identical to the mixed manifest's *second* list. Storage is now resolved
  **per list** (was: one probe stamped on all files → half the grid blanked on a camera with a card, hit
  on an OA6 and an Xtra). The status pill shows **both** capacities from `0x02/0xDC` (card @6/@10,
  built-in @24/@28), verified against a camera's own on-screen figures. (Byte 0 of that frame is *not* an
  SD-inserted flag — it reads backwards; presence = capacity > 0.)
- **⏸️ Osmo 360 (`0x17`) — parked.** BLE pairs and hands out creds, but the phone **never finds the
  `Osmo360` Wi-Fi SSID** ("searching for device…" → timeout), so it never reaches the datalink; both
  WPA3 and the new WPA2-fallback fail because there's no AP to attach to — the 360's AP bring-up
  differs (it's the only model advertising an extra `fff7` GATT characteristic, and may want a
  360-specific Wi-Fi-enable command or a 5 GHz band). Deprioritized: its footage is 360-format that
  needs Mimo to view anyway. Cracking it needs a **PCAPdroid capture of Mimo connecting the 360**.
- **⚠️ Osmo Action 4 (`0x14`) — pairs, but no AP (open).** First OA4 contact (2026-07-24, tester):
  BLE connect, pairing (`0x07/46`), and WiFi creds over BLE all succeed — but the AP **never comes up**
  (Android sees no SSID; `ConnectToWiFi` twice drew a `status=19` link-termination), while the OA6 on the
  same phone/build worked. It's an older BLE generation (MTU 510, no `fff7`). The v0.4 build it was tested
  on used `ConnectToWiFi(0x07/47)`; it has **never** been sent `0x07/0x39` (dropped for the Nano, but that
  was Nano-only) — so an OA4-gated `0x07/0x39` wake probe now lives on the **`osmo-action-4-debugging`**
  branch, awaiting a test. Cheapest first experiment: turn WiFi on from the OA4's own menu, then tap it.
- **Best-effort default (no data source):** Osmo Action 2 / 3 — Mimo cameras that get the 9004/poke/WPA2
  default and the `~experimental` tag. Confirming each needs a PCAPdroid capture of Mimo or the unit.

Shared, already model-agnostic: pairing (`osmo` token), `/v2` HTTP, `DJI_`/`CAM_` naming, per-list
storage detect, and the preview/trim/stream flow. **Remaining:** the **Action 4** AP bring-up and the
**360**'s datalink once it joins — the per-model media-list layouts that used to block OA5/OA6/Pocket 3
are all solved.

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
  ([DatalinkClient.recordStart / deleteFiles](app/src/main/java/dev/konraditurbe/osmosis/camera/CameraSession.kt))
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

## 7. Retrieve highlight / moment markers — ✅ DONE, inline (2026-07-30)

DJI "highlight" marks (side-button presses during recording) are **NOT stored in the MP4** — proven
by an exhaustive teardown of an Action 4 + Xtra clip: not in chapters/tags, not in the `dvtm` protobuf
metadata track (per-frame telemetry only — orientation quat, ISO, shutter, WB), not in the `dbgi`
(sensor-debug) track, not in `udta`, not as HEVC SEI. They live **on the camera** and are pulled on
demand — the Xtra app shows them in its *trim/editor* view.

**The command is `0x02/0xff`** (the SDK's generic `camera_expansion_cmd`; `PullHighLightAction`).
RE'd empirically from a PCAPdroid capture of the Xtra app opening two marked clips' trim views —
which matched hardware ground truth byte-for-byte (a 2-mark clip → 4000/7000 ms; a 3-mark clip →
1000/3000/5000 ms). Runs **inline on the live session** (`DatalinkClient.getHighlights` via `runCommand`,
no fresh session) now that #12 landed the faithful session.

```
request  0x02/0xff → Camera(0x01):  40 2f 00 01 0b 00 00 00 [handle:u32-LE] 00 00
reply    0x02/0xff:  00 · 40 2f 00 01 · [len:u32-LE] · [handle:u32-LE] · [count:u8] · 00 ·
                     { 00 [startTimeMs:u32-LE] } × count     # count @ byte 13, first mark @ 16, stride 5
```
- `handle` = the video's manifest delete-handle (§2). `startTimeMs` = mark start in ms (we show whole
  marks; a `duration` field wasn't distinguishable in the reply — the marks read as points).

**Shipped:** the video preview pulls marks off-UI via the `net/Highlights` bridge and shows a row of
tappable **⚑ m:ss** chips that seek the player. Verified on the Xtra (`0023` → 4s/7s, `0022` →
1s/3s/5s) with the two-storage split staying intact afterward — the churn that forced this off before is
gone (see #12). Originally the camera only answered `0x02/0xff` on a fresh session, whose teardown churn
collapsed the Xtra's storage split; the #12 faithful session removed both problems.

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
  subscribe list in [`DatalinkClient`](app/src/main/java/dev/konraditurbe/osmosis/camera/CameraSession.kt)),
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

## 11. Direct USB-C ↔ USB-C media read — 🔬 IDEA

Offload over a **USB-C cable** instead of WiFi, for the Osmo models that expose their storage when
wired to a phone. Would sidestep the whole BLE-pair → wake-AP → WiFi-join dance and run at cable speed —
attractive for bulk transfers and for cameras/phones where the AP hop is flaky.

- **Open questions:** does the Osmo present as **MTP / USB mass-storage** when plugged into an Android
  host, or only through a **proprietary DJI USB protocol** (the way Mimo talks to a wired camera)? Which
  models support it at all? Some Osmos default to "charge only" and need an on-camera "USB mode" toggle
  (possibly a DUML command) before they enumerate as storage.
- **Approach:** enumerate the camera over Android's **USB host API** (`UsbManager`) with the phone as
  host; if it's MTP, read media via the MediaStore/MTP path; if it's a DJI protocol, capture DJI Mimo/the
  desktop assistant doing a wired transfer and port it. Needs a USB-C ↔ USB-C cable and a host-capable
  phone.
- **Why it's worth it:** no AP, no pairing, no password — just plug in and pull, at USB speed.

## 12. Faithful long-lived session (one session, all commands inline) — ✅ DONE (2026-07-30)

**The keystone.** Every "fresh registered session" workaround — paging (~15 s/page), delete (~9 s),
favorite, burst group-expand, the highlight pull (#7) — existed because our session drifted out of the
camera's write-accept window. The churn was also *harmful*: repeated teardown/re-register destabilized an
Xtra so its two-storage manifest split collapsed (all files `storage=0` → half the thumbnails 404'd).
Fixing the session removed every workaround **and** the fragility. All commands now run **inline on one
long-lived session — instant, no pause, no playback flash**, verified on Nano + Xtra.

**The real bug (one field).** The datalink is a **sliding-window transport**: every packet carries
`[r8-9 = ack of the peer's seq][r10-11 = my own seq]`, `uhSeq = my own seq`. A command's `r8-9` and
`r10-11` are **both in the app's own command-seq space** (`r8-9` lags `r10-11` — the last of our seqs the
camera echoed). We instead put **`lastCamSeq`** in `r8-9`, and `recvAll` refreshes `lastCamSeq` from every
received packet — i.e. the camera's *telemetry* seq, which floods ~10× faster and wraps to a different
phase. So our `r8-9` chased the telemetry stream and diverged from our own `r10-11` → the receiver window
silently dropped **writes** (reads were lenient). A fresh session only "fixed" it by momentarily
re-aligning the two streams. (My first guess — re-derive `udpSeq` from `lastCamSeq` — was backwards; the
fix is the opposite: keep `udpSeq` a clean `+8` counter and stop leaking the camera's seq into `r8-9`.)

**The fix (shipped, in order, each on-device-verified):**
1. **`routingHeader` `r8-9` = our own previous command seq** (`udpSeq − 8`), not `lastCamSeq`. This alone
   makes writes land on the live session. `udpSeq` stays a `+8` monotonic counter from registration.
2. **Hold playback** (`0x02/0x0c 01 01 00 01`) for the whole browse session (was entered per-fetch).
3. **Session-thread command queue** (`runCommand` / `runManifestQuery`): the keep-alive thread owns the
   socket, so callers queue a command and it's sent + its reply captured from the same recv loop — one
   in-sequence session. `findReply` skips the empty transport ACK to read the real status reply.
4. Converted **favorite → burst → delete → pagination → highlights** to inline, fresh-session paths kept
   as fallbacks (they now rarely fire). Mirroring Mimo's fuller `0x02/0x8e` heartbeat set turned out
   **not** to be needed — steps 1–2 were enough. See MEDIA_PROTOCOL "Datalink transport / sequencing".

---

**Historical note (the problem this solved).** The media list only paginates in **playback mode**
(`0x02/0x0c` `01 01 00 01`), and older pages used to spin up a **fresh registered session** (~15 s/page)
because the camera dropped the datalink after ~2 pages and
any keepalive we insert just drifts our `udpSeq` out of the accept window (see `freshSessionPage`).

DJI Mimo instead **stays in playback mode the entire time the gallery is open**, keeping one long-lived
session warm with a constant `0x02/0x8e` heartbeat — so *its* pagination is effectively **instantaneous**
(no re-handshake, no re-enter-playback per page).

- **Idea:** hold the camera in playback for the whole browse session (enter on grid open, leave on
  `onDestroy`) and keep a single paging session alive, so each scroll-page is one quick query.
- **Blocker / why we don't already:** it needs the `udpSeq`-window / ~2-page session drop solved — the
  exact thing that forced fresh-session-per-page. Mimo's constant heartbeat evidently keeps the window
  aligned; ours (inserted mid-fetch) corrupted the stream, so it needs its own reversing pass.
- **Trade-off:** persistent playback probably blocks on-camera recording while we're attached and holds
  the camera's UI in playback; our per-fetch enter/exit is more polite and leaves it usable between loads.
- **Payoff:** near-instant infinite scroll instead of ~15 s/page — a much nicer feel on 100s-of-clips
  libraries. See `DatalinkClient.fetchNextPage` and [[pagination-index-first]] in memory.

## 13. Improve previews by byte-range-streaming the LRF — 🔬 IDEA

We already stream the low-res `.lrf`/`.lrv` proxy for preview (`MediaPreviewActivity`: `VideoView` +
native MediaPlayer, which does its own HTTP range requests over the camera's `/v2` endpoint) and it
plays cleanly. A DJI-Mimo capture shows *how small and range-addressable* the proxy is: Mimo pulls the
MP4 header (`bytes 0-4095`) + the `moov` atom at the tail, then progressive ~5 MB chunks — a whole
clip's proxy is only ~17 MB. That cheap random access opens some nice polish on top of what we have:

- **YouTube-style scrub preview** — a small snapshot window floating above the seek bar while you drag,
  showing the frame under the cursor. Because the `.lrf` is tiny + range-seekable, decode frames on the
  fly with `MediaMetadataRetriever.getFrameAtTime` (against the proxy URL or a cached copy), or
  pre-extract a sparse keyframe filmstrip once.
- **Cache the proxy locally on open** — ~17 MB, so one background fetch gives *instant* seeking/scrubbing
  (no re-buffer on jumps) and feeds the scrub thumbnails with zero extra range round-trips.
- **Filmstrip / storyboard strip** under the player for quick visual navigation of long clips.

Not a bug fix (previews already work) — a UX layer the proxy's small size + range access make cheap.

## 14. Drone offload support — ✅ WORKING on a Mavic 3 (2026-08-01)

**End to end on real hardware:** BLE pair → WiFi creds → AP join → `udp/9003` session-open → **the whole
library, paged to the oldest file, with thumbnails on every cell** → proxy preview → full-res and
partial download, plus battery + storage in the status pill.

Verified 2026-08-02 on a Mavic 3: ten pages, 373 files, terminating cleanly on the last page, using ten
datalink transfers in total — one per page. Two bugs stood between the first 45 files and that: the
`0x51` session lapsing after ~30 s, and thumbnails leasing a transfer slot each.

**The gate was a session-open handshake on the `0x51` channel.** A drone answers *nothing* until the app
sends `0x51/0x02` and completes a mutual challenge (`0x51/0x08`, `0x51/0x06`, carrying its serial and an
app id). Before that it streams ~2 DUML frames/s of empty keepalive; a second after it, ~1200 frames/s
and every command works. Found by capturing DJI Fly against a **cold** drone — both earlier captures
began with the session already open, so neither contained the transition. Two traps cost real time:
`r0-1` is **not** a running ack (it oscillates and only moves when a reply lands, so it can't be used to
test whether the drone is accepting us), and the **sequence window is not enforced** (DJI Fly runs ~1600
packets ahead of it). Replaying captured `0x51` frames verbatim still failed until the wrapper's trailing
counter was re-stamped monotonically — frozen at capture values it ran *backwards*, and the drone
dropped the open as a replay without answering.

### `/v1` in full

`file_index` is a **packed** field, not a flat number — masking the directory as 16 bits instead of 14
folds the storage bits in, and every file on internal storage silently disappears from the grid:

```
bits 31:30 = storage (0 SD, 1 internal eMMC, 2 SSD)
bits 29:16 = DCF directory, 14 bits (100 → 100MEDIA)
bits 15:0  = DCF file number (554 → DJI_0554)
```

`file_subtype` selects the rendition, and the server maps each to a different tree on the card:

| subtype | meaning | path |
|---:|---|---|
| 0 | ORG — original | `DCIM/<dir>MEDIA/DJI_<n>` |
| 1 | THM — thumbnail | `MISC/THM/<dir>/DJI_<n>` |
| 2 | SCR — screen-res render | `MISC/THM/<dir>/DJI_<n>` |
| 18 | LRF — low-res proxy video | `DCIM/<dir>MEDIA/DJI_<n>` |

Measured: the subtype-18 proxy is ~7× smaller than the original (38.8 MB vs 273 MB on a 30 s clip) and
previews at 1280×720. `file_seg_subindex` picks a part of a segmented recording (0 = whole file) and is
sent on every request — the Mavic 3 tolerates its absence but the parser expects all three parameters.
The camera-style `/v2?storage=N&path=…` is believed to work on a drone too, but we have never exercised
it there — everything above goes through `/v1`.

Server is `lighttpd/1.4.55` on TCP 80, no auth. `Last-Modified` on a `/v1` response independently
confirms the manifest's FAT timestamp decode, to the second.

### Telemetry

The drone wraps its pushes inside `0x51/0x01` tunnel frames, so a top-level scan steps straight over
them — they need a nested-aware scan. Battery and storage then use the **same field layout as a camera**:
`0x0D/0x02` @20 percent, @1 pack mV, @5 signed mA; `0x02/0xDC` @6/@10 SD and @24/@28 internal MiB.
Ground-truthed on a Mavic 3: 26%, 15.18 V, −1.30 A, 238 GB card (149 GB free) + 7.9 GB internal, and
`0x0D/0x03` carries four cell voltages. Dock/charging bytes are deliberately NOT reused — those are Osmo
dock semantics and would show a phantom dock indicator on an aircraft.

### Threading

One thread owns the socket. The keep-alive loop is permanently in `recvAll`, so anything else calling it
gets starved — that broke pagination (a page fetch received *zero bytes*) and stalled thumbnails
part-way down the grid. Thumbnails and paging queue via `onDroneThread`, and status is decoded inside
the pump so the pill updates during long transfers rather than only between them.

### Transfers are leased, and must be released

Every `0x00/0x26` media transfer — list *or* thumbnail — holds a slot on the drone until the client
releases it, and only so many exist. Leak them and the drone stops serving new transfers while telemetry
keeps streaming, so the link looks perfectly healthy and answers nothing.

The `0x4a` subtypes are a family per transfer kind: `+0` query, `+1` reply, `+2` proceed, `+3` state,
`+4` release. A media list is `0x00`–`0x04`; a thumbnail is `0x20`–`0x24`. A full exchange, captured
from the reference app:

```
-> 4a sub=00 seq=0005     query
<- 4a sub=03 seq=0005     drone raises state, and waits
-> 4a sub=02 seq=0005     app says proceed
<- 4a sub=01 seq=0005     data
-> 4a sub=04 seq=0005     app releases the slot
```

We released list transfers but never thumbnail ones, so browsing died a dozen cells in. Two symptoms
that took a while to connect: paging stopped after ~2 pages, and scrolling *fast* got much further —
because it outran the thumbnail queue. `seq` is one monotonic counter shared by both transfer kinds;
the reference app runs it past 30 in a session without stalling.

The failure log used to say `heard (no valid DUML at all)`, which read like a dead link. It wasn't —
that sink only keeps `pktType 0x03` (the data stream), and the drone was pushing ~850 telemetry packets
per query throughout. Queries now log received datagrams **by pktType**, so "silent" and "ignoring us"
can't be confused again.

Separately, and a genuine second bug: the `0x51` data session lapses **~30 s** after it opens unless the
identity beacon keeps being answered (~2/s — it was answered once, at session open). Measured across
five sessions, every query at t ≤ 28.5 s worked and every one at t ≥ 28.9 s returned nothing.

### Thumbnails: a still has none on the card

Probed against a Mavic 3 for a photo index: only `file_subtype=0` answers. Every other subtype makes the
server close the connection with no response — which is how this firmware reports a missing file, not a
404. A failed lookup returns `HANDLER_ERROR` and the connection dies, which is also why `GET /` returns
an empty reply. So a still has **no THM, SCR, AIS or LRF** — nothing but the original.

The reference app solves this by pulling *every* thumbnail over the datalink; across three captures it
never once requests subtype 1 or 2 over HTTP, only subtype 0 (downloads) and 18 (playback). We don't,
because that path is strictly one-at-a-time and leases a slot per image — a gridful of stills either
crawls or exhausts the budget.

Instead a still's thumbnail is lifted from the **EXIF block inside the original**: one ranged request
for the first 64 kB (EXIF's `APP1` is a u16 length, so it cannot be larger), then `EmbeddedJpeg` walks
the segments to `APP1` and extracts the JPEG within. Measured on a Mavic 3, the embedded thumbnail
starts 1502 bytes in. Videos keep their THM. Both are plain HTTP and parallelise, and **the datalink now
carries nothing but the page queries themselves** — one transfer per page, so browsing cannot exhaust
the slots however far it scrolls.

Deliberately not "find the second `FFD8`": a 14 MP frame's entropy-coded data contains those bytes by
chance, so the search is confined to `APP1` and stops at `SOS`.

### Reading a capture with our own decoder

`PcapAnalysis` (test sources, skipped unless `OSMOSIS_PCAP` is set) walks a LINKTYPE_RAW pcap, filters
`udp/9003`, and decodes it with the app's own `DumlTransport.scanFrames`. It prints the pktType mix, the
command histogram, every `0x4a` subtype with its seq range, and a media timeline with control frames
dumped verbatim. Every byte-level claim above came out of it, and the frame builders are unit-tested
against those captured bytes.

---

## Historical: how it got here

**⚠️ Everything below this line is a record of the investigation, not a description of the current
state.** Several sections were written while the feature was still blocked and say so in the present
tense — "the drone won't serve US", "not yet run against the drone itself". Those were true when
written and are not true now; they are kept because the eliminations in them are expensive to redo.

DJI drones speak the **same DUML framing** over BLE/WiFi as the Osmo line. The chain was cracked on
**2026-08-01** — BLE pair (token `"DJI FLY"`) → WiFi creds → AP join → datalink on `udp/9003` →
**media list** → **download** — from a PCAPdroid capture of DJI Fly ↔ a real **Mavic 3** browsing its
gallery (`reference/captures/wifi/mavic3_media_browse.pcap`, gitignored), then implemented and
unit-tested against those bytes before it ever met an aircraft.

### The media API (cracked 2026-08-01)

The drone reuses the camera's DUML query — `0x00/0x26` → `0x00/0x27`, `receiverType 0x01`, the same
`0x4a` sub-protocol envelope, even the byte-identical `4a04…` follow-up frame — but **answers with a
completely different body**, and serves the bytes over a different URL.

| | Osmo camera | DJI drone |
|---|---|---|
| datalink | `udp/9004` (+tcp-7001 poke) or `10004` | **`udp/9003`, no poke** |
| list reply | CompositePack TLV, carries **paths** | **flat array of fixed 94-byte records, no filename at all** |
| media URL | `/v2?storage=N&path=DCIM/…` | **`/v1?file_index=<u32>&file_subtype=0`** |
| thumbnails | HTTP `.scr`/`.thm` | **over DUML** (`0x4a` subtype `0x20`→`0x21`, chunked JPEG) |
| playback mode | required to paginate | **not needed** |

**`0x4a` envelope** (both directions): `+0` magic `0x4a`, `+1` subtype (`0x00` list query, `0x01` list
reply, `0x20` thumb query, `0x21` thumb reply), `+2 u16` length — **low 12 bits are the length, bit
`0x1000` marks the FINAL chunk** — `+4 u16` seq (echoed), `+6 u32` chunk index. Chunk 0 of a reply adds
`+10 u32` total file count and `+14 u32` total manifest bytes. That `0x1000` flag is the trap: read the
length as a plain `u8` and short frames parse fine while every long one silently mis-parses.

**Record — 94 bytes, newest first:** `+0 u32` mtime (unix), `+4 u32` **size in bytes**, `+8 u32`
**`file_index` = `(folder shl 16) or number`**, `+12 u16` **duration in seconds** (`0` ⇒ still photo).
Bytes past `+14` are still unmapped (resolution/fps live in there) and are deliberately left `null`
rather than guessed. There is **no filename on the wire** — `DroneManifest` synthesises DJI's on-card
convention (`DCIM/100MEDIA/DJI_0554.MP4`) from the index, picking `.MP4`/`.JPG` off the duration.

**Ground truth:** DJI Fly downloaded `file_index` 6554154 and 6554148 in the same capture and the
server returned `Content-Length` **14168064** and **9494528** — byte-exact matches for the sizes decoded
out of the manifest, which is what pins the size field and the 94-byte stride. Both were `dur = 0` and
came back `image/jpeg`, confirming the still-vs-video signal. Pinned by
[DroneManifestTest](app/src/test/java/dev/konraditurbe/osmosis/drone/DroneManifestTest.kt) against the
real captured frames (fixture `manifests/mavic3_manifest.txt`).

**Pagination — also solved, and simpler than the camera's.** `cursor = 1` (bytes 10–13 of the query)
asks for the newest page (45 files); each older page passes **the oldest `file_index` of the page just
received**, and the drone replays that file as the first record of the next page, so callers dedup by
index. No fresh session, no playback mode — pages come back to back on the live session. DJI Fly walked
`1 → 6553910 → 6553865 → 6553821 → 6553777 → 6553768` and stopped when a page returned only the cursor
file. (The camera's `0x40000001` video-handle cursor is meaningless here — DJI Fly issues it after every
page and the Mavic answers `count = 0` every time.)

### Hardware attempt (2026-08-01) — the media API is right, the drone won't serve US

Everything up to the media query works against a real Mavic 3: BLE pair with the `"DJI FLY"` token →
`ALREADY PAIRED` → SSID + passphrase over BLE → AP join at `192.168.2.100` → **`datalink: handshake OK
on udp/9003`**. Then the drone answers the media query with nothing. Over ~10 runs it sent us ~30 kB
per session consisting *only* of its own `0x51/0x01` + `0x51/0x13` beacons, and never once replied to a
command.

**Eliminated by direct byte-level comparison against DJI Fly** (each of these was a real defect, is
fixed, and did *not* unblock it):

| Suspect | Verdict |
|---|---|
| Query payload | **identical** — pinned by `DroneManifestTest` |
| DUML framing / CRCs | identical (`crc16(frame) == 0` both) |
| Routing header ack + seq | matched (`ack=channel`, `seq=channel+8`) |
| Session id / handshake | drone echoes our id and ACKs `01`, same as DJI Fly's |
| Channel echo (`r0-1`) | drone mirrors our `b887` exactly as it mirrored DJI Fly's `40ef` |
| Symmetric UDP port | now bind 9003→9003 as DJI Fly does |
| Camera registration | removed for drones (DJI Fly never sends it) |
| 29-command WiFi prelude | replayed verbatim |
| BLE prelude | replayed verbatim (DJI Fly sends it over **BLE**, we had only sent it over WiFi) |
| 860/s uplink stream (`0x02/0x82`, `0x02/0xdc`, `0x04/0x1c` — 95% of DJI Fly's traffic) | replayed |
| App identity in `0x07/0x45` | tried DJI Fly's own install UUID |

**Two things worth not re-deriving.** `r0-1` is **not** a running ack — it oscillates
(`ef40→ef58→ef40→ef60`) and only changes when a reply lands, so it can't be used as an "are we being
accepted" probe. And the **sequence window is not enforced**: DJI Fly runs ~1600 packets ahead of it.
Also note the drone replies to *nothing* except `0x00/0x26` — even DJI Fly gets no response to any
prelude command — so silence during setup is normal and only the manifest is a real signal.

**What that leaves.** Whatever distinguishes us is something the drone knows that isn't in DJI Fly's
outbound bytes — most likely client authorisation (account binding), given it already withholds
credentials from any pairing token but `"DJI FLY"`. Seeing it needs a capture of **our own** session,
which PCAPdroid can't provide: its VPN mode breaks `bindProcessToNetwork`, so an attempted capture
recorded only DJI Fly's traffic and none of ours. That needs PCAPdroid's root/pcapd mode or `tcpdump`
on-device.

### Still open

*(Everything the old version of this list called a blocker — the datalink refusing commands, thumbnails
being unwired, no hardware run — is done. See the top of this item. What's genuinely left:)*

- **Delete and favourite are not implemented on a drone.** `DroneSession` takes the `MediaSession`
  no-op defaults (`deleteFiles` → `null`, `setFavorite` → `false`), and drone records carry no manifest
  handle, so `CameraFile.deletable` is false and the long-press menu correctly offers neither. The
  camera commands exist (`0x00/0x28`, `0x02/0xbf` — the latter does appear in a drone capture), but the
  handle they address is a camera-manifest field with no equivalent in the 94-byte DCF record. Wiring
  this means finding what a drone deletes *by* — plausibly the packed `file_index` itself.
- **`/v2?storage=N&path=…` on a drone** — believed to work, never exercised. Everything drone-side goes
  through `/v1`, because `DcfAddressing` is chosen for any file with a `fileIndex`.
- **Record fields past `+14`** are unmapped. The decoder reads mtime `@0`, size `@4`, index `@8` and
  duration `@12` out of the 94-byte stride and ignores the rest — resolution and fps are in there
  somewhere (see the new item #18).
- **`PROXY_MOOV` / `ORIGIN_MOOV`** (`file_subtype` 15/16) serve an MP4's `moov` atom alone and would
  replace the range request preview currently pays to find it. Untested on any aircraft.
- **Neo 2 (`0x007e`)** shares the drone defaults but has never been offloaded — its datalink port is
  unconfirmed and a tester's scan showed it refusing `0x07/0x0e`.
- **Any drone that isn't a Mavic 3.** Model ids at or above `0x40` fall back to the drone defaults on a
  documented guess (`CameraModel.DRONE_ID_FLOOR`); the Mini 3's real model byte is still unknown.

### History (how it got here)

- **Detection already works — via the DJI company id.** A drone advertises DJI's BLE company id
  `0x08AA` in its mfr data exactly like an Osmo, so the scanner already surfaces it as a `HIT` and reads
  its model id (the `u16-LE` after the cid). Confirmed on a real **Mavic 3** (named "1001"):
  `mfr[cid=08aa 7000…]` → model **`0x0070`**. `Brand.of` now treats "carries cid `0x08AA`" ⇒ `DJI`
  (more robust than OUI/name — a renamed drone still shows it), so it labels `[DJI]` instead of
  `[UNKNOWN]`.
- **A drone model-id → name table — partly done.** Both confirmed ids now resolve by name in
  `BleConstants.MODEL_NAMES` (`0x0070` Mavic 3, `0x007e` Neo 2) and carry `isDrone` in `CameraModel`;
  anything else at or above `0x40` falls back to drone defaults by the `DRONE_ID_FLOOR` guess. A fuller
  list is still wanted so unknown aircraft resolve by name rather than by threshold. **Dead end worth
  recording:** the `LctProductType` enum in a decompiled DJI-derived app is *not* this table — its
  `0x70` is a Matrice-class aircraft where ours is the Mavic 3, and our Nano's `0x19` is absent
  entirely. Two schemes that look interchangeable and aren't.
- **Seen already — DJI Neo 2 (`0x007e`).** In a tester's scan it **pairs over the same BLE DUML**, but
  returns **no WiFi password** to `0x07/0x0e` (the getter that works on every Osmo) — the app correctly
  falls back to the manual-password prompt, which the tester didn't complete. So the credential path
  differs on the drone side; whether it exposes creds via a different cmd, or expects the AP set up
  another way, is unknown.
- **Mavic 3 (`0x0070`) — BLE snoop findings (2026-07-25).** An HCI snoop of **DJI Fly** then Osmosis,
  back to back (same method as #3/#10), on a real Mavic 3:
  - **Pairing is identical to a camera.** Both apps use `0x07/0x45 SetPairingPIN` — DJI Fly with token
    `"DJI FLY"` (+ a UUID identifier), Osmosis with `"osmo"`. The Mavic accepted our pairing after
    on-screen approval (`0x07/0x45`→`0002`, then `0x07/0x46`→ approved). So our BLE pairing already
    reaches a drone.
  - **The Mavic rejects the camera WiFi getters with error `0xe4`:** `0x07/0x07` (SSID) and `0x07/0x0e`
    (password) each return a bare `e4`, not a `[status][PackString]`. This is *why* offload falls through
    to the manual prompt (same symptom as the Neo 2, now with the error code).
  - **DJI Fly never calls those getters.** Its Mavic session is a high-rate `0x51/0x13` push stream
    (telemetry — every frame just repeats the drone serial), with no `0x07/0x07`/`0x0e`/`0x47`.
  - **QuickTransfer WiFi does NOT traverse BLE (confirmed 2026-07-25).** A second snoop taken *during an
    actual QuickTransfer image pull* carried only the same two things — pairing + the `0x51` telemetry
    heartbeat. No SSID, no password, no WiFi command, no new cmdset; the only ASCII in the whole capture
    is the drone serial. So DJI Fly brings the QuickTransfer AP up over a **non-BLE channel** — most
    likely the phone already holds the drone's WiFi creds from first-time **binding** (cached by DJI Fly)
    and joins the AP directly, or negotiates over WiFi-Direct.
  - **Creds *do* come over BLE — via the same getters (2026-07-25).** A fresh-bind snoop of DJI Fly
    (after `pm clear dji.go.v5` + a **2 s drone-battery press** to confirm) shows the drone answering the
    **same getters Osmosis uses for cameras**: `0x07/0x0c`→ WiFi MAC, `0x07/0x07`→ SSID, `0x07/0x0e`→
    passphrase, in the `[status][PackString]` format we already decode. It's a **plain WPA2 SoftAP** (the
    "WiFi-Direct" guess was wrong — DJI Fly `addNetwork()`s it ephemerally).
  - **But cred release is gated on the pairing IDENTITY, not the button.** Osmosis ran the identical flow
    — `0x07/0x45` → `0002` approval-required → 2 s button → approved — and still got `no password`. The
    tell is the **approval code**: DJI Fly's pairing (token **"DJI FLY"**, a per-bind UUID identifier)
    gets `0x07/0x46` **[01]** and the creds; Osmosis's (token **"osmo"**, our fixed identifier) gets
    **[02]** and no creds. Cameras give our "osmo" token **[01]** — the *drone* is what discriminates,
    withholding QuickTransfer creds from a non-official pairing token.
  - **CONFIRMED (2026-07-25) — the token *is* the gate.** Pairing the Mavic with token **"DJI FLY"**
    (via Osmosis's existing `--es pin` hook, no rebuild) flipped the approval to `0x07/0x46` **[01]**,
    and the drone handed over **SSID + passphrase** over BLE (`0x07/0x07`, `0x07/0x0e`) exactly like a
    camera. Osmosis then **joined the drone AP** (`WiFi link: ip=192.168.2.100`). So BLE creds + WiFi
    join are solved; the fix is to drone-gate the pairing token to "DJI FLY".
  - **Remaining — the drone's media API is NOT the camera datalink.** On the drone AP the DUML datalink
    handshake fails on **both** `udp/9004` and `udp/10004`, so the manifest comes back empty. The drone
    serves QuickTransfer media some other way (different port / HTTP / MTP-over-WiFi). Direct probing
    from adb is walled off by Android per-app routing (only the network-bound app reaches
    `192.168.2.1`). **Next:** PCAPdroid-capture **DJI Fly doing a QuickTransfer browse+download** and
    read the media API off the wire — same technique that cracked the camera list from the Mimo PCAPs.
  - **Datalink is up on udp/9003, but the list command differs (2026-07-25).** A QuickTransfer PCAP +
    a live Osmosis attempt pinned it: the drone uses the **same datalink handshake as a camera on
    `udp/9003`** (no tcp-7001 poke; TCP-6001 probes RST'd). Osmosis now handshakes fine — but its
    camera list command `0x00/0x26` gets **no `0x00/0x27` back**: the 43 KB it reads is pure noise —
    25 `0x51/0x01` telemetry frames (the serial heartbeat) + datalink session heartbeat, no media. So
    the drone's **media-list command is different** and is the one remaining unknown. **Next:** a
    cleaner DUML capture of DJI Fly's QuickTransfer *browse* on 9003 — the earlier PCAP had **no
    inbound** simply because that pairing attempt *failed* (DJI Fly was retrying the handshake into
    the void), not a capture limitation, so a successful browse should record both directions. Or
    fuzz the list command now that Osmosis is on the datalink.
- **✅ The capture landed (2026-08-01).** A PCAPdroid capture of DJI Fly doing a full QuickTransfer
  browse + two downloads on a real Mavic 3 answered every open question above in one go — the list
  command was never different, only its *reply* and the media URL were. See "The media API" at the top
  of this item; the guess that drones "may not use CompositePack" was right.
- **Scope note (updated):** this started as "see how far the existing DUML stack reaches". It reaches
  all the way — list, paging and download are the same stack with a different manifest body and a `/v1`
  URL, so drone support is now a real feature rather than an experiment. Thumbnails turned out **not**
  to need the DUML plumbing this note once predicted: video cells use the `/v1` THM rendition and stills
  come out of the original's EXIF, both over plain HTTP.

---

## 15. Background downloads with a progress notification — ⬜ TODO

**Where it stands now:** a download queue runs on a bare `Thread` started by the Activity
([MainActivity](app/src/main/java/dev/konraditurbe/osmosis/ui/MainActivity.kt): `Thread { MediaDownloader(…).run(jobs, listener) }`),
with progress rendered only into the in-app UI. The one foreground service in the manifest is
`rsdk.GpsService`. So leaving the app puts a multi-gigabyte transfer at the mercy of process death,
and there is nothing in the shade to show it is alive.

**Wanted:** move the queue into a foreground service with an ongoing notification — determinate
progress bar, current filename and *n of m*, plus pause/cancel actions.

Worth thinking about before writing it:
- **The network binding is the hard part, not the notification.** Downloads only work because the
  process is bound to the camera AP (`bindProcessToNetwork`); a service must own or share that binding,
  and must react when the AP drops (the BLE link is torn down at handoff, and the camera can sleep
  mid-queue).
- Android 14+ wants a declared `foregroundServiceType` — `dataSync` is the fit — and posting the
  notification needs `POST_NOTIFICATIONS`.
- Resumable range requests already exist in `MediaDownloader`, so a killed transfer should resume
  rather than restart; that is most of the value of doing this at all.
- The drone path holds a **leased transfer slot** per datalink operation (#14). Downloads themselves are
  plain HTTP and unaffected, but a background queue that outlives the foreground session must not keep
  a lease alive behind the user's back.

## 16. Per-file shooting details (ISO, shutter, EV…) like Mimo — ⬜ TODO

Mimo's playback screen shows what the camera was *set to* when the file was shot. We show duration, fps,
resolution and size — all manifest fields — but nothing about exposure.

Note this is **per-file capture metadata**, distinct from #8, which is about the camera's *live* settings.

Three candidate sources, cheapest first:
- **Stills: already on the wire.** The EXIF thumbnail path fetches the original's first 64 kB
  ([EmbeddedJpeg.HEAD_BYTES](app/src/main/java/dev/konraditurbe/osmosis/core/EmbeddedJpeg.kt)), and the
  same `APP1` block carries the standard EXIF tags — ISO, exposure time, aperture, focal length. The
  bytes are being downloaded and thrown away today; reading them costs one extra parse and **zero extra
  requests**. This is the obvious first increment.
- **Video: the `djmd` track.** DJI clips carry an in-file metadata track that is **protobuf, not
  encrypted** — decodable, and it also carries GPS. Needs a range read of the right atom rather than the
  whole clip.
- **Drone: `file_subtype` 11 `PHOTO_METADATA` / 13 `JSON`.** Named in a decompiled DJI-derived app but
  never requested against an aircraft; the firmware may serve them per index, in which case it is a
  single small `/v1` fetch. Untested — subtypes 3–16 were refused on a Neo 2.

## 17. Mavic 3: resolution + fps in the preview — ⬜ TODO

Drone cells show duration and size but no resolution, because the 94-byte DCF record is only decoded as
far as `+14` (mtime `@0`, size `@4`, index `@8`, duration `@12` — see
[DcfRecords](app/src/main/java/dev/konraditurbe/osmosis/dcf/DcfRecords.kt)). The remaining ~80 bytes are
unmapped and are the likely home of the format fields.

**Do it the way the camera one was done, because that worked:** the camera's `resolution` byte is a
DJI-wide format index (`10`=1080p, `16`=4K 16:9, `95`=2.7K 4:3, …) that was pinned by ground-truthing
manifest bytes against `ffprobe` output on files pulled off the card. Same method here — download a
handful of Mavic 3 clips at deliberately different resolutions and frame rates, then diff their records.

Two cautions from that exercise:
- **Table the enum, never compute it.** The camera's codes are sparse and unordered, and there is no
  reason to expect the drone's to be denser.
- **Don't assume the camera's table transfers.** It may well be the same DJI-wide index — worth testing
  first, since it would make this nearly free — but a wrong shared assumption would mislabel every clip.
