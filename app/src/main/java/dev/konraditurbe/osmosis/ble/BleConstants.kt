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
    // A second DJI id (wire C0 E5), used by the older Osmo Pocket generation — a public teardown of
    // that dock logs it as "Reserved ID <0xE5C0>", and it is not a Bluetooth SIG assignment at all
    // (the registry tops out around 0x10F4). No device we've scanned uses it; matching it costs
    // nothing and stops one showing up as an unnamed "other".
    const val DJI_COMPANY_ID_E5C0 = 0xE5C0

    fun isDjiCompanyId(cid: Int): Boolean =
        cid == DJI_COMPANY_ID || cid == DJI_COMPANY_ID_ALT || cid == DJI_COMPANY_ID_E5C0

    // Model id: normally the first two manufacturer-payload bytes little-endian, but newer cameras
    // leave those at zero and carry a product type further into the payload — see [BleAdvert], which
    // folds both formats back into this one id space. Names here are for the scan log; offload
    // capabilities per model live in CameraModel. Nano/Action 5 Pro verified on hardware.
    val MODEL_NAMES: Map<Int, String> = mapOf(
        0x0006 to "OsmoAction",   // Action 1 — unconfirmed here, no hardware
        0x0010 to "OsmoAction2",
        0x0012 to "OsmoAction3",
        0x0014 to "OsmoAction4",
        0x0015 to "OsmoAction5Pro",
        0x0017 to "Osmo360",
        0x0018 to "OsmoAction6",
        0x0019 to "OsmoNano",   // verified 2026-07-09: OsmoNano-C2D8 advertised model 0x0019
        0x0020 to "OsmoPocket3",
        0x0021 to "OsmoPocket4",
        // Verified 2026-08-07: OsmoPocket4P-6E55 advertised the new format with product type 218
        // (HG224), which corresponds to classic id 0x22.
        0x0022 to "OsmoPocket4Pro",
        0x0070 to "Mavic3",   // drone — offload confirmed end-to-end on hardware (see DroneSession)
        0x007e to "Neo2",     // drone — never offloaded; datalink port unconfirmed
    )
}
