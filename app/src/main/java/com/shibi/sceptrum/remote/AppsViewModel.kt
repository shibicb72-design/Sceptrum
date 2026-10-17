package com.shibi.sceptrum.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.data.active
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.session.RemoteSession
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddAppForm(
    val label: String = "",
    val link: String = "",
    val error: AppShortcutError? = null,
)

data class AppsUiState(
    val shortcuts: List<AppShortcut> = AppShortcuts.defaults,
    /** Edit mode: reorder and remove instead of launch. */
    val editing: Boolean = false,
    val form: AddAppForm = AddAppForm(),
    /** "Add current app" target: the TV's foreground app, when it reported one and it is not in the grid. */
    val currentApp: AppShortcut? = null,
    /** Edits need a saved device to hold them. */
    val canEdit: Boolean = false,
)

/**
 * The apps grid for the active device (plan §3.4): launch with app_link, add by
 * package or URL, add the TV's current app, reorder and remove. The order is
 * kept per device in the encrypted device store. Only links that pass
 * [AppShortcuts.isAllowed] are added, shown or sent; a stored tile that fails
 * is left out and logged once.
 */
class AppsViewModel(
    private val repository: DeviceRepository,
    private val session: RemoteSession,
    private val errors: ErrorLog? = null,
) : ViewModel() {
    private val editing = MutableStateFlow(false)
    private val form = MutableStateFlow(AddAppForm())

    /** Blocked stored links already logged, so each is reported once while this view model lives. */
    private val reportedBlocked = mutableSetOf<String>()

    private val _rejects = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** A launch did not go out (not connected or blocked link): Reject haptic. */
    val rejects: SharedFlow<Unit> = _rejects

    val state: StateFlow<AppsUiState> =
        combine(repository.store, session.state, editing, form) { store, s, edit, f ->
            val device = store.active()
            reportBlocked(device)
            val list = AppShortcuts.effective(device)
            val current = s.currentApp?.let(AppShortcuts::forPackage)?.takeIf { c -> list.none { it.link == c.link } }
            AppsUiState(
                shortcuts = list,
                editing = edit && device != null,
                form = f,
                currentApp = current,
                canEdit = device != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun launch(shortcut: AppShortcut) {
        if (!AppShortcuts.isAllowed(shortcut.link)) {
            errors?.record("Launch app", "Not sent, blocked link scheme: ${schemeLabel(shortcut.link)}")
            _rejects.tryEmit(Unit)
            return
        }
        if (!session.launchApp(shortcut.link)) _rejects.tryEmit(Unit)
    }

    fun setEditing(on: Boolean) {
        editing.value = on
        if (!on) form.value = AddAppForm()
    }

    fun onLabelChange(text: String) = form.update { it.copy(label = text.take(MAX_LABEL), error = null) }

    fun onLinkChange(text: String) = form.update { it.copy(link = text.trim().take(MAX_LINK), error = null) }

    /** Adds the typed package or URL; on success the form empties. */
    fun submit() {
        val f = form.value
        val shortcut = AppShortcuts.parse(f.label, f.link)
        if (shortcut == null) {
            val error = AppShortcuts.refusalFor(f.link) ?: AppShortcutError.Invalid
            form.update { it.copy(error = error) }
            return
        }
        edit { list ->
            when (val result = AppShortcuts.add(list, shortcut)) {
                is AppShortcuts.Result.Ok -> {
                    form.value = AddAppForm()
                    result.list
                }
                is AppShortcuts.Result.Refused -> {
                    form.update { it.copy(error = result.error) }
                    null
                }
            }
        }
    }

    fun addCurrentApp() {
        val app = state.value.currentApp ?: return
        edit { list -> (AppShortcuts.add(list, app) as? AppShortcuts.Result.Ok)?.list }
    }

    fun remove(index: Int) = edit { AppShortcuts.remove(it, index) }

    fun moveUp(index: Int) = edit { AppShortcuts.move(it, index, index - 1) }

    fun moveDown(index: Int) = edit { AppShortcuts.move(it, index, index + 1) }

    fun resetToDefaults() {
        viewModelScope.launch {
            val id = repository.store.first().active()?.id ?: return@launch
            repository.resetAppShortcuts(id)
        }
    }

    /** Applies [change] to the active device's grid and saves it; null leaves it alone. */
    private fun edit(change: (List<AppShortcut>) -> List<AppShortcut>?) {
        viewModelScope.launch {
            val device = repository.store.first().active() ?: return@launch
            val before = AppShortcuts.effective(device)
            val after = change(before) ?: return@launch
            if (after != before) repository.setAppShortcuts(device.id, after)
        }
    }

    /** Logs each stored tile [AppShortcuts.effective] leaves out, once. */
    private fun reportBlocked(device: PairedDevice?) {
        for (tile in AppShortcuts.blocked(device)) {
            val firstTime = synchronized(reportedBlocked) { reportedBlocked.add("${device?.id}|${tile.link}") }
            if (firstTime) {
                errors?.record(
                    "Apps",
                    "Hid saved tile \"${tile.label}\" (blocked link ${schemeLabel(tile.link)}); the next grid edit removes it from storage",
                    tv = device?.let { "${it.name} (${it.host})" }.orEmpty(),
                )
            }
        }
    }

    private companion object {
        const val MAX_LABEL = 24
        const val MAX_LINK = 200

        /** "intent:" style scheme for the log; never the rest of the link, which may carry extras. */
        fun schemeLabel(link: String): String = AppShortcuts.schemeOf(link)?.let { "$it:" } ?: "(no scheme)"
    }
}
