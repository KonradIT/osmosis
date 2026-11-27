# Osmosis — Roadmap

### 6. Older Osmo Action cameras

| Camera | Album | Original download |
|---|---|---|
| Osmo Action (AC101) | BLE pairing, Wi-Fi and indexed listing observed on hardware | HTTP if available, experimental datalink fallback. Original download pending hardware verification. |
| DJI Action 2 | Indexed and path-based lists supported in code | Mimo selects HTTP v1, pending hardware verification |
| Osmo Action 3 (AC202) | Indexed and path-based lists supported in code | Mimo selects HTTP v2, pending hardware verification |

The Action 1 advertises IDs `0x0006`, `0x0007` or `0x0082`. Its album fixtures contain
65-byte DCF descriptors. The AC101 firmware's `DjiTransSrv_SendListFrag` packs a local FAT timestamp
and the media type. `DjiTransSrv_VideoMiscInfoGet` supplies a segment sub-index and millisecond
duration. Osmosis gives each advertised segment its own filename and request. A recording for which
the camera lists only the first part still needs a source for the other parts. [Record layout](MEDIA_PROTOCOL.md#parsed--index-based-osmo-action-123)

**Action 1 HTTP evidence.** Retail firmware `V01.10.00.40` includes `/etc/cherokee.conf` for port 80
with document root `/var/www` and an empty `DCIM` directory there. BusyBox 1.23.2 includes its
HTTP server applet. The startup scripts mount the card at `/tmp/SD0`. The supplied
`restart_webserver.sh` sources `/tmp/start_webserver.sh`, which
is absent from the stock root filesystem. How a stock camera would activate an HTTP server and map
its card into that directory remains open. Tester logs show connection refusals in RECORD and in
four other modes. [Hardware reports](https://github.com/KonradIT/Osmosis/issues/32)

In Mimo 2.10.9, `libdjisdk_jni.so` selects the transport in `FileTransferManager::TransferData`
(camera-type switch at `0x12ce0e0`). AC101 takes version 0. `CreateHandler` at `0x12ccaa0`
constructs the ordinary file-transfer handler for that version. Action 2 selects the v1 HTTP handler
and AC202 selects v2. This explains the reference app's choice while leaving the camera-side HTTP
question open.

The AC101 Wi-Fi session has a four-second receive timeout with the handshake Osmosis sends.
In the stock `libswudp.so`, `SW_Pro_Uav_Manage_Check_Deal` checks that timer at `0x20fd4`.
The receive path at `0x218ac` matches the UDP peer and session ID and refreshes the timer before
dispatching the packet. Ordinary UDP acknowledgements keep that timer running during HTTP reads.
The reported route failures for receivers 8/2, 8/1 and 3/0 concern separate DUML commands. Osmosis
skips those commands on AC101 and keeps sending transport acknowledgements during the album check.
On a Wi-Fi rejoin it opens a fresh UDP session, checks HTTP again and restores queued files whose
index, segment, name, size and capture time still match. The camera also tracks app presence.
Osmosis now sends Mimo’s 500 ms Wi-Fi ping during browsing and transfers to renew it. The
Wi-Fi manager can stop the access point after 60 seconds without a refresh.
[Action 1 heartbeat and test results](MEDIA_PROTOCOL.md#action-1-wi-fi-app-heartbeat)

On Action 2 and 3, Osmosis checks listed originals over HTTP and validates their media signatures.
Action 1 skips that check. Port 80 refused every connection in every tester session, in RECORD and
after 30 seconds in TRANSCODE, and the check cost about 32 seconds per connect. Selecting a listed
original and tapping Download starts a datalink transfer. Tapping Play on a video poster starts the
camera’s transcoded preview over the same session. Trimming still requires HTTP.

An Android 14 emulator exercised the production list and download code against simulated cameras.
The Action 1 static path, Action 2 v1 and Action 3 v2 each resumed an interrupted MP4 at byte
1,171. The file saved to MediaStore matched the source SHA-256 in each case. With both Action 1
URLs returning 404, Osmosis still listed the clip and restored RECORD after the 30-second check.
With HTTP unavailable throughout a separate run, the Action 1 datalink fallback resumed an MP4 at
byte 980, requested a missing chunk and saved a byte-identical original to MediaStore. Replaying a
captured Mavic 3 transfer independently verified reassembly across real UDP packet boundaries.
The Android viewer also played a 1280×720 AVC stream from the simulated camera, paused, sought,
resumed, and fetched another thumbnail after restoring the camera mode. Stock Action 1 hardware
checks are pending for a complete original download, thumbnails, screen JPEGs and video playback.
[Action 1 album playback](MEDIA_PROTOCOL.md#action-1-album-thumbnails-screen-jpegs-and-video-playback) · [Transfer format](MEDIA_PROTOCOL.md#action-1-original-download-over-the-datalink)

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

### 19. Migrate to CompanionDeviceManager API

CompanionDeviceManager will give us features such as auto-detect, less permissions, better handling for BLE, etc...

### 22. Show a tick for downloaded media

Just after media is downloaded, stateless. When app is restarted, and camera connects again, tick won't be there. Just visual insight into what was just downloaded.

### 23. Better camera disconnection flow

Works great on osmo nano but xtra just prompts to go back to cameras or input password
