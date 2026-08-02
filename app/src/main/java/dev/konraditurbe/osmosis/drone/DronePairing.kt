package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.duml.DjiPairMessagePayload
import dev.konraditurbe.osmosis.duml.OsmoCommands

/**
 * What differs about pairing a **drone** over BLE, as opposed to an Osmo camera.
 *
 * Three things, all of which were sitting in `MainActivity` next to the camera flow:
 *  - the token: a drone releases WiFi credentials only for **`DJI FLY`** (see `CameraModel`);
 *  - the identity: DJI Fly's own install UUID rather than our generic one ([IDENTIFIER]);
 *  - a post-pair setup sequence DJI Fly runs before it will do anything useful ([sendBleSetup]).
 *
 * The GATT plumbing itself is identical, so it stays in `ble/`.
 */
object DronePairing {

    /**
     * The identity sent with `SetPairingPIN`. A drone gets **DJI Fly's own install UUID**, not the
     * generic one a camera accepts: it already gates WiFi credentials on the `DJI FLY` token, so it
     * plausibly discriminates on this too. This was the last field left unvaried while it accepted our
     * credentials yet refused every datalink command — which turned out to be the `0x51` session-open,
     * not this. Kept because it is what the reference app sends.
     */
    val IDENTIFIER: String get() = DjiPairMessagePayload.DJI_FLY_IDENTIFIER

    /** A drone is confirmed at the aircraft, not on a screen it doesn't have. */
    const val APPROVAL_MESSAGE = "Press and hold the drone's power button for ~2 seconds to confirm pairing."

    /**
     * The setup sequence DJI Fly runs **over BLE** right after pairing a Mavic, replayed verbatim from a
     * btsnoop taken alongside a working QuickTransfer browse (2026-08-01, 17:30:53).
     *
     * We had been sending these same commands over the **WiFi datalink**, where the drone accepts
     * nothing at all before the `0x51` open — so they never actually ran. Over BLE it demonstrably
     * answers us (pairing and both credential getters work), which makes this the one transport where
     * the sequence can take effect.
     *
     * Receiver addressing recovered from each captured frame's DUML target (`(id shl 5) or type`):
     * `0x2802` = type 8/id 1, `0x6F02` = type 15/id 3, `0x4F02` = type 15/id 2, `0x0302` = type 3/id 0.
     */
    private val SETUP = listOf(
        Frame(0x00, 0x51, 0x2802, "06"),
        Frame(0x00, 0xE5, 0x6F02, "323201"),
        Frame(0x03, 0x20, 0x0302, "034fdf6802ba68c7ff2d116e6a"),
        Frame(0x00, 0x4F, 0x4F02, "040000000000000000"),
        Frame(0x00, 0xE5, 0x6F02, "040401"),
        Frame(0x00, 0x51, 0x0302, "04"),
        Frame(0x03, 0xF8, 0x0302, "5806be97"),
        Frame(0x03, 0xF8, 0x0302, "0b163bde0b163bdf0b163be0713d65cbef979092ef3963f5"),
        Frame(0x00, 0x4F, 0x4F02, "0100000000e8030000"),
        Frame(0x00, 0x99, 0x2802, "0100060063616d657261"),
    )

    private class Frame(val set: Int, val cmd: Int, val target: Int, val payload: String)

    /**
     * Write [SETUP] through [write], paced by [schedule].
     *
     * The pacing is not cosmetic: `fff5` is write-without-response, so back-to-back frames silently
     * drop — the same constraint as the credential reads that follow.
     *
     * [schedule] takes `(delayMs, action)`; on Android that is a `Handler.postDelayed`.
     */
    fun sendBleSetup(
        write: (ByteArray) -> Unit,
        schedule: (Long, () -> Unit) -> Unit,
        log: (String) -> Unit,
    ) {
        SETUP.forEachIndexed { i, f ->
            val bytes = ByteArray(f.payload.length / 2) {
                ((f.payload[it * 2].digitToInt(16) shl 4) or f.payload[it * 2 + 1].digitToInt(16)).toByte()
            }
            schedule(150L + i * 55L) {
                write(OsmoCommands.command(f.set, f.cmd, bytes, target = f.target, id = 0x8100 + i))
            }
        }
        schedule(150L + SETUP.size * 55L) {
            log("drone: sent DJI Fly's BLE setup prelude (${SETUP.size} cmds)")
        }
    }
}
