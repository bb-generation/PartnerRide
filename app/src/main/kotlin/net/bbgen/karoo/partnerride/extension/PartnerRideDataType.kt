package net.bbgen.karoo.partnerride.extension

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnerride.R
import net.bbgen.karoo.partnerride.core.DemoFieldFrames
import net.bbgen.karoo.partnerride.core.FieldBackground
import net.bbgen.karoo.partnerride.core.FieldDisplay
import net.bbgen.karoo.partnerride.core.FieldState
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.service.ServiceController

/**
 * The "Partner Gap" ride data field: distance to the partner with an ahead/behind arrow, on a
 * green/yellow/red background. Graphical (a RemoteViews layout) because a plain numeric data
 * type cannot change its background color. What to show for which link state is decided by
 * [FieldState] in core; this class only renders.
 *
 * The view is `res/layout/partner_gap_field.xml`, a single autosizing `TextView`: it measures the
 * string against the slot it was actually given and picks the largest size that fits. That is the
 * only reliable way to size this text — see the layout's comment and TECHNICAL.md §7.1.
 */
class PartnerRideDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            if (config.preview) {
                // Page editor: no live data — render a representative sample.
                emitter.updateView(
                    gapFieldViews(context, FieldDisplay("42 m ▲", FieldBackground.GREEN)),
                )
                awaitCancellation()
            }
            // The field being on screen means the user wants the link up: one of the redundant
            // start triggers (with boot receiver and app open) so no single bind order is
            // load-bearing.
            ServiceController.sync(context.applicationContext, context.streamSettings().first())
            // Re-render on every state change and once per second (staleness ages tick), but
            // pace it ourselves: karoo-ext drops any updateView within ~900 ms of the previous
            // one, so emitting faster meant a fresh packet landing just after a tick was
            // silently discarded and the field kept the old value — while still paying for a
            // full RemoteViews parcel on every dropped emission.
            //
            // throttle() conflates, so the value that survives the window is always the latest,
            // and because our emissions are >= 1 s apart none of them can now be dropped. Only
            // then is distinctUntilChanged safe: a filtered value is one the field is already
            // showing, not one that got thrown away.
            //
            // Demo mode replaces the live state with a synthetic frame per FRAME_MS; the 1 s tick
            // below samples each frame twice, so every frame gets its full 2 s on screen.
            val demoMode = context.streamSettings().map { it.demoMode }.distinctUntilChanged()
            combine(GapRepository.state, secondsTicker(), demoMode) { state, _, demo ->
                state to demo
            }
                .throttle(VIEW_UPDATE_INTERVAL_MS)
                .map { (state, demo) ->
                    val now = SystemClock.elapsedRealtime()
                    if (demo) {
                        DemoFieldFrames.displayAt(now, config.report())
                    } else {
                        FieldState.build(state, now)
                    }
                }
                .distinctUntilChanged()
                .collect { display -> emitter.updateView(gapFieldViews(context, display)) }
        }
        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }

    private fun secondsTicker() = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    companion object {
        const val TYPE_ID = "partner-gap"

        /** karoo-ext drops any updateView within ~900 ms of the previous one; stay outside that. */
        private const val VIEW_UPDATE_INTERVAL_MS = 1_000L
    }
}

/**
 * What Karoo says about this slot, for demo mode's report frame: grid span, pixel size, numeric
 * font size, and whether the page draws field boundaries. The field's only window onto those
 * numbers, and the record of what the corner radius and the text fit are up against.
 */
private fun ViewConfig.report(): String =
    "${gridSize.first}x${gridSize.second} ${viewSize.first}x${viewSize.second} " +
        "t$textSize b${if (boundariesEnabled) 1 else 0}"

/**
 * The zone background: a rounded rectangle rather than a color, because Karoo draws its field
 * boundary *over* the graphic. A flat color fills the corners the boundary leaves open, which on
 * a map page shows as squared-off blocks of color with a rounded outline drawn inside them.
 *
 * A drawable per color because the size cannot be tinted from here: `setBackgroundTintList` over
 * RemoteViews needs API 31 and Karoo 2 is API 26.
 */
private fun FieldBackground.backgroundRes(): Int = when (this) {
    FieldBackground.GREEN -> R.drawable.field_bg_green
    FieldBackground.YELLOW -> R.drawable.field_bg_yellow
    FieldBackground.RED -> R.drawable.field_bg_red
    FieldBackground.GRAY -> R.drawable.field_bg_gray
}

/** Plain ARGB ints: this view is RemoteViews, not Compose. */
private fun FieldBackground.textColor(): Int = when (this) {
    FieldBackground.GREEN, FieldBackground.YELLOW -> 0xFF000000.toInt()
    FieldBackground.RED, FieldBackground.GRAY -> 0xFFFFFFFF.toInt()
}

/**
 * The field as RemoteViews. Only the text, its color and the background shape are set — the text
 * *size* is the layout's job, and setting it here would be ignored anyway while autosizing is on.
 */
private fun gapFieldViews(context: Context, display: FieldDisplay): RemoteViews =
    RemoteViews(context.packageName, R.layout.partner_gap_field).apply {
        setTextViewText(R.id.partner_gap_text, display.text)
        setTextColor(R.id.partner_gap_text, display.background.textColor())
        setInt(R.id.partner_gap_text, "setBackgroundResource", display.background.backgroundRes())
    }
