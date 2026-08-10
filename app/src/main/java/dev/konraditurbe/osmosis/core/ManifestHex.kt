package dev.konraditurbe.osmosis.core

/**
 * The hex-dump format `tools/hexdump_to_bin.py` reads back into a test fixture.
 *
 * Shared rather than copied because the emitter and the parser are one contract: the tool matches the
 * fence, checks each row's offset against a running counter, and rejects the whole block if the
 * recovered length disagrees with the declared one. A second copy of this that drifted by a space
 * would produce logs that look fine and recover nothing.
 */
object ManifestHex {

    /** Bytes per row. The tool's fixed-width hex field is sized for exactly this. */
    private const val ROW = 32

    /**
     * Emit [bytes] as an offset/hex/ASCII block, truncated to [maxBytes].
     *
     * The declared size is what is actually dumped, with the true total after it when truncated —
     * the tool verifies the first figure and would drop a block that promised more than it wrote.
     */
    fun dump(log: (String) -> Unit, bytes: ByteArray, maxBytes: Int, what: String = "MANIFEST") {
        val shown = minOf(bytes.size, maxBytes)
        if (shown <= 0) return
        log("datalink: --- $what-HEX BEGIN (${shown}B" +
            (if (shown < bytes.size) " of ${bytes.size}B" else "") +
            "), report this to crack the layout ---")
        var off = 0
        while (off < shown) {
            val end = minOf(off + ROW, shown)
            val hex = StringBuilder()
            val asc = StringBuilder()
            for (i in off until end) {
                val b = bytes[i].toInt() and 0xFF
                hex.append("%02x".format(b))
                if (i - off == 15) hex.append(' ')
                asc.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            log("  %04x  %-65s %s".format(off, hex.toString(), asc.toString()))
            off = end
        }
        log("datalink: --- $what-HEX END ---")
    }
}
