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

        fun of(address: String?, name: String?): Brand {
            val oui = address?.uppercase()?.take(8) ?: ""
            val n = name?.lowercase() ?: ""
            return when {
                oui == XTRA_OUI -> XTRA
                n.contains("xtra") || n.contains("edge") -> XTRA
                n.contains("osmo") || n.contains("nano") || n.contains("dji") ||
                    n.contains("pocket") || n.contains("action") -> DJI
                else -> UNKNOWN
            }
        }
    }
}
