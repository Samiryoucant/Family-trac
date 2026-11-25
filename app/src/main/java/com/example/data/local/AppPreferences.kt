package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "familytrack_prefs")

data class LocalAppSettings(
    val deviceId: String = "",
    val language: String = "en",
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val selectedRole: String = "",
    val activeFamilyId: String = "",
    val activeAdminUid: String = "",
    val consentGranted: Boolean = false,
    val locationSharingEnabled: Boolean = false,
    val lastSyncMs: Long = 0L,
    val turnServerUrl: String = "",
    val turnUsername: String = "",
    val turnCredential: String = ""
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val SELECTED_ROLE = stringPreferencesKey("selected_role")
        val ACTIVE_FAMILY_ID = stringPreferencesKey("active_family_id")
        val ACTIVE_ADMIN_UID = stringPreferencesKey("active_admin_uid")
        val CONSENT_GRANTED = booleanPreferencesKey("consent_granted")
        val LOCATION_SHARING_ENABLED = booleanPreferencesKey("location_sharing_enabled")
        val LAST_SYNC_MS = longPreferencesKey("last_sync_ms")
        val TURN_URL = stringPreferencesKey("turn_url")
        val TURN_USERNAME = stringPreferencesKey("turn_username")
        val TURN_CREDENTIAL = stringPreferencesKey("turn_credential")
    }

    val settingsFlow: Flow<LocalAppSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME] ?: ThemePreference.SYSTEM.name
        val theme = ThemePreference.entries.firstOrNull { it.name == themeStr } ?: ThemePreference.SYSTEM
        LocalAppSettings(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            language = prefs[Keys.LANGUAGE] ?: "en",
            themePreference = theme,
            selectedRole = prefs[Keys.SELECTED_ROLE] ?: "",
            activeFamilyId = prefs[Keys.ACTIVE_FAMILY_ID] ?: "",
            activeAdminUid = prefs[Keys.ACTIVE_ADMIN_UID] ?: "",
            consentGranted = prefs[Keys.CONSENT_GRANTED] ?: false,
            locationSharingEnabled = prefs[Keys.LOCATION_SHARING_ENABLED] ?: false,
            lastSyncMs = prefs[Keys.LAST_SYNC_MS] ?: 0L,
            turnServerUrl = prefs[Keys.TURN_URL] ?: "",
            turnUsername = prefs[Keys.TURN_USERNAME] ?: "",
            turnCredential = prefs[Keys.TURN_CREDENTIAL] ?: ""
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val newId = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        context.dataStore.edit { it[Keys.DEVICE_ID] = newId }
        return newId
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setSessionPairing(
        role: String,
        familyId: String,
        adminUid: String
    ) {
        context.dataStore.edit {
            it[Keys.SELECTED_ROLE] = role
            it[Keys.ACTIVE_FAMILY_ID] = familyId
            it[Keys.ACTIVE_ADMIN_UID] = adminUid
        }
    }

    suspend fun setConsentAndSharing(consentGranted: Boolean, sharingEnabled: Boolean) {
        context.dataStore.edit {
            it[Keys.CONSENT_GRANTED] = consentGranted
            it[Keys.LOCATION_SHARING_ENABLED] = sharingEnabled
        }
    }

    suspend fun setLocationSharingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOCATION_SHARING_ENABLED] = enabled }
    }

    suspend fun setLastSyncMs(timeMs: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_MS] = timeMs }
    }

    suspend fun setTurnConfig(url: String, username: String, credential: String) {
        context.dataStore.edit {
            it[Keys.TURN_URL] = url.trim()
            it[Keys.TURN_USERNAME] = username.trim()
            it[Keys.TURN_CREDENTIAL] = credential.trim()
        }
    }

    suspend fun clearPairingOnSignOut() {
        context.dataStore.edit {
            it.remove(Keys.SELECTED_ROLE)
            it.remove(Keys.ACTIVE_FAMILY_ID)
            it.remove(Keys.ACTIVE_ADMIN_UID)
            it[Keys.LOCATION_SHARING_ENABLED] = false
        }
    }
}
