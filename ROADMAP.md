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
  ([decodeComposite](app/src/main/java/dev/konraditurbe/osmosis/net/DatalinkClient.kt), reverse-engineered
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

## 7. Retrieve highlight / moment markers — ✅ PROTOCOL SOLVED, ⏸️ UI blocked on #12 (2026-07-29)

DJI "highlight" marks (side-button presses during recording) are **NOT stored in the MP4** — proven
by an exhaustive teardown of an Action 4 + Xtra clip: not in chapters/tags, not in the `dvtm` protobuf
metadata track (per-frame telemetry only — orientation quat, ISO, shutter, WB), not in the `dbgi`
(sensor-debug) track, not in `udta`, not as HEVC SEI. They live **on the camera** and are pulled on
demand — the Xtra app shows them in its *trim/editor* view.

**The command is `0x02/0xff`** (the SDK's generic `camera_expansion_cmd`; `PullHighLightAction`).
RE'd empirically from a PCAPdroid capture of the Xtra app opening two marked clips' trim views —
which matched hardware ground truth byte-for-byte (a 2-mark clip → 4000/7000 ms; a 3-mark clip →
1000/3000/5000 ms). Read-only, so we run it inline on the live datalink (pause the keep-alive, query,
resume — `DatalinkClient.getHighlights`).

```
request  0x02/0xff → Camera(0x01):  40 2f 00 01 0b 00 00 00 [handle:u32-LE] 00 00
reply    0x02/0xff:  00 · 40 2f 00 01 · [len:u32-LE] · [handle:u32-LE] · [count:u8] · 00 ·
                     { 00 [startTimeMs:u32-LE] } × count     # count @ byte 13, first mark @ 16, stride 5
```
- `handle` = the video's manifest delete-handle (§2). `startTimeMs` = mark start in ms (we show whole
  marks; a `duration` field wasn't distinguishable in the reply — the marks read as points).

**Why there's no UI yet.** The camera only answers `0x02/0xff` on a **freshly registered session** — on the
live browse session it silently ignores it (proven: the request goes out byte-identical to the app's, and
status polls on the same sequence *are* answered). Doing a teardown+re-register per video open worked but
the churn **destabilized the Xtra**: the manifest's two-storage split collapsed (every file stamped
`storage=0` → internal-storage thumbnails and previews 404'd), and it persisted across reconnects. So the
implementation was **removed rather than left dormant** — the byte spec above (and MEDIA_PROTOCOL §3a) is
the deliverable. Re-add it (~30 lines: query + parse + a row of tappable ⚑ m:ss chips that seek) once **#10**
lands a faithful session, where it runs inline with no re-registration.

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

## 12. Faithful long-lived session (persistent playback + in-window `udpSeq`) — 🔬 NEXT UP, unblocks #7 & more

> **This is the keystone item.** Every "fresh registered session" workaround in the app — paging, delete,
> favorite, and the highlight pull (#7) — exists because our session drifts out of the camera's accept
> window. Worse, the churn is actively harmful: repeated teardown/re-register **destabilized an Xtra** so
> its manifest's two-storage split collapsed (all files stamped `storage=0` → half the thumbnails/previews
> 404'd), persisting across reconnects. Fixing the session removes the workarounds *and* the fragility.
>
> **The diagnosis.** We set `udpSeq = lastCamSeq + 8` at registration, then advance it only when *we*
> send. The camera meanwhile floods telemetry (`0x00/0x99`, `0x02/0x80`) and races its own sequence ahead,
> so ours falls behind the write-accept window: reads still answer, writes and expansion commands are
> silently dropped. Mimo/Xtra keep the two locked with a constant heartbeat, which is why *their* delete,
> favorite, highlight and pagination all work inline on one session with no pauses.
>
> **The fix:** re-derive `udpSeq` from the camera's current `lastCamSeq` on every keep-alive tick (track
> the camera instead of drifting), hold playback mode for the whole browse session, and mirror Mimo's
> fuller heartbeat set (`0x02/0x8e` for each subsystem id it polls: 06,08,09,0f,14,15,18,1f,20,28,29 —
> we only send `14`). Then convert each command back to inline and re-verify on-device one at a time.
> Ground truth for all of it is in the `reference/` pcaps (Nano + Xtra).

The media list only paginates while the camera is in **playback mode** (`0x02/0x0c` `01 01 00 01`;
`01 01 00 00` leaves). We enter it **only for the duration of a fetch** — once on the initial
newest-45 load, and again inside every `fetchNextPage()` — and each older page spins up a **fresh
registered session** (~15 s/page). We have to, because the camera drops the datalink after ~2 pages and
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
