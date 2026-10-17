package com.shibi.sceptrum.appearance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.AppearancePersistence
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppearanceRepository(application)

    val background: StateFlow<AppBackground> = repository.background.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppBackground.Default,
    )

    val darkTheme: StateFlow<Boolean> = repository.darkTheme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppearancePersistence.DEFAULT_DARK_THEME,
    )

    fun selectBackground(background: AppBackground) {
        viewModelScope.launch { repository.setBackground(background) }
    }

    fun setDarkTheme(dark: Boolean) {
        viewModelScope.launch { repository.setDarkTheme(dark) }
    }
}
