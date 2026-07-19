package net.bbgen.karoo.partnerride.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PartnerRideSettings(
    val enabled: Boolean = false,
    val coupleCode: String = "",
    val alertEnabled: Boolean = false,
    val alertThresholdMeters: Int = 100,
    // Add fields with defaults only — old persisted JSON must keep decoding. Removed fields are
    // fine too: ignoreUnknownKeys below means old JSON with a since-removed key (e.g. the former
    // "scanMode") just has that key ignored on decode.
)

private val Context.dataStore by preferencesDataStore(name = "partnerride_settings")
private val settingsKey = stringPreferencesKey("settings")

// ignoreUnknownKeys: users up/downgrade APKs; stale JSON must never crash a data field.
private val json = Json { ignoreUnknownKeys = true }

suspend fun Context.saveSettings(settings: PartnerRideSettings) {
    dataStore.edit { it[settingsKey] = json.encodeToString(settings) }
}

fun Context.streamSettings(): Flow<PartnerRideSettings> = dataStore.data.map { prefs ->
    prefs[settingsKey]?.let {
        runCatching { json.decodeFromString<PartnerRideSettings>(it) }.getOrNull()
    } ?: PartnerRideSettings()
}
