package dev.konraditurbe.osmosis.drone

import android.content.SharedPreferences
import dev.konraditurbe.osmosis.duml.DjiPairMessagePayload
import dev.konraditurbe.osmosis.duml.OsmoCommands

/**
 * What differs about pairing a **drone** over BLE, as opposed to an Osmo camera.
 *
 * Four things, all of which were sitting in `MainActivity` next to the camera flow:
 *  - the token: a drone releases WiFi credentials only for **`DJI FLY`** (see `CameraModel`);
 *  - the identity: a persistent per-install UUID ([identifier]);
 *  - the approval: confirmed at the aircraft, by hand ([APPROVAL_MESSAGE]);
 *  - a post-pair setup sequence DJI Fly runs before it will do anything useful ([sendBleSetup]).
 *
 * The GATT plumbing itself is identical, so it stays in `ble/`.
 */
object DronePairing {

    /**
     * The identity sent with `SetPairingPIN` — **this install's own**, minted once and kept.
     *
     * An aircraft remembers the identity it approved and re-pairs it silently; anything else gets the
     * full approval round (LEDs chasing, hold the power button, `0x07/0x46`). Confirmed on a Mavic 3
     * by rotating the string on an already-paired aircraft: a silent `0x45` → `0x01` became
     * `0x45` → `0x02` and a prompt.
     *
     * Hence per-install rather than a shipped constant. Every user then gets one button press on first
     * connect and silence after — which is also how DJI Fly behaves. A constant would instead have
     * every install share one identity: silent only for whoever's aircraft had already approved it,
     * and contending for that single remembered slot for everybody else.
     */
    fun identifier(prefs: SharedPreferences): String =
        prefs.getString(KEY_IDENTITY, null)
            ?: DjiPairMessagePayload.newInstallIdentity().also {
                prefs.edit().putString(KEY_IDENTITY, it).apply()
            }

    private const val KEY_IDENTITY = "drone_app_identity"

    /**
     * A drone is confirmed at the aircraft, not on a screen it doesn't have.
     *
     * The hold is 2 s on most models and 3 s on the newest (DJI's own QuickTransfer setup page splits
     * it that way: Mini 3 Pro / Mini 4 Pro = two seconds, Mini 5 Pro = three), so the prompt gives the
     * range rather than a duration that is wrong on half the line-up.
     *
     * The **Mini 3** is the exception and needs its own line: it has no hold-to-confirm at all — the
     * aircraft must first be put into QuickTransfer mode with *three quick presses* of the power
     * button. Worth surfacing, since a user holding the button on one gets nothing.
     */
    const val APPROVAL_MESSAGE =
        "The drone's lights should be flashing / chasing around now — that means it got the request.\n\n" +
            "Press and hold its power button for 2–3 seconds to confirm the connection.\n\n" +
            "On a Mini 3, instead press the power button three times quickly to enter QuickTransfer mode."

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
