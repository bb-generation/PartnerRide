package net.bbgen.karoo.partnerride.core

/**
 * Reconstruction of full GPS timestamps from the 2-byte mod-65536 value in the packet.
 *
 * All timestamps are GPS time from Location.getTime() (satellite-derived UTC) — never
 * System.currentTimeMillis(), whose drift between the two devices would break the matching.
 */
object Timestamps {
    private const val MOD = PacketCodec.TIME_MOD.toInt()
    private const val HALF = MOD / 2

    /**
     * Returns the timestamp congruent to [timeMod] (mod 65536) that is closest to [referenceMs]
     * (the local device's most recent Location.getTime()). Handles wraparound in both directions;
     * unambiguous as long as the two fixes are within ±32.768 s of each other.
     *
     * The offset lands in `[-32768, +32767]`: at exactly half a modulus the two candidates are
     * equidistant and this resolves them as *past*, matching [ReplayGuard], which treats a
     * forward distance of exactly 32768 as not-newer. The two used to disagree at that single
     * value — harmless in practice, but two modules sharing one convention should share it.
     */
    fun reconstruct(timeMod: Int, referenceMs: Long): Long {
        val refMod = referenceMs.mod(PacketCodec.TIME_MOD).toInt()
        var diff = timeMod - refMod
        if (diff >= HALF) diff -= MOD
        if (diff < -HALF) diff += MOD
        return referenceMs + diff
    }

    /**
     * Orders one scan batch chronologically by fix time.
     *
     * [ReplayGuard] only accepts strictly-newer values, so if the controller hands back a batch
     * out of chronological order everything after the first accepted packet is discarded and the
     * gap is computed from a fix up to a whole batch window old.
     *
     * The link scans with no report delay, so this is a safety net for controllers that batch
     * anyway rather than the normal path.
     *
     * A batch spans seconds, far less than the 65.536 s modulus, so the values are contiguous — but
     * they can straddle the wrap. A spread wider than the half-window means they do, and rotating
     * the space by half a modulus moves the discontinuity to the edges so a plain sort is correct.
     */
    fun chronological(packets: List<PartnerPacket>): List<PartnerPacket> {
        if (packets.size < 2) return packets
        val min = packets.minOf { it.timeMod }
        val max = packets.maxOf { it.timeMod }
        if (max - min <= HALF) return packets.sortedBy { it.timeMod }
        return packets.sortedBy { (it.timeMod + HALF) % MOD }
    }
}

/**
 * Stale/replay protection: accepts a packet only if its fix timestamp is strictly newer than the
 * last accepted one, under mod-65536 arithmetic ("newer" = within the forward half-window).
 */
class ReplayGuard {
    private var lastTimeMod = -1

    /**
     * True if [timeMod] is newer than the last accepted value, *without* recording it.
     *
     * Split from [accept] so a caller that may still reject the packet for its own reasons
     * (e.g. being unable to align it to an own fix) does not burn the slot: recording first
     * meant a retransmission that could have been used was then dropped as a replay.
     */
    fun isNewer(timeMod: Int): Boolean {
        if (lastTimeMod < 0) return true
        val forward = (timeMod - lastTimeMod).mod(PacketCodec.TIME_MOD.toInt())
        return forward in 1 until PacketCodec.TIME_MOD.toInt() / 2
    }

    /** Records [timeMod] as the newest accepted fix time. */
    fun accept(timeMod: Int) {
        lastTimeMod = timeMod
    }

    fun acceptIfNewer(timeMod: Int): Boolean = isNewer(timeMod).also { if (it) accept(timeMod) }

    /** Forget history, e.g. after the partner has been out of range long enough for the
     *  mod-65536 comparison to be meaningless. Required so recovery is never blocked by state. */
    fun reset() {
        lastTimeMod = -1
    }
}
