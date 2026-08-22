# Osmosis — Roadmap

### 2. The rest of the Osmo line:

- **Action 4 (`0x14`)** — BLE pair and WiFi creds both succeed, but the AP never comes up (Android sees
  no SSID). Older BLE generation: MTU 510, no `fff7` characteristic. It has never been sent
  `0x07/0x39`; an OA4-gated probe waits on branch `osmo-action-4-debugging`.

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
duration, fps, resolution and size, all manifest fields, and nothing about exposure.

- **Stills — already on the wire.** The EXIF thumbnail path fetches the original's first 64 kB
  ([EmbeddedJpeg](app/src/main/java/dev/konraditurbe/osmosis/core/EmbeddedJpeg.kt)) and the same `APP1`
  block carries ISO, exposure time, aperture and focal length. We download and discard them today, so
  reading them costs one parse and **zero extra requests**. The obvious first increment.
- **Video — the `djmd` track**, which is protobuf and not encrypted. Needs a range read of the right
  atom rather than the whole clip.
- **Drone — `file_subtype` 11 (`PHOTO_METADATA`) / 13 (`JSON`)**, named in the enum recovered from a
  decompiled DJI-derived app ([MEDIA_PROTOCOL §29](MEDIA_PROTOCOL.md#29-http-media-api-v1--dcf-indexed))
  but never requested against an aircraft. Subtypes 3–16 were refused on a Neo 2.

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

### 19. DNG / sidecar file download:

Allow downloading sidecar files such as DNG photos, audio files, etc...

Need to do research on what sort of files can acompany each video/photo.

- Video: 

"Audio backup" / "Built In Mic Audio Backup" feature on Xtra Edge Pro/Osmo Nano.

- Photo:

DNG sidecar file when shooting in JPEG+DNG mode

UI:

"Add to queue" button remains one button, clicking adding to queue prompts to also append to the queue the sidecar file.

**Sidecar file detected**

Want to add (DNG/AAC/XXX) file to the queue as well?

*Yes / no*