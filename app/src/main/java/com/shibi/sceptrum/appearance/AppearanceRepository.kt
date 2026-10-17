package com.shibi.sceptrum.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.AppearancePersistence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "appearance",
)

/**
 * Dark theme flag and the chosen backdrop, stored separately. Turning dark on
 * never rewrites the backdrop, so turning it off restores the last pick.
 */
class AppearanceRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.appearanceDataStore)

    val background: Flow<AppBackground> = dataStore.data.map { prefs ->
        AppearancePersistence.decode(prefs[BackgroundKey])
    }

    val darkTheme: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DarkThemeKey] ?: AppearancePersistence.DEFAULT_DARK_THEME
    }

    suspend fun setBackground(background: AppBackground) {
        dataStore.edit { prefs ->
            prefs[BackgroundKey] = AppearancePersistence.encode(background)
        }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { prefs ->
            prefs[DarkThemeKey] = dark
        }
    }

    private companion object {
        val BackgroundKey = stringPreferencesKey(AppearancePersistence.KEY_BACKGROUND)
        val DarkThemeKey = booleanPreferencesKey(AppearancePersistence.KEY_DARK_THEME)
    }
}
