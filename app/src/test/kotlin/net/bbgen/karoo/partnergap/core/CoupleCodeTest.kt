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
    fun `normalization lowercases, trims and joins words with hyphens`() {
        assertEquals("maple-rocket-sunset", CoupleCode.normalize("  Maple ROCKET   sunset "))
        assertEquals("maple-rocket-sunset", CoupleCode.normalize("maple-rocket-sunset"))
        assertEquals("maple-rocket-sunset", CoupleCode.normalize("Maple - Rocket - Sunset"))
        assertEquals("", CoupleCode.normalize("   "))
    }

    @Test
    fun `tag is the first 4 bytes of the sha-256 of the normalized code`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("maple-rocket-sunset".toByteArray(Charsets.UTF_8))
            .copyOf(4)
        assertArrayEquals(expected, CoupleCode.tag(" Maple Rocket SUNSET "))
    }

    @Test
    fun `equivalent formattings produce the same tag`() {
        assertArrayEquals(CoupleCode.tag("maple rocket sunset"), CoupleCode.tag("MAPLE-ROCKET-SUNSET"))
    }

    @Test
    fun `different codes produce different tags`() {
        assertFalse(CoupleCode.tag("maple-rocket-sunset").contentEquals(CoupleCode.tag("maple-rocket-sunrise")))
    }

    @Test
    fun `generate builds a three-word hyphenated code from the list`() {
        val words = listOf("acid", "acorn", "acre", "zoom")
        val code = CoupleCode.generate(words, Random(42))
        val parts = code.split("-")
        assertEquals(3, parts.size)
        assertTrue(parts.all { it in words })
        // Already normalized: hashing it directly must be stable.
        assertArrayEquals(CoupleCode.tag(code), CoupleCode.tag(code.uppercase()))
    }
}
