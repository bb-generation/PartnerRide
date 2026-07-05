package net.bbgen.karoo.partnergap.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ScanModeSetting {
    /** SCAN_MODE_LOW_LATENCY — fastest partner updates (default). */
    PERFORMANCE,

    /** SCAN_MODE_BALANCED — saves battery, can add a few seconds of update latency. */
    BATTERY_SAVER,
}

@Serializable
data class PartnerGapSettings(
    val enabled: Boolean = false,
    val coupleCode: String = "",
    val alertEnabled: Boolean = false,
    val alertThresholdMeters: Int = 100,
    val scanMode: ScanModeSetting = ScanModeSetting.PERFORMANCE,
    // Add fields with defaults only — old persisted JSON must keep decoding.
)

private val Context.dataStore by preferencesDataStore(name = "partnergap_settings")
private val settingsKey = stringPreferencesKey("settings")

// ignoreUnknownKeys: users up/downgrade APKs; stale JSON must never crash a data field.
private val json = Json { ignoreUnknownKeys = true }

suspend fun Context.saveSettings(settings: PartnerGapSettings) {
    dataStore.edit { it[settingsKey] = json.encodeToString(settings) }
}

fun Context.streamSettings(): Flow<PartnerGapSettings> = dataStore.data.map { prefs ->
    prefs[settingsKey]?.let {
        runCatching { json.decodeFromString<PartnerGapSettings>(it) }.getOrNull()
    } ?: PartnerGapSettings()
}

/** The bundled EFF short wordlist (1,296 words), one word per line. */
fun Context.loadWordlist(): List<String> =
    assets.open("eff_short_wordlist.txt").bufferedReader().useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }
