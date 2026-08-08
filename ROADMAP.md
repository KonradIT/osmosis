# Osmosis — Roadmap

What we want next, and what is already there. Wire-level detail belongs in
[MEDIA_PROTOCOL.md](MEDIA_PROTOCOL.md) and [docs/01-protocol-map.md](docs/01-protocol-map.md), not here.

**Working today, hardware-verified:** Osmo **Nano**, **Action 5 Pro**, **Action 6**, **Pocket 3**,
**Pocket 4** and **Pocket 4 Pro**, the **Xtra Edge Pro** rebrand, and the **Mavic 3** drone. Full
pipeline: BLE pair → wake the AP → media list across internal *and* SD → thumbnail grid → proxy
preview → resumable download, plus delete, favourite and GPS sync. Every grid field (size, fps,
resolution, duration, ⭐) comes from the manifest — no MP4 `moov` parse, no HTTP `HEAD`.

Item numbers are stable, so an item keeps its number when it moves between the two lists. Nothing else
in the repo cites them — code and the protocol docs point at [MEDIA_PROTOCOL.md](MEDIA_PROTOCOL.md) or
at the implementing class, never back here.

---

## Todo

### 2. The rest of the Osmo line — Action 4 and Osmo 360

We want every Osmo in the selector to reach the grid. An unrecognised model already falls back to the
common config (9004 + TCP-7001 poke + WPA2) and is tagged `~experimental`, so it is *attempted*, never
refused; the datalink retries the alternate config (9004+poke ⇄ 10004/no-poke) and logs which port
answered. Per-model capabilities live in
[CameraModel](app/src/main/java/dev/konraditurbe/osmosis/ble/CameraModel.kt).

The **Action 2 and 3** sit in the table on the common config but have no data source at all — nobody has
run one, and they may well belong to #6's older index-based generation rather than here. Two models we
*have* seen get part-way and stop:

- **Action 4 (`0x14`)** — BLE pair and WiFi creds both succeed, but the AP never comes up (Android sees
  no SSID). Older BLE generation: MTU 510, no `fff7` characteristic. It has never been sent
  `0x07/0x39`; an OA4-gated probe waits on branch `osmo-action-4-debugging`.
- **Osmo 360 (`0x17`)** — pairs and hands out creds, then the phone never finds the `Osmo360` SSID, so
  it never reaches the datalink. Its AP bring-up differs (it is the only model advertising `fff7`) and
  may want a 360-specific enable command or 5 GHz.

**Blockers:** the Action 4 needs a tester to run the branch — cheapest first experiment is turning WiFi
on from the camera's own menu, then tapping it. The 360 needs a PCAPdroid capture of Mimo bringing its
AP up; deprioritised because 360-format footage needs Mimo to view anyway.

### 6. Older Osmo Action generation (index-based list)

We want browse + download on the Action 1/2/3, which use an older list format keyed by numeric
`FileIndex` with no path strings ([MEDIA_PROTOCOL §1](MEDIA_PROTOCOL.md#1-get-media-list), "Parsed —
index-based"). The **list is shipped and hardware-verified** (`decodeIndexList`, fixture
`action1_7.bin`) — the grid shows the clips. The **download is not**: our `/v1?file_index=` is a
placeholder, and HTTP `:80` is refused while the datalink is up.

**Branch: `support-osmo-action-1`.** Five commits on top of main: the index decoder ported onto the DCF
seam with the record layout corrected, the 65-byte stride confirmed on a second camera, and
`DcfTransferProbe` — which asks the camera outright whether it serves files over the datalink, and
tests whether `:80` is gated on playback mode. That probe is the experiment that answers this item's
core question; it has never been run against an Action.

**Blockers:** none any more. Unblocked 2026-08-07 — a decompiled DJI-derived app's media layer turns out
to be index-based as well, so the download path can be read off rather than guessed at. Two smaller
fixes ride along: the AP keepalive does not hold an Action's AP (`onLost` ~40 s after the
list), and the `/v2` storage detect should be skipped for index cameras (it fires two failing HEADs).

### 7. Highlight / moment markers

We want ⚑ chips under the preview scrubber that seek to each side-button mark. Built, verified on the
Xtra, then **cut from main** — the whole feature lives on branch `highlights`, and the protocol is
[MEDIA_PROTOCOL §3a](MEDIA_PROTOCOL.md#3a-highlight--moment-marks). Marks are not in the MP4 at all;
they are pulled from the camera on demand.

**Blockers:** the inline query misses often enough to matter, and a miss falls back to a fresh session —
which tears the live session down and rebuilds it. On a Nano that cost ~8 s per preview to return `0 []`,
and two previews in quick succession killed playback mode outright. Revive it when the inline path never
needs the fallback.

### 8. Camera control and live settings

We want to read the camera's current mode / resolution / fps and control it — start-stop recording, take
a photo, switch mode. Two halves, and one is done:

- **R-SDK** — solved via #9. `0x1D/0x05`→`0x1D/0x02` streams mode, resolution, fps and recording state;
  record is `0x1D/0x03` and mode switch `0x1D/0x04`, both already written in `RsdkProtocol`. Only a UI
  is missing.
- **Media path** — the command ids are documented with wire examples in
  [MEDIA_PROTOCOL §Camera control](MEDIA_PROTOCOL.md#camera-control), but they are firmware- and
  reference-derived and have never been sent to a Nano or Xtra. The status decode is the other gap: we
  subscribe to the `camcap_*` params and receive the `0x02/0x80` push, but which bytes carry mode /
  resolution / fps is unmapped. (An old guess — the low nibble of `0x02/0x80` byte 0 — was wrong and
  was removed.)

**Blockers:** the field decode needs on-device ground truth: change mode/res/fps on the camera and diff
the frames. The control commands side-effect real hardware, so verify them against a throwaway state.

### 11. Direct USB-C ↔ USB-C media read

We want to offload over a cable, skipping the BLE-pair → wake-AP → WiFi-join dance entirely and running
at cable speed. Approach: enumerate the camera over Android's USB host API with the phone as host; if it
presents as MTP, read it through the MediaStore path.

**Blockers:** unknown whether an Osmo presents as MTP / mass-storage to an Android host or only through
a proprietary DJI USB protocol, and which models do it at all — some default to charge-only and need a
USB-mode toggle first. Needs a USB-C ↔ USB-C cable, a host-capable phone, and probably a capture of a
wired Mimo transfer.

### 15. Background downloads with a progress notification

We want the download queue to survive leaving the app, with an ongoing notification: determinate
progress, current filename, *n of m*, and pause/cancel. Today it is a bare `Thread` started by the
Activity, so a multi-gigabyte transfer is at the mercy of process death and nothing shows in the shade.
Resumable range requests already exist in `MediaDownloader`, so a killed transfer should resume rather
than restart — that is most of the value of doing this.

**Blockers:** the network binding, not the notification. Downloads only work because the process is
bound to the camera AP (`bindProcessToNetwork`); a service has to own or share that binding and react
when the AP drops. Android 14+ needs a declared `foregroundServiceType` (`dataSync`) plus
`POST_NOTIFICATIONS`. And a queue that outlives the foreground session must not keep a drone transfer
lease alive behind the user's back (#14).

### 16. Per-file shooting details (ISO, shutter, EV…)

We want what Mimo's playback screen shows — what the camera was *set to* when the file was shot. We show
duration, fps, resolution and size, all manifest fields, and nothing about exposure. Distinct from #8,
which is the camera's *live* settings. Three sources, cheapest first:

- **Stills — already on the wire.** The EXIF thumbnail path fetches the original's first 64 kB
  ([EmbeddedJpeg](app/src/main/java/dev/konraditurbe/osmosis/core/EmbeddedJpeg.kt)) and the same `APP1`
  block carries ISO, exposure time, aperture and focal length. We download and discard them today, so
  reading them costs one parse and **zero extra requests**. The obvious first increment.
- **Video — the `djmd` track**, which is protobuf and not encrypted. Needs a range read of the right
  atom rather than the whole clip.
- **Drone — `file_subtype` 11 (`PHOTO_METADATA`) / 13 (`JSON`)**, named in the enum recovered from a
  decompiled DJI-derived app ([MEDIA_PROTOCOL §29](MEDIA_PROTOCOL.md#29-http-media-api-v1--dcf-indexed))
  but never requested against an aircraft. Subtypes 3–16 were refused on a Neo 2.

### 17. Mavic 3: resolution and fps in the grid

Drone cells show duration and size but no resolution, because the 94-byte DCF record is decoded only as
far as `+14` ([DcfRecords](app/src/main/java/dev/konraditurbe/osmosis/dcf/DcfRecords.kt): mtime `@0`,
size `@4`, index `@8`, duration `@12`). The format fields are somewhere in the remaining ~80 bytes.

Do it the way the camera's was done, because that worked: pull a handful of clips shot at deliberately
different resolutions and frame rates, then diff their records against `ffprobe`.

**Blockers:** needs an aircraft. Two cautions from the camera exercise — **table the enum, never compute
it** (the camera's codes are sparse and unordered), and **don't assume the camera's table transfers**.
It may well be the same DJI-wide index, which would make this nearly free, but a wrong shared assumption
mislabels every clip.

### 18. Drones beyond the Mavic 3

- **Neo 2 (`0x007e`) stalls at the session-open.** It hands over creds with the `DJI FLY` token and
  handshakes on `udp/9003`, then fails with *no drone serial seen in a beacon*. The `0x51` open has to
  echo the aircraft's serial, read out of its own `0x51/0x13` beacon
  ([MEDIA_PROTOCOL §27a](MEDIA_PROTOCOL.md#27a-neo-2--the-same-transport-a-different-unlock)). Two
  candidates, now instrumented rather than guessed: our parser required a serial of exactly 20 chars
  (a Mavic 3's length), or the Neo 2 never emits the beacon. A failed open now logs every `0x51` inner
  command and dumps any `0x13` payload, so the next run tells them apart. Secondary: its AP dropped
  ~16 s in, 112 ms *before* the list query went out.
- **Mini 3** — model byte unknown, so it resolves only by the `DRONE_ID_FLOOR` guess. It also enters
  QuickTransfer differently: no hold-to-confirm at all, **three quick power-button presses** instead,
  which is why the approval dialog needs its own line for it.
- **Delete and favourite are camera-only.** Drone records carry no manifest handle, so
  `CameraFile.deletable` is false and the long-press menu correctly offers neither. Wiring them means
  finding what a drone deletes *by* — plausibly the packed `file_index` itself.
- **Untested, would be cheap:** `/v2?storage=N&path=…` on a drone (believed to work, never exercised —
  everything goes through `/v1`), and `PROXY_MOOV` / `ORIGIN_MOOV` (`file_subtype` 15/16), which serve
  an MP4's `moov` alone and would replace the range request preview pays to find it.
- **Any other aircraft.** Ids at or above `0x40` fall back to drone defaults on a documented guess
  (`CameraModel.DRONE_ID_FLOOR`), which at least gets far enough to be diagnosable.

**Branches: `support-neo2` and `support-mini3`** — the same payload on both, one commit each, because
the diagnostic an unknown airframe needs is the same one. `DroneFrameCensus` answers what the existing
logging can't: `0x51 inner cmds seen: NONE` says the aircraft doesn't talk like a Mavic without saying
what it *does* do. So it censuses every CRC-valid frame by cmdset/cmd (nested included), the raw head
of each transport packet type, and any payload carrying a **serial-shaped run** (12–24 uppercase
alphanumerics) — which identifies the frame that carries the serial on *this* airframe even when it
isn't a `0x51/0x13`. Strictly diagnostic: it never latches a serial or changes what we send, because a
run that merely looks like a serial isn't one. `support-mini3` also carries the three-press line in the
approval dialog. `PcapAnalysis` rides along on both for reading a capture with our own decoder.

**Blockers:** hardware. Both branches are instrumentation waiting for one run each — nothing more can
be deduced from what we have.

---

## Done

### 1. In-preview trimming → trimmed high-res download — 2026-07-10

Set in/out points on the paused preview scrubber (`[` / `]`), and "Add to Queue (trimmed)" writes just
that slice of the **high-res** clip (`DJI_…_0247_D_28-48s.MP4`) — never the LRF, never the whole file.

The camera has **no server-side trim**, so the cut is client-side but still only pulls the window:
`MediaExtractor` on the full-res HTTP URL → `seekTo(startUs, PREVIOUS_SYNC)` → stream-copy into a
`MediaMuxer`. It honours the process network binding and range-fetches, so only the window's bytes
(+ `moov`) come off the AP. Stream copy means no re-encode, and the start snaps to the nearest keyframe
≤ the in-point — frame-accurate would need re-encoding, deliberately out of scope.
[MediaDownloader.downloadTrimmed](app/src/main/java/dev/konraditurbe/osmosis/net/MediaDownloader.kt),
[MediaPreviewActivity](app/src/main/java/dev/konraditurbe/osmosis/ui/MediaPreviewActivity.kt).

### 3. Retrieve the WiFi password over BLE — 2026-07-14

The camera hands out its own SSID and passphrase over BLE once paired, so there is no manual entry:
after pairing, query `0x07/0x07` then `0x07/0x0e` and feed the results straight into the WiFi join
([§24](MEDIA_PROTOCOL.md#24-getwifissid), [§25](MEDIA_PROTOCOL.md#25-getwifipassword)).

**Pacing matters:** `fff5` is write-without-response, so the two queries must be ~500 ms apart or the
second drops, and the first must not race the pairing-approval ACK. Falls back to the saved password or
a one-time prompt. The passphrase is cached per-MAC and **never logged** — only its length.

### 4. Delete a file on the camera — 2026-07-21

Long-press a grid cell → confirm → the file is gone off the card. Verified on the Nano and the Xtra Edge
Pro. Irreversible, so it sits behind a confirm dialog showing the filename and handle, and is only
offered for files we resolved a handle for. Wire: `0x00/0x28`
([MEDIA_PROTOCOL §2](MEDIA_PROTOCOL.md#2-delete-media)); the handle is the `u32-LE` at the head of each
manifest record. Runs inline on the live session since #12.

**Two deliberate gaps.** *Photos are non-deletable*: video records carry the `03 ff 19 06` marker we
anchor the handle on, photo records lay out differently, and widening the search would latch onto the
*neighbouring* video's marker — a wrong-file irreversible delete, so we fail safe. *Batch delete is
unsent*: the payload is `[count:u8][handle …]` and `deleteFiles` already builds N, but every capture we
have is `n=1`, which makes the leading `u8` indistinguishable from a constant. Settle it with one Mimo
capture of a multi-select delete before writing any batch path.

### 5. Osmo Nano: dock and power stats — 2026-07-23

The status pill shows pack voltage, charge/draw current, and whether the camera is docked and charging,
decoded from the `0x0d/0x02` battery push
([MEDIA_PROTOCOL §20](MEDIA_PROTOCOL.md#20-battery--power-also-the-only-place-the-dock-reports-in)).
`docked` (`@27`) and `charging` (`@32`) are genuinely separate signals — one transition showed attached
but not yet drawing charge.

**The dock's own battery % is not exposed by the camera** — closed as *not possible*, not *not done*.
All three plausible homes were excluded: no second DUML battery device appears when docked, none of the
53 subscribable params is dock-related, and nothing in the battery frame's 34 bytes tracks it. *Dock
SD-card space* is a separate, still-open question: `0x02/0x80` reports the active store only.

### 9. R-SDK GPS sync — 2026-07-23, verified on an Action 5 Pro

The 🛰️ toggle streams the phone's GPS into the camera for geotagging and speed/route overlays, ported
from DJI's own `Osmo-GPS-Controller-Demo`. It is **BLE-only** — same GATT as the media path, completely
different frames (SOF `0xAA`, header CRC-16 + whole-frame CRC-32, DJI's `0x3AA3` init), ported in
`rsdk/RsdkProtocol` and verified byte-for-byte against the demo's own C. `rsdk/GpsService` is a
foreground service pushing at 1 Hz. Uses **LocationManager, not FusedLocationProvider** — no
Play-Services dependency, and it uniquely exposes the satellite count the frame carries.

Two field-test bugs, both fixed: we subscribed to `GPS_PROVIDER` alone and seeded from
`getLastKnownLocation()`, so a stale seed was pushed at 1 Hz for a whole clip (now FUSED + GPS + NETWORK,
gated on each fix's own timestamp), and the same stale fix made `gpsTime` read an hour behind. Proven
objectively rather than by eye: the failing clip's `djmd` track decoded to 719 points with exactly one
distinct position and one distinct timestamp. That track is **protobuf, not encrypted** — read it with
[`pyosmogps`](https://github.com/francescocaponio/pyosmogps), which doubles as the regression harness.

**Branch `rsdk-device-id`** (unmerged) names the camera from the `device_id` its connection request
carries, and settles which models can do this at all: DJI's R-SDK table covers the Action 4/5 Pro/6 and
the 360, lists the Nano as unsupported and omits the Pocket 3. It also establishes on hardware that the
**Xtra rebrand strips R-SDK out** — the Edge Pro connects over GATT and then answers a media-path
battery push instead of any `0xAA` frame — a second firmware divergence alongside its 10004 port.

### 10. Wake a sleeping camera over BLE — 2026-07-23

Tap a sleeping Nano in the list and it wakes and offloads. It is a **command sequence, not a broadcast**:
a sleeping camera keeps advertising `ADV_IND`, and Mimo simply connects and drives it with DUML
([MEDIA_PROTOCOL — waking a sleeping camera](MEDIA_PROTOCOL.md#waking-a-sleeping-camera)).

The bug that hid it: the DUML receiver byte is `(id << 5) | type`, and two of the commands are **not
addressed to the camera** — `0x00/0x2b` goes to `0xF0` and `0x53/0x10` to `0x1C`. Addressed to Camera
(`0x01`) every one is answered `e0` and nothing wakes. Pinned by
[SessionCommandsTest](app/src/test/java/dev/konraditurbe/osmosis/duml/SessionCommandsTest.kt), because
the failure is silent.

### 12. Faithful long-lived session — 2026-07-30

**The keystone.** Every "fresh registered session" workaround — paging (~15 s/page), delete (~9 s),
favourite, burst expand, highlights — existed because our session drifted out of the camera's
write-accept window, and the churn was itself harmful: repeated re-registration collapsed an Xtra's
two-storage split so half the thumbnails 404'd. All commands now run **inline on one session**, verified
on Nano and Xtra.

One field caused it. The datalink is a sliding-window transport and a command's `r8-9` and `r10-11` are
**both in the app's own seq space**; we were putting `lastCamSeq` in `r8-9`, so it chased the camera's
telemetry stream (~10× faster, different phase) and the receiver silently dropped **writes** while reads
stayed lenient. Fix: `r8-9 = udpSeq − 8`, hold playback for the whole session, and serialise commands on
the thread that owns the socket. See
[MEDIA_PROTOCOL — datalink transport / sequencing](MEDIA_PROTOCOL.md#datalink-transport--sequencing--the-one-that-makes-commands-land-inline).

### 13. Scrub preview off the range-seekable proxy — 2026-08-04

A snapshot window floats above the seek bar while you drag, showing the frame under your thumb.
`ScrubFrames` decodes straight off the camera with `MediaMetadataRetriever.getScaledFrameAtTime` +
`OPTION_CLOSEST_SYNC` — keyframes only, because an exact seek would drag every P-frame back over the AP.
Two tiers behind one `nearest(ms)`: a 12-cell grid prefetched in the background so the bubble is never
empty, plus on-demand frames that jump the queue as the thumb settles. Dragging no longer seeks the
player; the clip jumps once, on release.

**Measured on hardware** (Nano `.LRF` + Xtra `.XRF`, 41+ frames, 0 failures): ~260 ms/frame on the Xtra,
~420 ms on the Nano, and **flat regardless of seek distance** — a frame at 14 s and one at 325 s of the
same clip cost the same. Caching the whole ~17 MB proxy on open is still possible but no longer pressing.

### 14. Drone offload — 2026-08-02, verified on a Mavic 3

BLE pair with the `"DJI FLY"` token → creds → AP → a `0x51/0x02` session-open the cameras don't need →
the whole library paged to the oldest file, thumbnails on every cell, proxy preview, full and partial
download, plus battery and storage in the pill. Ten pages, 373 files, terminating cleanly, in ten
datalink transfers. Protocol in
[MEDIA_PROTOCOL §27–31](MEDIA_PROTOCOL.md#dji-drone-quicktransfer-media-offload).

The same DUML stack as a camera with two swaps behind one `MediaAddressing` seam: the manifest is flat
94-byte **DCF records** instead of CompositePack, and media is addressed by packed `file_index` over
**`/v1`** instead of by path over `/v2`.

Four things that cost real time and are worth not re-deriving:

- **The gate is the `0x51` session-open.** Before it a drone streams ~2 empty keepalive frames/s and
  answers nothing; a second after it, ~1200 frames/s and every command works. Only a capture against a
  **cold** drone contains the transition.
- **Every `0x00/0x26` transfer holds a lease** — list *or* thumbnail — and must be released (`0x4a`
  subtype `+4`). We released lists but not thumbnails, so browsing died a dozen cells in while telemetry
  kept streaming and the link looked healthy. Stills now take their thumbnail from the EXIF `APP1` block
  inside the original (one 64 kB ranged request), so the datalink carries nothing but page queries.
- **The `0x4a` length is 12 bits plus a `0x1000` FINAL flag.** Read it as a plain length and short
  frames parse fine while every long one silently mis-parses.
- **`file_index` is packed**, not a flat number: storage in bits 31:30, a **14-bit** directory in 29:16,
  file number in 15:0. Masking the directory as 16 bits folds the storage bits in and every internal-
  storage file vanishes from the grid.

Reading a capture with our own decoder: `PcapAnalysis` (test sources, skipped unless `OSMOSIS_PCAP` is
set) walks a pcap with the app's own `DumlTransport.scanFrames` and prints the pktType mix, command
histogram, `0x4a` subtypes and a media timeline. Every byte-level claim above came out of it.
