package dev.konraditurbe.osmosis.ble

/**
 * Per-model camera capabilities, keyed on the BLE manufacturer model id (payload bytes [0:1], LE).
 *
 * Only the datalink UDP port and WiFi security actually vary across the Osmo line — pairing (the
 * "osmo" PIN), the `/v2` HTTP media API, and DJI_/CAM_ file naming are shared. Cells marked
 * [verified] were confirmed on real hardware: **Nano, Action 5 Pro, Action 6 and Pocket 3** all
 * browse + download on 9004 (tester-confirmed). Everything else falls back to the most common config
 * (9004 + TCP-7001 poke + WPA2) so an unrecognized DJI Osmo is still *attempted* rather than refused.
 * Still open: the Action 4 (pairs, but its AP never comes up) and the 360 (parked — 360-format files
 * need Mimo anyway).
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
        const val ID_OSMO_NANO = 0x0019

        private val BY_ID: Map<Int, CameraModel> = mapOf(
            0x0010 to CameraModel("Osmo Action 2"),
            0x0012 to CameraModel("Osmo Action 3"),
            0x0014 to CameraModel("Osmo Action 4"), // pairs + BLE creds, but its AP never comes up (open)
            // Genuine Action 5 Pro on 9004 — tester-confirmed (grid + download). The Xtra rebrand
            // gets flipped to 10004 by resolve(); see the Xtra note below.
            0x0015 to CameraModel("Osmo Action 5 Pro", verified = true),
            // 360 stays unverified: only ever scanned, never a successful offload — and its files are
            // 360-format (need Mimo to view), so it's parked regardless of transport.
            0x0017 to CameraModel("Osmo 360", wpa3 = true),
            0x0018 to CameraModel("Osmo Action 6", verified = true),
            ID_OSMO_NANO to CameraModel("Osmo Nano", verified = true),
            // Broadcasts no BLE mfr data — name fallback. Tester-confirmed on 9004; its _OP3-suffixed
            // naming decodes in full (path + ext + delete handle). Note: rejects 0x53/0x10 (e0) but its
            // AP comes up anyway via the 0x00/0x2b session, so the wake is belt-and-suspenders here.
            0x0020 to CameraModel("Osmo Pocket 3", verified = true),
            0x0021 to CameraModel("Osmo Pocket 4"),
        )

        /**
         * Resolve by BLE model id, then the local name (the Pocket 3 sends no mfr data), then [brand].
         *
         * **Why brand matters:** the Xtra Edge Pro is a rebadged Osmo Action 5 Pro and advertises the
         * *same* model id `0x0015`, but 10004/no-poke was only ever confirmed on the **Xtra** — that
         * looks like a rebrand firmware change, not a DJI one. So the genuine DJI unit keeps the
         * DJI-standard 9004 + poke (now tester-confirmed on a real Action 5 Pro), and the Xtra —
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
