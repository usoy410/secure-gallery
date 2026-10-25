package com.sudo.securegallery.scan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scanPrefsStore by preferencesDataStore("scan_prefs")

/** Scan settings (#27). Holds no photo data. */
class ScanPrefs(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.scanPrefsStore)

    /** True: new photos are processed only while the phone charges (§8.2, background work). */
    val chargingOnly: Flow<Boolean> = dataStore.data.map { it[CHARGING_ONLY] ?: false }

    suspend fun chargingOnly(): Boolean = chargingOnly.first()

    suspend fun setChargingOnly(value: Boolean) {
        dataStore.edit { it[CHARGING_ONLY] = value }
    }

    private companion object {
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
    }
}
