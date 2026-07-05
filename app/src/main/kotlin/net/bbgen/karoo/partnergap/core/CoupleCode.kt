package net.bbgen.karoo.partnergap.core

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The shared word code both riders enter. Its normalized SHA-256 prefix is the 4-byte tag that
 * pairs the two broadcasts — a static, plaintext identifier by design (accepted v1 tradeoff).
 */
object CoupleCode {
    const val WORD_COUNT = 3

    /** Lowercase, trimmed, words joined with "-": " Maple ROCKET sunset " -> "maple-rocket-sunset". */
    fun normalize(raw: String): String = raw
        .trim()
        .lowercase()
        .split(WORD_SEPARATORS)
        .filter { it.isNotEmpty() }
        .joinToString("-")

    /** First 4 bytes of SHA-256 of the normalized code (UTF-8). */
    fun tag(code: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(normalize(code).toByteArray(Charsets.UTF_8))
        .copyOf(PacketCodec.TAG_SIZE)

    fun generate(words: List<String>, random: Random = SecureRandom().asKotlinRandom()): String {
        require(words.isNotEmpty()) { "word list is empty" }
        return List(WORD_COUNT) { words.random(random) }.joinToString("-")
    }

    private val WORD_SEPARATORS = Regex("[\\s\\-_]+")
}
