package net.bbgen.karoo.partnergap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class CoupleCodeTest {

    @Test
    fun `normalization strips whitespace and separators`() {
        assertEquals("123456", CoupleCode.normalize(" 123 456 "))
        assertEquals("123456", CoupleCode.normalize("123-456"))
        assertEquals("123456", CoupleCode.normalize("123_456"))
        assertEquals("123456", CoupleCode.normalize("123456"))
        assertEquals("", CoupleCode.normalize("   "))
    }

    @Test
    fun `tag is the first 4 bytes of the sha-256 of the normalized code`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("123456".toByteArray(Charsets.UTF_8))
            .copyOf(4)
        assertArrayEquals(expected, CoupleCode.tag(" 123 456 "))
    }

    @Test
    fun `equivalent formattings produce the same tag`() {
        assertArrayEquals(CoupleCode.tag("123 456"), CoupleCode.tag("123-456"))
    }

    @Test
    fun `different codes produce different tags`() {
        assertFalse(CoupleCode.tag("123456").contentEquals(CoupleCode.tag("123457")))
    }

    @Test
    fun `generate builds a six-digit code`() {
        val code = CoupleCode.generate(Random(42))
        assertEquals(CoupleCode.CODE_LENGTH, code.length)
        assertTrue(code.all { it.isDigit() })
        // Already normalized: hashing it directly must be stable.
        assertArrayEquals(CoupleCode.tag(code), CoupleCode.tag(" $code "))
    }

    @Test
    fun `generate pads leading zeros to keep the length fixed`() {
        // Small values must render as e.g. "000042", never "42".
        repeat(500) { seed ->
            val code = CoupleCode.generate(Random(seed))
            assertEquals(CoupleCode.CODE_LENGTH, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }
}
