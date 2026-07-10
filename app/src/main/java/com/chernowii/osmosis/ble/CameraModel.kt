package com.chernowii.osmosis.ble

/**
 * Per-model camera capabilities, keyed on the BLE manufacturer model id (payload bytes [0:1], LE).
 *
 * Only the datalink UDP port and WiFi security actually vary across the Osmo line — pairing (the
 * "mbln" PIN), the `/v2` HTTP media API, and DJI_/CAM_ file naming are shared. Cells marked
 * [verified] were confirmed on real hardware (our own pcap + device tests for the Nano and Action 5
 * Pro; the osmo-download project for the 360). Everything else falls back to the most common config
 * (9004 + TCP-7001 poke + WPA2) so an unrecognized DJI Osmo is still *attempted* rather than refused
 * — there's simply no reference data for the Mimo-only Action 3/4/6 (we'd need a pcap of each).
 */
data class CameraModel(
    val name: String,
    val datalinkPort: Int = 9004,
    val tcpPoke: Boolean = true,
    val wpa3: Boolean = false,
    val verified: Boolean = false,
) {
    companion object {
        val DEFAULT = CameraModel("DJI Osmo camera")

        private val BY_ID: Map<Int, CameraModel> = mapOf(
            0x0010 to CameraModel("Osmo Action 2"),
            0x0012 to CameraModel("Osmo Action 3"),
            0x0014 to CameraModel("Osmo Action 4"),
            0x0015 to CameraModel("Osmo Action 5 Pro", datalinkPort = 10004, tcpPoke = false, verified = true),
            0x0017 to CameraModel("Osmo 360", wpa3 = true, verified = true),
            0x0018 to CameraModel("Osmo Action 6"),
            0x0019 to CameraModel("Osmo Nano", verified = true),
            0x0020 to CameraModel("Osmo Pocket 3", verified = true), // broadcasts no BLE mfr data — name fallback
            0x0021 to CameraModel("Osmo Pocket 4"),
        )

        /** Resolve by BLE model id; fall back to the BLE local name (e.g. the Pocket 3 sends no mfr data). */
        fun resolve(modelId: Int?, name: String?): CameraModel {
            BY_ID[modelId]?.let { return it }
            val n = name?.lowercase()?.replace(" ", "").orEmpty()
            return when {
                n.contains("pocket3") -> BY_ID.getValue(0x0020)
                n.contains("pocket4") -> BY_ID.getValue(0x0021)
                n.contains("360") -> BY_ID.getValue(0x0017)
                n.contains("nano") -> BY_ID.getValue(0x0019)
                n.contains("action6") -> BY_ID.getValue(0x0018)
                n.contains("action5") || n.contains("edge") || n.contains("xtra") -> BY_ID.getValue(0x0015)
                n.contains("action4") -> BY_ID.getValue(0x0014)
                n.contains("action3") -> BY_ID.getValue(0x0012)
                n.contains("action2") -> BY_ID.getValue(0x0010)
                else -> DEFAULT.copy(name = name?.takeIf { it.isNotBlank() } ?: DEFAULT.name)
            }
        }
    }
}
