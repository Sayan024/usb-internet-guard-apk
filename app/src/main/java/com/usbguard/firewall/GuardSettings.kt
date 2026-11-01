package com.usbguard.firewall

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "guard_settings")

object GuardSettings {
    /** True while the user wants USB Only Mode on. Used to restart it after a reboot. */
    private val ENABLED = booleanPreferencesKey("firewall_enabled")
    private val CHECKLIST = stringSetPreferencesKey("checklist_done")

    suspend fun isEnabled(context: Context): Boolean =
        context.applicationContext.dataStore.data.map { it[ENABLED] ?: false }.first()

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[ENABLED] = enabled }
    }

    fun checklistFlow(context: Context): Flow<Set<String>> =
        context.applicationContext.dataStore.data.map { it[CHECKLIST] ?: emptySet() }

    suspend fun setChecklistItem(context: Context, id: String, done: Boolean) {
        context.applicationContext.dataStore.edit {
            val current = it[CHECKLIST] ?: emptySet()
            it[CHECKLIST] = if (done) current + id else current - id
        }
    }
}
