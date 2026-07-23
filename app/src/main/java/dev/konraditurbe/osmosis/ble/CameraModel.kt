package dev.konraditurbe.osmosis.ble

/**
 * Per-model camera capabilities, keyed on the BLE manufacturer model id (payload bytes [0:1], LE).
 *
 * Only the datalink UDP port and WiFi security actually vary across the Osmo line — pairing (the
 * "osmo" PIN), the `/v2` HTTP media API, and DJI_/CAM_ file naming are shared. Cells marked
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
    /**
     * The other datalink config to try when [datalinkPort] never answers: the whole line is either
     * 9004 + TCP-7001 poke or 10004 with no poke, so one retry covers the unknown.
     */
    fun alternate(): CameraModel =
        if (datalinkPort == 9004) copy(datalinkPort = 10004, tcpPoke = false, verified = false)
        else copy(datalinkPort = 9004, tcpPoke = true, verified = false)

    companion object {
        val DEFAULT = CameraModel("DJI Osmo camera")

        private val BY_ID: Map<Int, CameraModel> = mapOf(
            0x0010 to CameraModel("Osmo Action 2"),
            0x0012 to CameraModel("Osmo Action 3"),
            0x0014 to CameraModel("Osmo Action 4"),
            // NOT verified on a genuine DJI unit — see the Xtra note below. DJI-standard config.
            0x0015 to CameraModel("Osmo Action 5 Pro"),
            0x0017 to CameraModel("Osmo 360", wpa3 = true, verified = true),
            0x0018 to CameraModel("Osmo Action 6"),
            0x0019 to CameraModel("Osmo Nano", verified = true),
            0x0020 to CameraModel("Osmo Pocket 3", verified = true), // broadcasts no BLE mfr data — name fallback
            0x0021 to CameraModel("Osmo Pocket 4"),
        )

        /**
         * Resolve by BLE model id, then the local name (the Pocket 3 sends no mfr data), then [brand].
         *
         * **Why brand matters:** the Xtra Edge Pro is a rebadged Osmo Action 5 Pro and advertises the
         * *same* model id `0x0015`, but 10004/no-poke was only ever confirmed on the **Xtra** — that
         * looks like a rebrand firmware change, not a DJI one. So the genuine DJI unit keeps the
         * DJI-standard 9004 + poke (unverified until a real Action 5 Pro is tested), and the Xtra —
         * identified hardware-side by its own OUI `EC:9E:EA` — gets the 10004 quirk. If either guess
         * is wrong the datalink retries [alternate] and logs which port actually answered.
         */
        fun resolve(modelId: Int?, name: String?, brand: Brand = Brand.UNKNOWN): CameraModel {
            val base = byIdOrName(modelId, name)
            if (brand != Brand.XTRA) return base
            val isEdgePro = modelId == 0x0015 || base.name.contains("Action 5")
            return base.copy(
                name = if (isEdgePro) "Xtra Edge Pro" else "Xtra ${base.name}",
                datalinkPort = 10004, tcpPoke = false, verified = isEdgePro,
            )
        }

        private fun byIdOrName(modelId: Int?, name: String?): CameraModel {
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
