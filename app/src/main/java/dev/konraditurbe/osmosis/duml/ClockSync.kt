package dev.konraditurbe.osmosis.duml

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * The commands that set a camera's clock to the phone's (MEDIA_PROTOCOL §17). Pure, so the bytes can
 * be pinned against what Mimo was captured sending.
 */
object ClockSync {

    /** One clock command: `0x00/[cmd]` to receiver `(receiverId << 5) | receiverType`. */
    class Command(val cmd: Int, val receiverType: Int, val receiverId: Int, val payload: ByteArray) {
        val receiver: Int get() = (receiverId shl 5) or receiverType
    }

    /**
     * What one sync sends, in order.
     *  - Most bodies: `0x00/0x6A` to the system/RTC subsystem (`0x28`).
     *  - A [dateTime] body (the Pocket 3): `0x00/0x4A` to the gimbal (`0x04`), then to the system
     *    (`0x28`). That is the order Mimo sends them in, and it sends that camera no `0x6A` at all.
     */
    fun commands(dateTime: Boolean, nowMs: Long, tz: TimeZone): List<Command> =
        if (dateTime) dateTimePayload(nowMs, tz).let { listOf(Command(0x4A, 0x04, 0, it), Command(0x4A, 0x08, 1, it)) }
        else listOf(Command(0x6A, 0x08, 1, unixPayload(nowMs, tz)))

    /**
     * `0x00/0x6A`: `01 00` · unix seconds (u64-LE) · UTC offset in minutes (i16-LE) · length-prefixed
     * IANA zone id (`Europe/Madrid`).
     */
    fun unixPayload(nowMs: Long, tz: TimeZone): ByteArray {
        val nowSec = nowMs / 1000L
        val offMin = tz.getOffset(nowMs) / 60000                  // signed minutes east of UTC
        val tzId = tz.id.toByteArray(Charsets.US_ASCII)
        val b = java.io.ByteArrayOutputStream()
        b.write(0x01); b.write(0x00)
        for (k in 0 until 8) b.write(((nowSec ushr (k * 8)) and 0xFF).toInt())
        b.write(offMin and 0xFF); b.write((offMin shr 8) and 0xFF)
        b.write(tzId.size); b.write(tzId)
        return b.toByteArray()
    }

    /**
     * `0x00/0x4A`: year (u16-LE) · month · day · hour · minute · second, all in local time, then the
     * UTC offset in minutes (i16-LE).
     */
    fun dateTimePayload(nowMs: Long, tz: TimeZone): ByteArray {
        // Gregorian by name: Calendar.getInstance follows the locale, and a Thai one counts from 543 BC.
        val c = GregorianCalendar(tz).apply { timeInMillis = nowMs }
        val year = c.get(Calendar.YEAR)
        val offMin = tz.getOffset(nowMs) / 60000
        return byteArrayOf(
            year.toByte(), (year shr 8).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
            offMin.toByte(), (offMin shr 8).toByte(),
        )
    }
}
