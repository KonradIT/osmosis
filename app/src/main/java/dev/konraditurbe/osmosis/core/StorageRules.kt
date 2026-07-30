package dev.konraditurbe.osmosis.core

/**
 * Maps a media record to its `/v2?storage=N` HTTP mount **from the DUML manifest alone** — the pure part
 * of storage resolution, split out from the Android side (the HEAD that confirms the guess) so it can be
 * pinned in tests against real captured handles for every camera.
 *
 * The record handle encodes the physical store: **internal** sets bit `0x40000000` (Nano `0x4010xxxx`,
 * Xtra/Action 5 internal `0x4004xxxx`, Action 6 `0x4010xxxx`), **SD** clears it (Xtra SD `0x0004xxxx`,
 * Pocket 3 `0x0004xxxx`). Verified 26/26 in the Xtra two-store pcap and cross-checked against the
 * Nano/OA5/OA6/OP3 handles in the manifest fixtures. Single-store models that don't follow "internal→1"
 * (the Pocket 3 has only its microSD, always served at `storage=0`) are pinned via [singleSdStorage].
 */
object StorageRules {

    /** Bit set on an *internal*-store handle; clear on an SD-store handle. */
    const val INTERNAL_BIT = 0x40000000L

    /**
     * The likely mount for a record, before the caller's one-HEAD confirmation:
     *  - a single-SD model (Pocket 3) is always `0`;
     *  - otherwise the handle's [INTERNAL_BIT] gives internal → `1`, SD → `0`;
     *  - `null` when no handle is available at all (photos-only list) — the caller must probe.
     *
     * Photos carry no delete handle, so [cmdHandle] (the group-fitted handle) stands in — it lives in the
     * same per-store namespace, so its bit is the store's bit.
     */
    fun mountGuess(singleSdStorage: Boolean, handle: Long, cmdHandle: Long): Int? {
        if (singleSdStorage) return 0
        val h = if (handle != 0L) handle else cmdHandle
        if (h == 0L) return null
        return if (h and INTERNAL_BIT != 0L) 1 else 0
    }
}
