package com.sudo.securegallery.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.vaultPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "vault_prefs")

/** Vault settings that are not secrets: for now, whether the uninstall warning was dismissed. */
class VaultPrefs(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.vaultPrefsStore)

    suspend fun exportWarningDismissed(): Boolean = dataStore.data.first()[WARNING_DISMISSED] ?: false

    suspend fun dismissExportWarning() {
        dataStore.edit { it[WARNING_DISMISSED] = true }
    }

    private companion object {
        val WARNING_DISMISSED = booleanPreferencesKey("export_warning_dismissed")
    }
}
