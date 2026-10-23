package com.sudo.securegallery.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "setup")

/** Saves the last finished setup step (a step name only, never a PIN or code). */
class SetupProgressStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.setupDataStore)

    suspend fun completed(): SetupStep? =
        dataStore.data.first()[KEY]?.let { name -> SetupStep.entries.firstOrNull { it.name == name } }

    suspend fun markCompleted(step: SetupStep) {
        dataStore.edit { it[KEY] = step.name }
    }

    suspend fun isDone(): Boolean = completed() == SetupStep.DONE

    private companion object {
        val KEY = stringPreferencesKey("completed_step")
    }
}
