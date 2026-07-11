package net.bbgen.karoo.partnerride.core

import java.nio.ByteBuffer

/**
 * A decoded position broadcast from the partner device.
 *
 * @property timeMod GPS fix time in milliseconds mod 65536 ([PacketCodec.TIME_MOD]).
 * @property speedMps speed at the fix, null when the sender marked it invalid (0xFF sentinel).
 * @property headingDeg heading at the fix, null when the sender marked it invalid (0xFF sentinel).
 */
data class PartnerPacket(
    val timeMod: Int,
    val latDeg: Double,
    val lonDeg: Double,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
)

/**
 * Encoder/decoder for the 19-byte BLE manufacturer-data payload (format version 1, plaintext).
 *
 * Layout (big-endian):
 * ```
 * [0]      version          (1)
 * [1..2]   magic            "PG"
 * [3..6]   couple code tag  first 4 bytes of SHA-256 of the normalized couple code
 * [7..8]   GPS fix time     Location.getTime() mod 65536, unsigned
 * [9..12]  latitude         int32, degrees * 1e7
 * [13..16] longitude        int32, degrees * 1e7
 * [17]     speed            m/s * 4 (0..63.5 m/s), 0xFF = invalid/unknown
 * [18]     heading          degrees / 2 (0..358), 0xFF = invalid/unknown
 * ```
 *
 * The version byte leaves the door open for an encrypted version-2 format: receivers silently
 * discard any version they don't understand.
 */
object PacketCodec {
    /** Bluetooth SIG test manufacturer ID used for the advertisement. */
    const val MANUFACTURER_ID = 0xFFFF

    const val VERSION: Byte = 1
    const val PACKET_SIZE = 19
    const val TIME_MOD = 65536L
    const val TAG_SIZE = 4

    /** Sentinel for "speed/heading invalid or unknown" in bytes 17/18. */
    private const val INVALID_SENTINEL = 0xFF

    private const val MAGIC_0: Byte = 0x50 // 'P'
    private const val MAGIC_1: Byte = 0x47 // 'G'

    fun encode(
        coupleTag: ByteArray,
        gpsTimeMs: Long,
        latDeg: Double,
        lonDeg: Double,
        speedMps: Double? = null,
        headingDeg: Double? = null,
    ): ByteArray {
        require(coupleTag.size == TAG_SIZE) { "couple tag must be $TAG_SIZE bytes" }
        return ByteBuffer.allocate(PACKET_SIZE).apply {
            put(VERSION)
            put(MAGIC_0)
            put(MAGIC_1)
            put(coupleTag)
            putShort((gpsTimeMs.mod(TIME_MOD)).toInt().toShort())
            putInt(Math.round(latDeg * 1e7).toInt())
            putInt(Math.round(lonDeg * 1e7).toInt())
            put(encodeSpeed(speedMps).toByte())
            put(encodeHeading(headingDeg).toByte())
        }.array()
    }

    /**
     * Decodes and validates a payload. Returns null (silent discard) unless the size, version,
     * magic constant, and couple code tag all match.
     */
    fun decode(data: ByteArray?, expectedTag: ByteArray): PartnerPacket? {
        if (data == null || data.size != PACKET_SIZE) return null
        val buf = ByteBuffer.wrap(data)
        if (buf.get() != VERSION) return null
        if (buf.get() != MAGIC_0 || buf.get() != MAGIC_1) return null
        val tag = ByteArray(TAG_SIZE).also { buf.get(it) }
        if (!tag.contentEquals(expectedTag)) return null
        val timeMod = buf.short.toInt() and 0xFFFF
        val lat = buf.int / 1e7
        val lon = buf.int / 1e7
        val speedRaw = buf.get().toInt() and 0xFF
        val headingRaw = buf.get().toInt() and 0xFF
        return PartnerPacket(
            timeMod = timeMod,
            latDeg = lat,
            lonDeg = lon,
            speedMps = if (speedRaw == INVALID_SENTINEL) null else speedRaw / 4.0,
            headingDeg = if (headingRaw == INVALID_SENTINEL) null else (headingRaw * 2.0) % 360.0,
        )
    }

    private fun encodeSpeed(speedMps: Double?): Int {
        if (speedMps == null || speedMps < 0) return INVALID_SENTINEL
        // 0xFF is the sentinel, so the highest encodable value is 254 (= 63.5 m/s).
        return Math.round(speedMps * 4.0).toInt().coerceIn(0, INVALID_SENTINEL - 1)
    }

    private fun encodeHeading(headingDeg: Double?): Int {
        if (headingDeg == null) return INVALID_SENTINEL
        // Round on the half-degree grid, wrapping 360 back to 0 (values 0..179).
        return Math.round(headingDeg.mod(360.0) / 2.0).toInt() % 180
    }
}
