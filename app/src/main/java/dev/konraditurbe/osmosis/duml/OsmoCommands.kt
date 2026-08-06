package dev.konraditurbe.osmosis.duml

/** Builders for ready-to-write DUML command frames (BLE fff5). */
object OsmoCommands {
    const val TARGET_APP_TO_WIFI = 0x0702       // sender App(0x02) -> receiver WiFi(0x07)

    // 24-bit LE `type` = flags | (cmdSet<<8) | (cmdId<<16).
    const val TYPE_SET_PAIRING_PIN = 0x450740   // flags 0x40, set 0x07, cmd 0x45
    const val TYPE_CONNECT_WIFI = 0x470740      // flags 0x40, set 0x07, cmd 0x47

    const val PAIR_MSG_ID = 0x8092
    const val WIFI_MSG_ID = 0x8C19

    /**
     * SetPairingPIN (CmdSet 0x07 / CmdId 0x45). `pin` is the second PackString field; `identifier` is
     * the app identity, and the field the device keys its remembered approval on.
     *
     * Reply on 0x07/0x45 is `[00][status]`:
     *   - `0x01` already paired — this (identity, token) pair was approved before; proceed silently.
     *   - `0x02` approval required — confirm at the device. A camera shows a prompt on its screen; a
     *     drone flashes its LEDs and waits for a ~2 s power-button hold.
     *
     * The approval itself then arrives as **0x07/0x46, a request (flags 0x40) rather than a response**,
     * so it has to be answered like any other request or the device drops the link.
     */
    fun setPairingPin(
        pin: String,
        id: Int = PAIR_MSG_ID,
        identifier: String = DjiPairMessagePayload.DEFAULT_IDENTIFIER,
    ): ByteArray {
        val payload = DjiPairMessagePayload(pin, identifier).encode()
        return DjiMessage(TARGET_APP_TO_WIFI, id, TYPE_SET_PAIRING_PIN, payload).encode()
    }

    /**
     * ConnectToWiFi (CmdSet 0x07 / CmdId 0x47). On the 360 this activates the camera's own AP
     * when passed its own SSID+password; we use it to wake the Nano's (idle) AP before joining.
     * Payload = PackString(ssid) + PackString(password). Camera replies 0x07/0x47 (0x0000 = ok).
     */
    fun connectWifi(ssid: String, password: String, id: Int = WIFI_MSG_ID): ByteArray {
        val payload = djiPackString(ssid) + djiPackString(password)
        return DjiMessage(TARGET_APP_TO_WIFI, id, TYPE_CONNECT_WIFI, payload).encode()
    }

    /** Generic request to the WiFi subsystem (CmdSet 0x07) with an arbitrary cmdId + payload. */
    fun wifiQuery(cmdId: Int, payload: ByteArray = ByteArray(0), id: Int = 0x8000): ByteArray {
        val type = 0x40 or (0x07 shl 8) or (cmdId shl 16)
        return DjiMessage(TARGET_APP_TO_WIFI, id, type, payload).encode()
    }

    // ---- Mimo's BLE session sequence (from an HCI snoop of Mimo waking a sleeping Nano) -------
    //
    // Captured order, once notifications are armed on fff4:
    //   0x00/0x2b `04 00`   <- FIRST, *before* pairing
    //   0x07/0x45 pair
    //   0x53/0x10 `00 00 00 00`   <- the camera answers 01 00 00 00 and wakes
    //   0x00/0x2b `01 01`   <- then repeating, ~0.5-1 s, as the session keepalive
    //   0x07/0x07 / 0x0e / 0x0c  (SSID / password / MAC)
    // Mimo also sends 0x07/0x39 in here, but the camera rejects it (`e0`) for Mimo as well, so it's
    // not replicated. And Mimo NEVER sends 0x07/0x47 (ConnectToWiFi) — the command we used to "wake
    // the AP", after which a sleeping camera drops us (GATT status=19).

    const val TARGET_APP_TO_CAMERA = 0x0102     // App(0x02) -> Camera(0x01)
    const val TARGET_APP_TO_DM368 = 0x0802      // App(0x02) -> DM368(0x08)

    // These two do NOT go to the camera. The receiver byte is `(id << 5) | type`, and an HCI diff of
    // Mimo vs us showed we were addressing both to Camera(0x01) — the camera answered `e0` (reject)
    // every time, which is why the wake never happened.
    //   0x00/0x2b -> 0xF0 = type 0x10, id 7    (matches `rcv=16` seen on the UDP datalink)
    //   0x53/0x10 -> 0x1C = type 0x1C, id 0    (matches `rcv_type=28` seen for 0x53/0x15)
    const val TARGET_APP_TO_SESSION = 0xF002    // App(0x02) -> type 0x10 / id 7
    const val TARGET_APP_TO_1C = 0x1C02         // App(0x02) -> type 0x1C / id 0

    /** Generic command to an arbitrary cmdset/cmdId + target, mirroring [wifiQuery]'s framing. */
    fun command(
        cmdSet: Int, cmdId: Int, payload: ByteArray = ByteArray(0),
        target: Int = TARGET_APP_TO_CAMERA, id: Int = 0x8000,
    ): ByteArray {
        val type = 0x40 or (cmdSet shl 8) or (cmdId shl 16)
        return DjiMessage(target, id, type, payload).encode()
    }

    /**
     * `0x00/0x2b` — the session/heartbeat frame Mimo opens with and then repeats. Payload `04 00`
     * is what it sends first (before pairing) on a sleeping camera; `01 01` is the repeating form.
     */
    fun sessionPing(payload: ByteArray, id: Int = 0x802B): ByteArray =
        command(0x00, 0x2B, payload, target = TARGET_APP_TO_SESSION, id = id)

    val SESSION_WAKE: ByteArray = byteArrayOf(0x04, 0x00)
    val SESSION_KEEPALIVE: ByteArray = byteArrayOf(0x01, 0x01)

    /**
     * `0x53/0x10` `00 00 00 00` -> type 0x1C. The camera answers `01 00 00 00` for Mimo; addressed
     * to the camera instead it answers `e0` (reject). This is the command that actually correlates
     * with the camera waking.
     */
    fun session5310(id: Int = 0x8053): ByteArray =
        command(0x53, 0x10, byteArrayOf(0, 0, 0, 0), target = TARGET_APP_TO_1C, id = id)


    /**
     * App device-info blob ("APP" identity), mirroring osmo-download's 0x00/0x81 payload. Used to
     * answer the camera's inbound 0x00/0x81 device-info exchange so it accepts us as a live app.
     */
    val APP_DEVICE_INFO: ByteArray =
        byteArrayOf(0x00, 0x41, 0x50, 0x50) + ByteArray(37) +
            byteArrayOf(0x02) + ByteArray(8) + byteArrayOf(0x02, 0x08) + ByteArray(10)
}
