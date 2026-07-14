package dev.konraditurbe.osmosis.ble

import java.util.UUID

object BleConstants {
    val SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHAR_FFF4: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    val CHAR_FFF5: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // DJI BLE manufacturer company IDs. On the wire the id is little-endian (AA 08 / AA F7),
    // so Android's SparseArray key is 0x08AA / 0xF7AA.
    const val DJI_COMPANY_ID = 0x08AA
    const val DJI_COMPANY_ID_ALT = 0xF7AA

    // Model id = first two manufacturer-payload bytes, little-endian (names for the scan log;
    // offload capabilities per model live in CameraModel). Nano/Action 5 Pro verified on hardware.
    val MODEL_NAMES: Map<Int, String> = mapOf(
        0x0010 to "OsmoAction2",
        0x0012 to "OsmoAction3",
        0x0014 to "OsmoAction4",
        0x0015 to "OsmoAction5Pro",
        0x0017 to "Osmo360",
        0x0018 to "OsmoAction6",
        0x0019 to "OsmoNano",   // verified 2026-07-09: OsmoNano-C2D8 advertised model 0x0019
        0x0020 to "OsmoPocket3",
        0x0021 to "OsmoPocket4",
    )
}
