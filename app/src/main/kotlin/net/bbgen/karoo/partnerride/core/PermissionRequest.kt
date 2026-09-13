package net.bbgen.karoo.partnerride.core

/**
 * Orders the runtime permission requests. Background location cannot go out with the rest: Android
 * 11+ ignores a request that asks for it together with foreground location (granting neither), and
 * only offers it once foreground location is already held. So it is always its own, last step.
 */
object PermissionRequest {
    const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

    /** The next batch to request out of [missing]; empty when nothing is missing. */
    fun nextBatch(missing: List<String>): List<String> =
        missing.filter { it != BACKGROUND_LOCATION }.ifEmpty { missing }

    /**
     * What to request straight after a request for [requested] came back, now that [missing] is
     * what is still missing; empty to stop and leave it to the Grant button. Only a granted
     * foreground step chains on into background location. Nothing chains after the background
     * step itself, or backing out of its settings page would just reopen it.
     */
    fun followUp(requested: Collection<String>, missing: List<String>): List<String> =
        if (BACKGROUND_LOCATION in requested) {
            emptyList()
        } else {
            nextBatch(missing).filter { it == BACKGROUND_LOCATION }
        }
}
