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
     * SetPairingPIN (CmdSet 0x07 / CmdId 0x45). `pin` is the second PackString field; the app
     * identifier is the known-accepted 32-hex blob from [DjiPairMessagePayload]. Camera replies
     * on 0x07/0x45 with payload 0x0001 (already paired) or 0x0002 (approval required on screen).
     */
    fun setPairingPin(pin: String, id: Int = PAIR_MSG_ID): ByteArray {
        val payload = DjiPairMessagePayload(pin).encode()
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

    /**
     * App device-info blob ("APP" identity), mirroring osmo-download's 0x00/0x81 payload. Used to
     * answer the camera's inbound 0x00/0x81 device-info exchange so it accepts us as a live app.
     */
    val APP_DEVICE_INFO: ByteArray =
        byteArrayOf(0x00, 0x41, 0x50, 0x50) + ByteArray(37) +
            byteArrayOf(0x02) + ByteArray(8) + byteArrayOf(0x02, 0x08) + ByteArray(10)
}
