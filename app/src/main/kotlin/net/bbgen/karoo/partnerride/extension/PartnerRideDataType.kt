package net.bbgen.karoo.partnerride.extension

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
import net.bbgen.karoo.partnerride.core.DemoFieldFrames
import net.bbgen.karoo.partnerride.core.FieldBackground
import net.bbgen.karoo.partnerride.core.FieldDisplay
import net.bbgen.karoo.partnerride.core.FieldState
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.core.TextFit
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.service.ServiceController

/**
 * The "Partner Gap" ride data field: distance to the partner with an ahead/behind arrow, on a
 * green/yellow/red background. Graphical (RemoteViews via Glance) because a plain numeric data
 * type cannot change its background color. What to show for which link state is decided by
 * [FieldState] in core; this class only renders.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PartnerRideDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            if (config.preview) {
                // Page editor: no live data — render a representative sample.
                val sample = FieldDisplay("42 m ▲", FieldBackground.GREEN, 1f)
                val result = glance.compose(context, DpSize.Unspecified) {
                    GapField(sample, fittedFontSizeSp(context, sample, config))
                }
                emitter.updateView(result.remoteViews)
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
            // full Glance composition and a RemoteViews parcel on every dropped emission.
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
                    if (demo) DemoFieldFrames.displayAt(now) else FieldState.build(state, now)
                }
                .distinctUntilChanged()
                .collect { display ->
                    val fontSizeSp = fittedFontSizeSp(context, display, config)
                    val result = glance.compose(context, DpSize.Unspecified) {
                        GapField(display, fontSizeSp)
                    }
                    emitter.updateView(result.remoteViews)
                }
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

        /**
         * Breathing room on each side of the text. The colored background is full-bleed, so
         * without it a fitted string ends flush against the slot's border.
         */
        internal const val FIELD_PADDING_DP = 4f
    }
}

/**
 * The font size to draw [display] at so it fits the slot's width.
 *
 * `config.textSize` is what Karoo would use for a number in a slot this tall and says nothing
 * about how wide the slot is, so it overflows for everything longer than a couple of digits —
 * see [TextFit]. Measured here rather than in core because only the view layer knows the device's
 * font metrics; the search itself is [TextFit.fittedSp].
 */
private fun fittedFontSizeSp(context: Context, display: FieldDisplay, config: ViewConfig): Float {
    val desiredSp = config.textSize * display.fontScale
    val metrics = context.resources.displayMetrics
    val paddingPx =
        2f * TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            PartnerRideDataType.FIELD_PADDING_DP,
            metrics,
        )
    val paint = TextPaint().apply { typeface = Typeface.DEFAULT_BOLD }
    return TextFit.fittedSp(desiredSp, config.viewSize.first - paddingPx) { sp ->
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        paint.measureText(display.text)
    }
}

private fun FieldBackground.color(): Color = when (this) {
    FieldBackground.GREEN -> Color(0xFF1DB954)
    FieldBackground.YELLOW -> Color(0xFFFFC107)
    FieldBackground.RED -> Color(0xFFE0352B)
    FieldBackground.GRAY -> Color(0xFF4A4A4A)
}

private fun FieldBackground.textColor(): Color = when (this) {
    FieldBackground.GREEN, FieldBackground.YELLOW -> Color.Black
    FieldBackground.RED, FieldBackground.GRAY -> Color.White
}

@Composable
private fun GapField(display: FieldDisplay, fontSizeSp: Float) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(display.background.color())
            .padding(horizontal = PartnerRideDataType.FIELD_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display.text,
            // The size is fitted to the slot, so this only backstops a slot too narrow for
            // TextFit.MIN_SP.
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(display.background.textColor()),
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
