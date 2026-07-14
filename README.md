# Osmosis — Android media client for DJI Osmo cameras

<div align="center">

![](./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

</div>

Third-party Android client to download videos and photos from DJI Osmo action / gimbal cameras. **No DJI SDK dependencies, no logins, no bloatware, no activation.**

<p align="center">
  <img src="./screenshots/Screenshot_20260714-201612_1.png" width="300"/>
  <img src="./screenshots/Screenshot_20260714-210742_1.png" width="300"/>
</p>

Works with the **DJI Osmo Nano**, which isn't supported by the official DJI SDK or the DJI R-SDK, and should also work with the rest of the DJI Osmo lineup (see [Supported cameras](#supported-cameras)). Also tested with the **Xtra Edge Pro**, a rebadged **DJI Osmo Action 5 Pro** made by DJI front company Xtra.

> Early days — this is `v0.1`. It works, but expect rough edges.

## Features

- **Zero-config pairing** — approve once on the camera screen; the app retrieves the camera's WiFi credentials over BLE, so there's no password to read off the camera or type in (with a one-time password prompt as a fallback for cameras that don't expose it).
- **Automatic WiFi handoff** — joins the camera's access point via Android's native `WifiNetworkSpecifier` and binds only this app's traffic to it, so your phone keeps its normal internet connection.
- **Media grid** with thumbnails, pulled straight off the camera.
- **Low-res streaming preview** — scrub any clip without downloading it first.
- **In-preview trimming** — set in/out points and download just that slice of the **high-res** clip. Keyframe-accurate stream copy (no re-encode), and only the window's bytes come off the camera.
- **Resumable download queue** — high-res downloads straight into your phone's gallery.
- **Live status** — battery, shooting mode, and storage (internal / SD) shown in a status pill.
- **Multi-camera** — remembers your cameras and shows which are currently in range.

## Supported cameras

| Camera | Status |
|---|---|
| Osmo Nano | ✅ Verified on hardware |
| Osmo Action 5 Pro / Xtra Edge Pro | ✅ Verified on hardware |
| Osmo 360 | 🧪 Coded, untested (WPA3 AP) |
| Osmo Pocket 3 / 4 | 🧪 Coded, untested |
| Osmo Action 2 / 3 / 4 / 6 | 🤷 Best-effort default |

The datalink port and WiFi security are resolved from the camera's BLE **model byte**, not its brand — so an unrecognized DJI Osmo is *attempted*, not refused. Got one working (or broken)? [Open an issue](../../issues) so it can be listed as fully supported.

## How it works

No DJI SDK — Osmosis speaks DJI's **DUML** protocol directly. Each session:

1. **BLE pair** with the camera (CmdSet `0x07`; you approve a token on the camera screen).
2. **Read the WiFi credentials over BLE** (SSID + passphrase).
3. **Wake and join** the camera's WiFi access point.
4. **List the media** via the DUML file-list command over UDP.
5. **Download** the high-res files over HTTP from the camera's `192.168.2.1` server.

The full reverse-engineered protocol — BLE pairing, WiFi handoff, the DUML file-list format, and more — is documented in [docs/01-protocol-map.md](docs/01-protocol-map.md).

## Privacy

Osmosis talks to **only your camera** (`192.168.2.1`). No analytics, no accounts, no activation servers, no cloud. Your media never leaves your phone and camera.

Contrast the official apps, which require a login and phone home to activation and telemetry backends (the Xtra companion app even bundles ByteDance analytics). Osmosis has none of that — the network security config restricts it to the camera's local address.

## Requirements

- **Android 10+** (API 29).
- **Bluetooth LE**.
- Permissions: Nearby devices (Bluetooth scan/connect) on Android 12+, or Location on older versions, plus WiFi state/change. No internet permission is needed for anything but the camera's local AP.

## Getting started

1. Turn on Bluetooth and open Osmosis; grant the permission prompts.
2. Power on the camera and tap it in the **Cameras** list.
3. **Approve the pairing prompt on the camera screen** (it shows a short token, e.g. `OSMO`).
4. **Approve the Android "join WiFi" dialog** when it appears.
5. Browse the grid. Tap a clip to preview, trim, and add it to the queue.
6. Tap **Download** — files land in your phone's gallery.

## Download

- GitHub releases:
- Unobtanium:
- F-Droid:

## Build from source

Standard Gradle Android build:

```sh
./gradlew assembleDebug
```

Plain Android Views (no Jetpack Compose). Built with AGP 7.4.2 / Gradle 7.5.1 / JDK 15 (JDK 11–17 should work); `compileSdk 34`, `minSdk 29`.

## Roadmap

Planned work and reverse-engineering notes live in [ROADMAP.md](ROADMAP.md).

## Credits

The monumental task of reverse engineering DJI's DUML protocol was initially done by the [DJI OGs](https://github.com/o-gs). DJI never released an SDK for their Osmo camera line, forcing users onto the DJI Mimo app. Thankfully several folks on GitHub open-sourced their reverse-engineering efforts to interact with DJI cameras over BLE and WiFi:

- https://github.com/dimadesu/dji-remote (Osmosis vendors its `DjiCrc` implementation)
- https://github.com/SemiConscious/osmo-download
- https://github.com/sniffingpickles/DJI-Wifi-Connect
- https://github.com/yigitkonur/lib-osmo-ble
- https://github.com/samuelsadok/dji_protocol
- https://github.com/xaionaro/reverse-engineering-dji

DJI's own official [Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo) was also a useful reference for the accessory (R-SDK) pairing protocol.

This application couldn't have been built without the work above. That said, much of it didn't work for the Osmo Nano and Edge Pro, so a significant additional reverse-engineering effort went into making Osmosis fully compatible with the DJI Osmo Nano.

## License

[MIT](LICENSE.txt).

## Disclaimer

Independent third-party project — **not affiliated with, authorized, or endorsed by DJI**. "DJI" and "Osmo" are trademarks of their respective owners. Use at your own risk; deleting or offloading media is your responsibility.
