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
    // The Pocket 3 has exactly one store — its microSD, always served at `/v2?storage=0`. Pin it there
    // instead of running the handle/HEAD storage resolution (tester-confirmed storage=0 across every
    // session). Other models can carry SD + internal, so they still resolve per file.
    val singleSdStorage: Boolean = false,
    // DJI drones (Mavic, Neo, …) speak the same BLE DUML but differ downstream: their WiFi creds only
    // unlock for the official pairing token ("DJI FLY", not "osmo"), pairing is confirmed by a ~2 s
    // power-button press, and their media API is NOT the camera datalink (see ROADMAP #14).
    val isDrone: Boolean = false,
) {
    /** The SetPairingPIN token this device expects: a drone only releases WiFi creds to "DJI FLY". */
    val pairingToken: String get() = if (isDrone) "DJI FLY" else "osmo"

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
        const val ID_MAVIC_3 = 0x0070  // drone (confirmed on hardware, mfr 7000…)
        const val ID_NEO_2 = 0x007e    // drone (tester scan, mfr 7e00…)

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
            0x0020 to CameraModel("Osmo Pocket 3", verified = true, singleSdStorage = true),
            0x0021 to CameraModel("Osmo Pocket 4"),
            // Drones (offload exploratory — see ROADMAP #14). BLE pair + WiFi creds work with the
            // "DJI FLY" token; the media API over the drone AP is still TBD (camera datalink fails).
            ID_MAVIC_3 to CameraModel("Mavic 3", isDrone = true),
            ID_NEO_2 to CameraModel("DJI Neo 2", isDrone = true),
        )

        /**
         * Xtra's shell-company product names for the DJI models they rebadge.
         * Keyed by the shared DJI BLE model id — the Xtra units advertise the *same* id
         * as the DJI original (confirmed on the Edge Pro / `0x0015`).
         */
        private val XTRA_NAMES: Map<Int, String> = mapOf(
            0x0019 to "Xtra Atto",      // rebadged Osmo Nano
            0x0014 to "Xtra Edge",      // rebadged Osmo Action 4
            0x0015 to "Xtra Edge Pro",  // rebadged Osmo Action 5 Pro — the only Xtra we've verified
            0x0020 to "Xtra Muse",      // rebadged Osmo Pocket 3
        )

        /**
         * Resolve by BLE model id, then the local name (the Pocket 3 sends no mfr data), then [brand].
         *
         * **Why brand matters:** the whole Xtra line runs a **10004 / no-poke** datalink, not the
         * DJI-standard 9004 + poke — a rebrand *firmware* change. The Edge Pro advertises the *same*
         * model id `0x0015` as a genuine Action 5 Pro (identical hardware) yet speaks 10004, and
         * decompiling the Xtra app shows its datalink is a **native JNI transport with the port baked
         * in as one global constant** — no per-model logic, no port passed from Java. So *every* Xtra
         * unit (identified hardware-side by its own OUI `EC:9E:EA`) gets 10004/no-poke, while genuine
         * DJI models keep 9004 + poke. If a guess is ever wrong the datalink retries [alternate] and
         * logs which port answered. Only the Edge Pro is hardware-verified; the other Xtra rebadges are
         * attempted-but-untested (no hardware to confirm).
         */
        fun resolve(modelId: Int?, name: String?, brand: Brand = Brand.UNKNOWN): CameraModel {
            val base = byIdOrName(modelId, name)
            if (brand != Brand.XTRA) return base
            val isEdgePro = modelId == 0x0015 || base.name.contains("Action 5")
            return base.copy(
                name = XTRA_NAMES[modelId] ?: if (isEdgePro) "Xtra Edge Pro" else "Xtra ${base.name}",
                datalinkPort = 10004, tcpPoke = false, verified = isEdgePro,
            )
        }

        private fun byIdOrName(modelId: Int?, name: String?): CameraModel {
            BY_ID[modelId]?.let { return it }
            val n = name?.lowercase()?.replace(" ", "").orEmpty()
            // Xtra product names map to their DJI twin (Atto=Nano, Edge=Action 4, Edge Pro=Action 5
            // Pro, Muse=Pocket 3). Matters for the mfr-data-less units (Pocket 3 / Muse) that only
            // resolve by name — a bare "xtra" must NOT fall through to 0x0015 or the Muse reads as an
            // Edge Pro. "edgepro" is tested before "edge" so the Pro isn't swallowed by the Action 4.
            return when {
                n.contains("pocket3") || n.contains("muse") -> BY_ID.getValue(0x0020)
                n.contains("pocket4") -> BY_ID.getValue(0x0021)
                n.contains("360") -> BY_ID.getValue(0x0017)
                n.contains("nano") || n.contains("atto") -> BY_ID.getValue(0x0019)
                n.contains("action6") -> BY_ID.getValue(0x0018)
                n.contains("action5") || n.contains("edgepro") -> BY_ID.getValue(0x0015)
                n.contains("action4") || n.contains("edge") -> BY_ID.getValue(0x0014)
                n.contains("action3") -> BY_ID.getValue(0x0012)
                n.contains("action2") -> BY_ID.getValue(0x0010)
                else -> DEFAULT.copy(name = name?.takeIf { it.isNotBlank() } ?: DEFAULT.name)
            }
        }
    }
}
