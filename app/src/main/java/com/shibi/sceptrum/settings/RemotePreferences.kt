package com.shibi.sceptrum.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.remoteDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote")

/** Remote behaviour settings (not secrets, so plain Preferences DataStore). */
class RemotePreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.remoteDataStore)

    /** Settings > "Type with key presses" (plan §3.2), off by default. */
    val keyPressTyping: Flow<Boolean> = dataStore.data.map { it[KeyPressTyping] ?: false }

    suspend fun setKeyPressTyping(on: Boolean) {
        dataStore.edit { it[KeyPressTyping] = on }
    }

    private companion object {
        val KeyPressTyping = booleanPreferencesKey("key_press_typing")
    }
}

class RemoteSettingsViewModel(private val preferences: RemotePreferences) : ViewModel() {
    val keyPressTyping: StateFlow<Boolean> =
        preferences.keyPressTyping.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setKeyPressTyping(on: Boolean) {
        viewModelScope.launch { preferences.setKeyPressTyping(on) }
    }
}
