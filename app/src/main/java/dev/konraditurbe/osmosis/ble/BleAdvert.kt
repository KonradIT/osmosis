package dev.konraditurbe.osmosis.ble

/**
 * Decodes the DJI BLE advertisement's manufacturer payload into a model id.
 *
 * DJI ships **two** advertisement formats and the newer one is why an Osmo Pocket 4 Pro scanned as
 * `model=unknown(0x0000)`: it leaves the classic model byte at zero and puts a 16-bit *product type*
 * further in. Every open-source implementation (osmo-download, node-osmo, dji-remote, and this app
 * until now) only reads the classic byte, so any camera using the new format is unidentifiable
 * except by its BLE name.
 *
 * The selector is **bit 2 of payload byte 5**: set, and a 16-bit little-endian product type at bytes
 * [10:12] carries the model; clear (or the payload too short to hold one), and it is the classic id at
 * [0:2]. Offsets here are payload-relative — Android splits the company id off into the SparseArray
 * key — so anything quoting them against the raw advert, company id included, is two higher.
 *
 * An OsmoPocket4P payload, byte by byte:
 *
 * ```
 * index  0  1  2  3  4  5  6  7  8  9 10 11 12 13
 * value 00 00 00 ee 00 04 bd 6e 56 20 da 00 00 10
 *       \___/ classic id = 0     ^ flag  \___/ productType = 0x00da = 218
 * ```
 */
object BleAdvert {
    /** Bit 2 of payload byte 5 ⇒ the 16-bit product type at [10:12] is populated. */
    private const val NEW_FORMAT_FLAG_INDEX = 5
    private const val NEW_FORMAT_FLAG_MASK = 0x04
    private const val PRODUCT_TYPE_INDEX = 10

    /**
     * DJI `ProductType` → the classic model id this app keys everything on.
     *
     * The two id spaces run in parallel: every camera has a DJI product-type number *and* a classic
     * advert id, and this maps the former onto the latter so the rest of the app only ever deals in
     * one of them. Codes in the comments (AC103, HG224, …) are DJI's internal names for the models.
     *
     * Only **218 (HG224)** has been seen on real hardware — a Pocket 4 Pro that reached the grid and
     * moved 43 MB before its AP dropped. The rest are the same table read straight through and are
     * unconfirmed; they cost nothing if wrong, because an unmapped value just falls through to the
     * classic byte and gets logged with its number so the next tester's log names it for us.
     *
     * **Shipping products only.** DJI's table also carries codes for hardware that has never been
     * announced, and this app is not the place to publish it: nobody can own one, so mapping it buys
     * nothing, and a public repo naming unreleased kit invites attention we do not want. If one ever
     * turns up in a scan it logs its raw product type and we deal with it then.
     */
    private val PRODUCT_TYPE_TO_MODEL_ID: Map<Int, Int> = mapOf(
        40 to 0x0006,   // OSMO_ACTION      — Osmo Action 1 (classic 0x06/0x07/0x82 all map here)
        143 to 0x0010,  // OSMO_ACTION_2    — AC103
        231 to 0x0012,  // AC202            — Osmo Action 3
        203 to 0x0014,  // AC203            — Osmo Action 4
        235 to 0x0015,  // AC204            — Osmo Action 5 Pro
        224 to 0x0017,  // OQ101            — Osmo 360
        223 to 0x0018,  // AC206            — Osmo Action 6
        222 to 0x0019,  // OW001            — Osmo Nano
        145 to 0x0020,  // HG212            — Osmo Pocket 3
        219 to 0x0021,  // HG214            — Osmo Pocket 4
        218 to 0x0022,  // HG224            — Osmo Pocket 4 Pro  [hardware-observed 2026-08-07]
        229 to 0x0087,  // OSMO_POCKET2     — HG211
    )

    /** What [modelId] found, so the scan log can say *why* a camera is unidentified. */
    data class Decoded(val modelId: Int?, val newFormat: Boolean, val rawProductType: Int?)

    /**
     * @param payload the manufacturer-specific data for a DJI company id, company id already stripped.
     * @return the classic model id, or null when neither format yields one.
     */
    fun decode(payload: ByteArray): Decoded {
        val pt = productType(payload)
        if (pt != null) {
            val mapped = PRODUCT_TYPE_TO_MODEL_ID[pt]
            // A product type we can read but haven't mapped is still worth reporting as new-format:
            // the classic byte is zero on these, so falling back would only ever say "unknown(0x0000)"
            // and lose the one number that identifies the camera.
            if (mapped != null || legacyModelId(payload) == null) return Decoded(mapped, true, pt)
        }
        return Decoded(legacyModelId(payload), false, pt)
    }

    /** Convenience for callers that only want the id. */
    fun modelId(payload: ByteArray): Int? = decode(payload).modelId

    /** The 16-bit product type, or null when this advert doesn't carry one. */
    private fun productType(payload: ByteArray): Int? {
        if (payload.size <= NEW_FORMAT_FLAG_INDEX) return null
        if ((payload[NEW_FORMAT_FLAG_INDEX].toInt() and NEW_FORMAT_FLAG_MASK) == 0) return null
        if (payload.size < PRODUCT_TYPE_INDEX + 2) return null
        return (payload[PRODUCT_TYPE_INDEX].toInt() and 0xFF) or
            ((payload[PRODUCT_TYPE_INDEX + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * The classic id: payload bytes [0:2] little-endian.
     *
     * Only the low byte actually varies; we keep the 16-bit read this app has always used, which
     * agrees on every camera seen so far (byte 1 is zero on all of them) and avoids churning ids
     * that are hardware-verified. Zero is not a model — it is what the new format leaves behind.
     */
    private fun legacyModelId(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        val id = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        return if (id == 0) null else id
    }
}
