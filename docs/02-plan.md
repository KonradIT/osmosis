# Osmosis — implementation plan

Goal: an Android app that offloads media from a **DJI Osmo Nano** with **zero DJI SDK**,
starting with *connect + query media manifest*.

## Toolchain constraint (drives every choice)

Pinned: **AGP 7.4.2 / Gradle 7.5.1 / JDK 15 / compileSdk 34 / minSdk 29**, built head-
lessly with `JAVA_HOME=/c/Program\ Files/Java/jdk-15.0.1 ./gradlew assembleDebug`.

Consequences:
- **No Jetpack Compose.** Modern Compose needs JDK 17 + Gradle 8+. We use **plain Android
  Views** (one Activity, a log `TextView` + buttons). This is a utility/RE tool; that's fine.
- Kotlin ~1.8.x (AGP 7.4.2-compatible), `sourceCompatibility = 15`, `jvmTarget = "15"`.
- Therefore we **cannot fork `dji-remote`** (Compose, Gradle 8.13, compileSdk 36). We
  *vendor its pure-Kotlin DUML classes* only (see protocol-map §7) and build the rest fresh.
- minSdk 29 is deliberate: `WifiNetworkSpecifier`/`requestNetwork` (API 29) is how we join
  the camera AP cleanly. `BLUETOOTH_SCAN/CONNECT` (31) and `NEARBY_WIFI_DEVICES` (33) are
  guarded by version checks.

## Module layout

```
app/
 build.gradle                 (AGP 7.4.2, no compose, jvmTarget 15)
 src/main/AndroidManifest.xml (BLE + WiFi perms)
 src/main/java/dev/konraditurbe/osmosis/
   duml/      DjiCrc, ByteReader, ByteWriter, DjiMessage, Payloads   <- vendored + tests
   ble/       OsmoScanner, GattClient (fff0/fff4/fff5, MTU, notify), Pairing
   net/       ApJoiner (WifiNetworkSpecifier+bind), HttpClient (/v2), Udp9004Client
   core/      CameraFile model, Manifest, ConnectionCoordinator (state machine)
   ui/        MainActivity (log + Scan/Pair/ActivateWifi/ListMedia buttons)
 src/test/java/...            vendored DUML unit tests (build gate)
```

## Permissions (AndroidManifest)

`BLUETOOTH_SCAN`(usesPermissionFlags="neverForLocation", maxSdk n/a) · `BLUETOOTH_CONNECT` ·
`ACCESS_FINE_LOCATION`(maxSdkVersion=30, for legacy scan) · `BLUETOOTH`,`BLUETOOTH_ADMIN`
(maxSdkVersion=30) · `ACCESS_WIFI_STATE`,`CHANGE_WIFI_STATE`,`CHANGE_NETWORK_STATE`,`INTERNET`
· `NEARBY_WIFI_DEVICES`(31+, neverForLocation). Runtime-request the dangerous ones.

## Connection state machine (ConnectionCoordinator)

```
IDLE → SCANNING → CONNECTING → DISCOVERING_GATT → SUBSCRIBED
     → ARMING_PAIR → PAIRING → (APPROVAL_WAIT) → PAIRED
     → ACTIVATING_AP → JOINING_WIFI → ONLINE
     → LISTING → MANIFEST_READY
(any) → ERROR(reason, rawBytes)
```
Every camera notification is logged raw (hex + parsed set/id/flags) so unknown Nano
responses are captured, not swallowed.

## Phases (each independently demoable)

### Phase 0 — De-risk the toolchain FIRST
Scaffold the Gradle project, vendor `duml/` + its unit tests, wire an empty MainActivity.
**Success = `./gradlew assembleDebug` produces an APK AND `./gradlew test` passes the DUML
CRC/encode/decode tests** under JDK 15. (If the pinned toolchain has surprises, find out now
with 5 files, not 30.)

### Phase 1 — BLE connect + observe
Scan (filter: name contains `Osmo`/`Nano`, or DJI manufacturer id `0xAA08`/`0xAAF7`), log the
**full advertisement incl. manufacturer data** so we learn the Nano's model byte (extends the
`10 00`→A2 … `17 00`→360 table). Connect GATT → discover `fff0` → get `fff4`/`fff5` → request
MTU 517 → enable notifications (CCCD `2902`) on `fff4` (and `fff5`).
**Success = we see DUML `0x55…` notifications streaming from the Nano** (telemetry `0x00/0x99`
etc.), decoded by the vendored `DjiMessage`.

### Phase 2 — PIN pairing
Write `[0x01,0x00]`→`fff4`; send `SetPairingPIN 07/45` with persisted UUID identifier + PIN.
Handle `PairingStatus`: `0x01` paired / `0x02` approval. Add a PIN text field + "approve on
camera" prompt. Persist identifier in SharedPreferences.
**Success = reach PAIRED, surviving app restart without re-approval.**

### Phase 3 — Media manifest (the milestone)
Probe cheapest-first (protocol-map §6):
1. Try DUML file-list over BLE (`0x00/0x26`) — read notifications.
2. Else `ConnectToWiFi 07/47` to activate AP → `ApJoiner` (WPA3→WPA2) → bind network.
3. Over the AP: probe for an HTTP listing endpoint; if none, run the **UDP-9004** sequence
   (ported from `file_list.py`) and parse `CAM_…` paths into `CameraFile`s.
**Success = a list of media (name, timestamp, seq, storage, size via HTTP HEAD) shown in-app.**

### Phase 4 — Download (later, not now)
HTTP `GET /v2?storage&path` with Range/resume + keepalive; save to MediaStore.

## Open questions we can only answer against the device (❓ in protocol-map)
- Nano BLE model byte + WiFi SSID pattern + WPA version.
- Is the "PIN" vestigial (fixed) or a real on-screen code?
- Does the Nano expose an HTTP directory-list endpoint (360 does not)?
- Is the manifest reachable over BLE alone, or is WiFi mandatory?

## First action on approval
Execute **Phase 0** and report the `assembleDebug` + `test` results before writing any
device logic — that proves the headless build works under the pinned toolchain.
```
