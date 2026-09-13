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
    val coupleCode: String = "",
    val alertEnabled: Boolean = false,
    val alertThresholdMeters: Int = 100,
    /**
     * Makes the data field cycle through every display state instead of showing the real gap, so
     * text that is too wide for a ride-page slot can be found on one device. Reachable only via
     * the hidden 7-tap gesture on the settings screen title.
     */
    val demoMode: Boolean = false,
    // Add fields with defaults only — old persisted JSON must keep decoding. Removed fields are
    // fine too: ignoreUnknownKeys below means old JSON with a since-removed key (e.g. the former
    // "scanMode", or "enabled" from before the link became tap-to-start) just has that key
    // ignored on decode.
)

private val Context.dataStore by preferencesDataStore(name = "partnerride_settings")
private val settingsKey = stringPreferencesKey("settings")

// ignoreUnknownKeys: users up/downgrade APKs; stale JSON must never crash a data field.
private val json = Json { ignoreUnknownKeys = true }

private fun decodeSettings(raw: String?): PartnerRideSettings =
    raw?.let { runCatching { json.decodeFromString<PartnerRideSettings>(it) }.getOrNull() }
        ?: PartnerRideSettings()

/**
 * Read-modify-write *inside* the DataStore transaction, returning the stored result.
 *
 * The settings are one serialized blob, so transforming a separately-collected snapshot and
 * writing it back loses concurrent edits: two updates started from the same snapshot silently
 * drop the first one. DataStore serializes `edit` blocks, so doing the decode, the transform and
 * the encode in here makes each update atomic against the others.
 */
suspend fun Context.updateSettings(
    transform: (PartnerRideSettings) -> PartnerRideSettings,
): PartnerRideSettings {
    lateinit var updated: PartnerRideSettings
    dataStore.edit { prefs ->
        updated = transform(decodeSettings(prefs[settingsKey]))
        prefs[settingsKey] = json.encodeToString(updated)
    }
    return updated
}

fun Context.streamSettings(): Flow<PartnerRideSettings> = dataStore.data.map { prefs ->
    decodeSettings(prefs[settingsKey])
}
