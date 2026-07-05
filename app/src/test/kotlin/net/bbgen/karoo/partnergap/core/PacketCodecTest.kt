package net.bbgen.karoo.partnergap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketCodecTest {
    private val tag = CoupleCode.tag("maple-rocket-sunset")
    private val otherTag = CoupleCode.tag("cedar-comet-harbor")

    @Test
    fun `encode produces 17 bytes`() {
        val bytes = PacketCodec.encode(tag, 1_234_567_890_123L, 47.0708333, 15.4382777)
        assertEquals(PacketCodec.PACKET_SIZE, bytes.size)
    }

    @Test
    fun `roundtrip preserves time mod and coordinates`() {
        val gpsTime = 1_234_567_890_123L
        val lat = 47.0708333
        val lon = 15.4382777
        val decoded = PacketCodec.decode(PacketCodec.encode(tag, gpsTime, lat, lon), tag)

        assertNotNull(decoded)
        assertEquals((gpsTime % 65536L).toInt(), decoded!!.timeMod)
        assertEquals(lat, decoded.latDeg, 1e-7)
        assertEquals(lon, decoded.lonDeg, 1e-7)
    }

    @Test
    fun `roundtrip preserves negative coordinates`() {
        val decoded = PacketCodec.decode(PacketCodec.encode(tag, 42L, -33.8688197, -151.2092955), tag)
        assertNotNull(decoded)
        assertEquals(-33.8688197, decoded!!.latDeg, 1e-7)
        assertEquals(-151.2092955, decoded.lonDeg, 1e-7)
    }

    @Test
    fun `time mod is unsigned at the 16-bit boundary`() {
        val decoded = PacketCodec.decode(PacketCodec.encode(tag, 65_535L, 0.0, 0.0), tag)
        assertEquals(65_535, decoded!!.timeMod)
    }

    @Test
    fun `matching couple code tag is accepted`() {
        assertNotNull(PacketCodec.decode(PacketCodec.encode(tag, 1L, 1.0, 2.0), tag))
    }

    @Test
    fun `non-matching couple code tag is rejected`() {
        assertNull(PacketCodec.decode(PacketCodec.encode(otherTag, 1L, 1.0, 2.0), tag))
    }

    @Test
    fun `unknown version is rejected`() {
        val bytes = PacketCodec.encode(tag, 1L, 1.0, 2.0)
        bytes[0] = 2
        assertNull(PacketCodec.decode(bytes, tag))
    }

    @Test
    fun `wrong magic is rejected`() {
        val bytes = PacketCodec.encode(tag, 1L, 1.0, 2.0)
        bytes[1] = 0x00
        assertNull(PacketCodec.decode(bytes, tag))
    }

    @Test
    fun `wrong length and null are rejected`() {
        val bytes = PacketCodec.encode(tag, 1L, 1.0, 2.0)
        assertNull(PacketCodec.decode(bytes.copyOf(16), tag))
        assertNull(PacketCodec.decode(bytes + 0x00, tag))
        assertNull(PacketCodec.decode(null, tag))
        assertNull(PacketCodec.decode(ByteArray(0), tag))
    }
}
