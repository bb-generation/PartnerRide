package net.bbgen.karoo.partnergap.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import net.bbgen.karoo.partnergap.R
import net.bbgen.karoo.partnergap.core.CoupleCode
import net.bbgen.karoo.partnergap.core.GapRepository
import net.bbgen.karoo.partnergap.data.PartnerGapSettings
import net.bbgen.karoo.partnergap.data.ScanModeSetting
import net.bbgen.karoo.partnergap.data.saveSettings
import net.bbgen.karoo.partnergap.data.streamSettings
import net.bbgen.karoo.partnergap.service.ServiceController

@Composable
fun MainScreen(
    missingPermissions: List<String> = emptyList(),
    onRequestPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { context.streamSettings() }.collectAsState(initial = PartnerGapSettings())
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

    // 1 Hz tick so the "x s ago" ages count up while the screen is open.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    fun update(transform: (PartnerGapSettings) -> PartnerGapSettings) {
        scope.launch {
            val new = transform(settings)
            context.saveSettings(new)
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
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)

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

        // ---------------- scan mode ----------------
        Text(stringResource(R.string.setting_scan_mode), style = MaterialTheme.typography.titleMedium)
        ScanModeOption(
            label = stringResource(R.string.scan_mode_performance),
            selected = settings.scanMode == ScanModeSetting.PERFORMANCE,
        ) { update { s -> s.copy(scanMode = ScanModeSetting.PERFORMANCE) } }
        ScanModeOption(
            label = stringResource(R.string.scan_mode_battery),
            selected = settings.scanMode == ScanModeSetting.BATTERY_SAVER,
        ) { update { s -> s.copy(scanMode = ScanModeSetting.BATTERY_SAVER) } }
        Text(
            stringResource(R.string.scan_mode_battery_note),
            style = MaterialTheme.typography.bodySmall,
        )

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
    }
}

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
private fun ScanModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label)
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
