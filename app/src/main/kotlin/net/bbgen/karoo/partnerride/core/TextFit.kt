package net.bbgen.karoo.partnerride.core

/**
 * Shrinks the data field's font size until its string fits the width of the page slot it was
 * placed in.
 *
 * `ViewConfig.textSize` is the size Karoo uses for a *numeric* field of that grid size: derived
 * from the slot's height, and sized for two or three digits. Our strings are much wider than a
 * number — `150 m ▼` is seven glyphs, `~150 m · 60 s` thirteen — so on a tall slot (a 3-row page,
 * where the row height and therefore `textSize` are large) even the full-width field overflows,
 * and on a half-width slot every state does. A single-line `Text` that overflows is silently
 * ellipsized, which turns the gap into `150 …`: the one number the field exists to show is the
 * part that gets dropped.
 *
 * The fit is computed against a caller-supplied measurement rather than a character count, so it
 * uses the real font, including the arrow and middle-dot glyphs that come from a fallback font.
 * Text width is very nearly linear in font size, so the first step lands on the answer; the extra
 * steps only absorb hinting and rounding.
 */
object TextFit {
    /**
     * Never shrink below this. A quarter-width slot cannot fit `~150 m · 60 s` at any readable
     * size, and there a clipped string the rider can still partly read beats a legible-to-nobody
     * one.
     */
    const val MIN_SP = 10f

    /** Fit to slightly less than the real width: rendering rounds up, measurement does not. */
    private const val SAFETY = 0.98f

    /** Enough for the linear first guess plus a correction; the loop converges downward. */
    private const val MAX_STEPS = 3

    /**
     * The largest size up to [desiredSp] whose rendered width fits [availableWidthPx].
     *
     * [measureWidthPx] returns the width the string would render at a given size in sp. A
     * non-positive [availableWidthPx] means the slot size is unknown — [desiredSp] is then used
     * unchanged, i.e. the pre-fit behavior.
     */
    fun fittedSp(desiredSp: Float, availableWidthPx: Float, measureWidthPx: (Float) -> Float): Float {
        if (desiredSp <= 0f || availableWidthPx <= 0f) return desiredSp
        val target = availableWidthPx * SAFETY
        val floor = MIN_SP.coerceAtMost(desiredSp)
        var size = desiredSp
        repeat(MAX_STEPS) {
            val width = measureWidthPx(size)
            if (width <= target) return size
            val next = (size * target / width).coerceAtLeast(floor)
            // Already at the floor, or the measurement says no smaller size is implied.
            if (next >= size) return next
            size = next
        }
        return size
    }
}
