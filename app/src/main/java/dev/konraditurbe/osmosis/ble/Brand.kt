package dev.konraditurbe.osmosis.ble

/**
 * Camera brand, distinguished primarily by BLE MAC OUI. "Xtra" is a covert DJI shell-company
 * rebrand (e.g. the Xtra Edge Pro = DJI Osmo Action 5 Pro) and uses its own OUI EC:9E:EA, which
 * gives it away despite the DJI-identical firmware/protocol.
 */
enum class Brand {
    DJI, XTRA, UNKNOWN;

    companion object {
        const val XTRA_OUI = "EC:9E:EA"

        /**
         * [djiCid] = the advertisement carried DJI's BLE company id (0x08AA). That's the definitive
         * DJI tell — every DJI product broadcasts it, across many OUIs and even under a user-renamed
         * device (a Mavic 3 renamed "1001" still advertises it), so it's more robust than OUI/name.
         * Checked *after* the Xtra branches so the rebrand (its own OUI) still wins.
         */
        fun of(address: String?, name: String?, djiCid: Boolean = false): Brand {
            val oui = address?.uppercase()?.take(8) ?: ""
            val n = name?.lowercase() ?: ""
            return when {
                oui == XTRA_OUI -> XTRA
                n.contains("xtra") || n.contains("edge") -> XTRA
                djiCid -> DJI
                n.contains("osmo") || n.contains("nano") || n.contains("dji") ||
                    n.contains("pocket") || n.contains("action") -> DJI
                else -> UNKNOWN
            }
        }
    }
}
