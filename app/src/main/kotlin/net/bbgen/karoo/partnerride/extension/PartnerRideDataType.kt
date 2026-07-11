package net.bbgen.karoo.partnerride.extension

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnerride.core.FieldBackground
import net.bbgen.karoo.partnerride.core.FieldDisplay
import net.bbgen.karoo.partnerride.core.FieldState
import net.bbgen.karoo.partnerride.core.GapRepository
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
                val result = glance.compose(context, DpSize.Unspecified) {
                    GapField(FieldDisplay("42 m ▲", FieldBackground.GREEN, 1f), config)
                }
                emitter.updateView(result.remoteViews)
                awaitCancellation()
            }
            // The field being on screen means the user wants the link up: one of the redundant
            // start triggers (with boot receiver and app open) so no single bind order is
            // load-bearing.
            ServiceController.sync(context.applicationContext, context.streamSettings().first())
            // Re-render on every state change and once per second (staleness ages tick).
            combine(GapRepository.state, secondsTicker()) { state, _ -> state }
                .collect { state ->
                    val display = FieldState.build(state, SystemClock.elapsedRealtime())
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

    companion object {
        const val TYPE_ID = "partner-gap"
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
private fun GapField(display: FieldDisplay, config: ViewConfig) {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(display.background.color()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display.text,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(display.background.textColor()),
                fontSize = (config.textSize * display.fontScale).sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
