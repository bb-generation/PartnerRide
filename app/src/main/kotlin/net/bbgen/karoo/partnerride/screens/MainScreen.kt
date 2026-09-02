package net.bbgen.karoo.partnerride.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnerride.R
import net.bbgen.karoo.partnerride.core.CoupleCode
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.data.PartnerRideSettings
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.data.updateSettings
import net.bbgen.karoo.partnerride.service.ServiceController

@Composable
fun MainScreen(
    missingPermissions: List<String> = emptyList(),
    onRequestPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { context.streamSettings() }.collectAsState(initial = PartnerRideSettings())
    val linkState by GapRepository.state.collectAsState()

    // Text fields own their state locally: driving them from the DataStore-backed `settings`
    // would echo every keystroke back asynchronously and reset the cursor position. They are
    // seeded from the stored values once and only written *to* the store afterwards.
    var codeText by remember { mutableStateOf("") }
    var thresholdText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val stored = context.streamSettings().first()
        codeText = stored.coupleCode
        thresholdText = stored.alertThresholdMeters.toString()
    }

    var debugTaps by remember { mutableIntStateOf(0) }
    var lastDebugTapMs by remember { mutableLongStateOf(0L) }

    // 1 Hz tick so the "x s ago" ages count up while the screen is open.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    fun update(transform: (PartnerRideSettings) -> PartnerRideSettings) {
        scope.launch {
            // Transform inside the DataStore transaction, not against the collected `settings`
            // snapshot: that snapshot lags the store, so two quick edits (generating a code and
            // then flipping a switch) could both start from the same stale value and the second
            // write would silently revert the first.
            val new = context.updateSettings(transform)
            ServiceController.sync(context, new)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Hidden debug-mode gesture: DEBUG_TAP_COUNT taps on the title, each within
        // DEBUG_TAP_WINDOW_MS of the last. No ripple and no indication — riders never need this,
        // and a stray tap during a ride must not accumulate towards it, hence the window.
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val tapNow = SystemClock.elapsedRealtime()
                debugTaps = if (tapNow - lastDebugTapMs > DEBUG_TAP_WINDOW_MS) 1 else debugTaps + 1
                lastDebugTapMs = tapNow
                if (debugTaps >= DEBUG_TAP_COUNT) {
                    debugTaps = 0
                    // Taps only ever switch it on; the section that appears below owns turning it
                    // off, so there is no way to end up in debug mode with no way out.
                    update { s -> s.copy(debugMode = true) }
                }
            },
        )

        // ---------------- enable ----------------
        LabeledSwitch(
            label = stringResource(R.string.setting_enabled),
            checked = settings.enabled,
            onCheckedChange = { update { s -> s.copy(enabled = it) } },
        )

        if (missingPermissions.isNotEmpty()) {
            Text(
                stringResource(R.string.status_permissions_missing),
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRequestPermissions) {
                Text(stringResource(R.string.grant_permissions))
            }
        }

        HorizontalDivider()

        // ---------------- couple code ----------------
        Text(stringResource(R.string.setting_couple_code), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = codeText,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(CoupleCode.CODE_LENGTH)
                codeText = digits
                update { s -> s.copy(coupleCode = digits) }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.couple_code_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        if (!CoupleCode.isValid(codeText)) {
            // Without this the screen happily reports "Broadcasting: yes" on a partial code
            // while the link is deliberately inert, with nothing saying why.
            Text(
                stringResource(R.string.couple_code_incomplete),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = {
            val code = CoupleCode.generate()
            codeText = code
            update { s -> s.copy(coupleCode = code) }
        }) {
            Text(stringResource(R.string.generate_code))
        }
        Text(
            stringResource(R.string.couple_code_help),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        // ---------------- gap alert ----------------
        LabeledSwitch(
            label = stringResource(R.string.setting_alert),
            checked = settings.alertEnabled,
            onCheckedChange = { update { s -> s.copy(alertEnabled = it) } },
        )
        if (settings.alertEnabled) {
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { text ->
                    thresholdText = text.filter { it.isDigit() }
                    thresholdText.toIntOrNull()?.let { threshold ->
                        if (threshold > 0) update { s -> s.copy(alertThresholdMeters = threshold) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_alert_threshold)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }

        HorizontalDivider()

        // ---------------- status ----------------
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
        StatusLine(
            stringResource(R.string.status_broadcasting),
            if (linkState.advertising) stringResource(R.string.yes) else stringResource(R.string.no),
        )
        StatusLine(
            stringResource(R.string.status_partner_signal),
            ageText(linkState.lastPacketElapsedMs, now),
        )
        StatusLine(
            stringResource(R.string.status_own_gps),
            ageText(linkState.lastOwnFixElapsedMs, now),
        )
        linkState.statusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // ---------------- debug ----------------
        // Conditional, so a normal rider never sees it; unconditional once on, so it is always
        // possible to switch back off.
        if (settings.debugMode) {
            HorizontalDivider()
            Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.titleMedium)
            LabeledSwitch(
                label = stringResource(R.string.setting_debug_mode),
                checked = settings.debugMode,
                onCheckedChange = { on -> update { s -> s.copy(debugMode = on) } },
            )
            Text(
                stringResource(R.string.debug_help),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Taps on the title that switch debug mode on, and the gap after which the count restarts. */
private const val DEBUG_TAP_COUNT = 7
private const val DEBUG_TAP_WINDOW_MS = 3_000L

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ageText(elapsedMs: Long?, nowMs: Long): String =
    if (elapsedMs == null) {
        stringResource(R.string.never)
    } else {
        stringResource(R.string.seconds_ago, (nowMs - elapsedMs).coerceAtLeast(0) / 1000)
    }
