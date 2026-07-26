package net.bbgen.karoo.partnerride.core

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The shared 6-digit code both riders enter. Its normalized SHA-256 prefix is the 4-byte tag that
 * pairs the two broadcasts — a static, plaintext identifier by design (accepted v1 tradeoff).
 */
object CoupleCode {
    const val CODE_LENGTH = 6

    /** Lowercase, separators removed: " 123 456 " -> "123456". */
    fun normalize(raw: String): String = raw.lowercase().replace(SEPARATORS, "")

    /**
     * True only for exactly [CODE_LENGTH] digits after normalization.
     *
     * Nothing about [tag] rejects a short or empty code — SHA-256("") is a perfectly good tag —
     * so without this check two devices that were never configured broadcast and match on the
     * same one and happily pair with a stranger.
     */
    fun isValid(code: String): Boolean {
        val normalized = normalize(code)
        return normalized.length == CODE_LENGTH && normalized.all { it.isDigit() }
    }

    /** First 4 bytes of SHA-256 of the normalized code (UTF-8). */
    fun tag(code: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(normalize(code).toByteArray(Charsets.UTF_8))
        .copyOf(PacketCodec.TAG_SIZE)

    /** Random 6-digit code; leading zeros are kept ("042137" is valid). */
    fun generate(random: Random = SecureRandom().asKotlinRandom()): String =
        random.nextInt(1_000_000).toString().padStart(CODE_LENGTH, '0')

    private val SEPARATORS = Regex("[\\s\\-_]+")
}
