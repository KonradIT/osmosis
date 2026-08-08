package dev.konraditurbe.osmosis.drone

/**
 * Which QuickTransfer machinery each DJI aircraft uses, keyed by its BLE model id.
 *
 * Recovered from an exhaustive scan of the current DJI Fly build: the aircraft rows of its WLM device
 * config, the two WiFi-mode handler membership sets, and every UAV camera's HTTP-download
 * initialiser. So this is a complete enumeration of what that app supports — not a list of aircraft
 * anyone has flown.
 *
 * ⚠️ **Only the Mavic 3 row is hardware-verified here.** Every other row is a static reading of the
 * app, which is strong evidence about what to *send* and no evidence at all about what comes back.
 * Treat a first run on any other airframe as a discovery run.
 */
internal object DroneProducts {

    /** How the aircraft serves file bytes over HTTP once QuickTransfer is up. */
    enum class Http {
        /** `/v1?file_index=<packed>&file_subtype=<n>` — renditions are subtypes of one index. */
        V1,

        /** `/v2?storage=<slot>&path=<physical path>` — the same shape the Osmo cameras use. */
        V2,

        /** The aircraft installs no HTTP download at all; bytes stay on the native transfer path. */
        NATIVE_ONLY,

        /** Known aircraft with no QuickTransfer workflow: it has WLM config but no WiFiFast asset. */
        NONE,
    }

    data class Product(val name: String, val http: Http) {
        /** Can this aircraft be offloaded at all? [Http.NONE] aircraft are named so a refusal can say why. */
        val quickTransfer: Boolean get() = http != Http.NONE
    }

    /**
     * Model id → product. Ids are the BLE product byte, which is the same value our scanner reads out
     * of the manufacturer data — a Mavic 3 is `0x70` here and `mfr[cid=08aa 7000…]` on the wire.
     *
     * The Mavic 3 Enterprise Series is deliberately absent: the app gives it literal product id 0,
     * which is a sentinel rather than an aircraft, and it has no Fly camera backend at all.
     */
    private val BY_ID: Map<Int, Product> = mapOf(
        0x8B to Product("DJI FPV", Http.NONE),            // WLM row, but no WiFiFast asset
        0x8C to Product("DJI Air 2S", Http.NONE),         // likewise — pairing refuses it
        0x8D to Product("DJI Mini 2", Http.NATIVE_ONLY),
        0x70 to Product("DJI Mavic 3", Http.V1),          // hardware-verified end to end
        0x71 to Product("DJI Mini 3 Pro", Http.V1),
        0x74 to Product("DJI Mavic 3 Classic", Http.V1),
        0x73 to Product("DJI Mavic 3 Pro", Http.V2),
        0x75 to Product("DJI Mini 3", Http.NATIVE_ONLY),
        0x72 to Product("DJI Air 3", Http.V2),
        0x76 to Product("DJI Mini 4 Pro", Http.V2),
        0x77 to Product("DJI Avata 2", Http.V2),
        0x78 to Product("DJI Mavic 4 Pro", Http.V2),
        0x79 to Product("DJI Mini 5 Pro", Http.V2),
        0x7A to Product("DJI Flip", Http.V2),
        0x7B to Product("DJI Neo", Http.V2),
        0x7C to Product("DJI Air 3S", Http.V2),
        0x7E to Product("DJI Neo 2", Http.V2),
        0x7F to Product("DJI Avata 360", Http.V2),
        0xD0 to Product("DJI Lito X1", Http.V2),
        0xD2 to Product("DJI Lito 1", Http.V2),
    )

    fun of(modelId: Int?): Product? = modelId?.let { BY_ID[it] }

    /**
     * Does this aircraft serve media by physical path over `/v2`?
     *
     * Defaults to **false** for anything unrecognised, which keeps an unknown airframe on the one
     * scheme we have actually made work rather than on the one we have only read about.
     */
    fun usesHttpV2(modelId: Int?): Boolean = of(modelId)?.http == Http.V2
}
