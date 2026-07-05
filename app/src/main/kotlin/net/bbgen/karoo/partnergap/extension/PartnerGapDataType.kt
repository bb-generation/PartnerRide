package net.bbgen.karoo.partnergap.extension

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnergap.core.GapZone
import net.bbgen.karoo.partnergap.core.PartnerGapState
import net.bbgen.karoo.partnergap.core.roundGapForDisplay
import android.os.SystemClock
import kotlinx.coroutines.delay
import net.bbgen.karoo.partnergap.core.GapRepository

/**
 * The "Partner Gap" ride data field: distance to the partner with an ahead/behind arrow, on a
 * green/yellow/red background. Graphical (RemoteViews via Glance) because a plain numeric data
 * type cannot change its background color.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PartnerGapDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            if (config.preview) {
                // Page editor: no live data — render a representative sample.
                val result = glance.compose(context, DpSize.Unspecified) {
                    GapField(Display("42 m ▲", Color(GREEN_BG), Color.Black, 1f), config)
                }
                emitter.updateView(result.remoteViews)
                awaitCancellation()
            }
            // Re-render on every state change and once per second (staleness ages tick).
            combine(GapRepository.state, secondsTicker()) { state, _ -> state }
                .collect { state ->
                    val display = buildDisplay(state, SystemClock.elapsedRealtime())
                    val result = glance.compose(context, DpSize.Unspecified) { GapField(display, config) }
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

    internal data class Display(
        val text: String,
        val background: Color,
        val textColor: Color,
        /** Relative font scale (stale/lost states use longer, smaller text). */
        val fontScale: Float,
    )

    companion object {
        const val TYPE_ID = "partner-gap"

        private const val GREEN_BG = 0xFF1DB954
        private const val YELLOW_BG = 0xFFFFC107
        private const val RED_BG = 0xFFE0352B

        private const val FRESH_MS = 5_000L
        /** After [FRESH_MS] show the last known value with its age for another 30 s, then "—". */
        private const val LAST_KNOWN_MS = FRESH_MS + 30_000L
        private const val OWN_FIX_STALE_MS = 10_000L

        internal fun buildDisplay(state: PartnerGapState, nowElapsedMs: Long): Display {
            val red = Color(RED_BG)
            val linkBroken = !state.serviceRunning ||
                !state.bluetoothReady ||
                state.missingPermissions.isNotEmpty() ||
                state.lastOwnFixElapsedMs == null ||
                nowElapsedMs - state.lastOwnFixElapsedMs > OWN_FIX_STALE_MS
            val packetAge = state.lastPacketElapsedMs?.let { nowElapsedMs - it }
            val gap = state.smoothedGapMeters

            if (linkBroken || packetAge == null || gap == null || packetAge > LAST_KNOWN_MS) {
                return Display("—", red, Color.White, 1f)
            }
            val arrow = if (state.partnerAhead) "▲" else "▼"
            val meters = roundGapForDisplay(gap)
            return if (packetAge <= FRESH_MS) {
                val background = when (state.zone) {
                    GapZone.GREEN -> Color(GREEN_BG)
                    GapZone.YELLOW -> Color(YELLOW_BG)
                    GapZone.RED -> red
                }
                val textColor = if (state.zone == GapZone.RED) Color.White else Color.Black
                Display("$meters m $arrow", background, textColor, 1f)
            } else {
                // Signal lost: last known value with age, red until it expires.
                Display("~$meters m · ${packetAge / 1000} s", red, Color.White, 0.62f)
            }
        }
    }
}

@Composable
private fun GapField(display: PartnerGapDataType.Display, config: ViewConfig) {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(display.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display.text,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(display.textColor),
                fontSize = (config.textSize * display.fontScale).sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
