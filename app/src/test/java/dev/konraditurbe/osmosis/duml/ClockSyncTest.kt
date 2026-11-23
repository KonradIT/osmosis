package dev.konraditurbe.osmosis.duml

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the clock commands against what Mimo was captured sending (MEDIA_PROTOCOL §17).
 *
 * Two commands exist. Mimo sends an Osmo Nano and an Xtra Edge Pro `0x00/0x6A` to the system
 * subsystem (`0x28`). It sends a Pocket 3 `0x00/0x4A` instead, to the gimbal (`0x04`) and then the
 * system, and no `0x6A` at all:
 *
 *     Nano      55 27 04 15 02 **28** 3a ef 40 00 **6a** 01 00 ac 5e 6f 6a 00 00 00 00 78 00 0d Europe/Madrid
 *     Pocket 3  55 16 04 fc 02 **04** df 97 40 00 **4a** ea 07 08 11 0c 36 0d 78 00
 *     Pocket 3  55 16 04 fc 02 **28** 13 98 40 00 **4a** ea 07 08 11 0c 36 0e 78 00
 */
class ClockSyncTest {

    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- 0x00/0x6A ---------------------------------------------------------------------------------

    /** The worked example in MEDIA_PROTOCOL §17, byte for byte: 2026-07-26 15:42:06 UTC, Madrid at +120. */
    @Test
    fun `unix payload is seconds, offset minutes and the zone id`() {
        assertEquals("0100ce2a666a0000000078000d4575726f70652f4d6164726964",
            hex(ClockSync.unixPayload(1785080526_000L, madrid)))
    }

    @Test
    fun `a zone west of UTC carries a negative offset`() {
        val p = ClockSync.unixPayload(1785080526_000L, TimeZone.getTimeZone("America/New_York"))
        assertEquals("EDT is -240 min, as i16-LE", listOf(0x10, 0xFF), p.slice(10..11).map { it.toInt() and 0xFF })
    }

    // ---- 0x00/0x4A ---------------------------------------------------------------------------------

    /** Mimo's first Pocket 3 frame: 2026-08-17 10:54:13 UTC is 12:54:13 in Madrid, +120 min. */
    @Test
    fun `date-time payload is local calendar fields then the offset`() {
        assertEquals("ea0708110c360d7800", hex(ClockSync.dateTimePayload(1786964053_000L, madrid)))
        assertEquals("one second later, as Mimo's second frame", "ea0708110c360e7800",
            hex(ClockSync.dateTimePayload(1786964054_000L, madrid)))
    }

    /** 2026-01-15 23:30:05 UTC is already the 16th in Madrid, and still the 15th in New York. */
    @Test
    fun `date-time fields follow the zone across midnight and below UTC`() {
        assertEquals("ea070110001e053c00", hex(ClockSync.dateTimePayload(1768519805_000L, madrid)))
        assertEquals("EST is -300 min", "ea07010f121e05d4fe",
            hex(ClockSync.dateTimePayload(1768519805_000L, TimeZone.getTimeZone("America/New_York"))))
    }

    /** A Thai locale's default calendar is Buddhist: the year would go out as 2569. */
    @Test
    fun `the year is Gregorian whatever the phone's locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            assertEquals("ea0708110c360d7800", hex(ClockSync.dateTimePayload(1786964053_000L, madrid)))
        } finally {
            Locale.setDefault(before)
        }
    }

    // ---- which command a body gets -----------------------------------------------------------------

    @Test
    fun `most bodies get one 0x6A to the system subsystem`() {
        val c = ClockSync.commands(dateTime = false, nowMs = 1785080526_000L, tz = madrid).single()
        assertEquals(0x6A, c.cmd)
        assertEquals(0x28, c.receiver)
        assertEquals("0100ce2a666a0000000078000d4575726f70652f4d6164726964", hex(c.payload))
    }

    @Test
    fun `a Pocket 3 gets 0x4A to the gimbal and then the system, and no 0x6A`() {
        val cs = ClockSync.commands(dateTime = true, nowMs = 1786964053_000L, tz = madrid)
        assertEquals(listOf(0x4A, 0x4A), cs.map { it.cmd })
        assertEquals("gimbal first, then system/RTC, as Mimo", listOf(0x04, 0x28), cs.map { it.receiver })
        cs.forEach { assertEquals("ea0708110c360d7800", hex(it.payload)) }
    }
}
