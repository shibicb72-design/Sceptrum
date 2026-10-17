package com.shibi.sceptrum.domain

/** One fallback step for a remote shortcut: a plain key or an app link. */
sealed interface TvStep {
    /** Sent as a remote key (SHORT). */
    data class Key(val keyCode: Int) : TvStep

    /** Sent as `remote_app_link_launch_request`. */
    data class Link(val uri: String) : TvStep
}

/**
 * Ordered fallback steps for a remote shortcut.
 *
 * Edit these lists to change the fallbacks; the first step that makes the TV
 * report a different foreground app wins.
 */
enum class TvShortcut(val steps: List<TvStep>) {
    Settings(
        listOf(
            TvStep.Key(176),
            TvStep.Link("intent:#Intent;action=android.settings.SETTINGS;end"),
            TvStep.Link("intent:#Intent;component=com.android.tv.settings/.MainSettings;end"),
            TvStep.Link("market://launch?id=com.android.tv.settings"),
        ),
    ),
    Inputs(
        listOf(
            TvStep.Key(178),
            TvStep.Link("intent:#Intent;component=com.xgimi.xhplayer/.InputActivity;end"),
            TvStep.Link("intent:#Intent;component=com.google.android.tvlauncher/.inputs.InputsPanelActivity;end"),
        ),
    ),
    ;
}

object TvShortcuts {
    /** How long to wait for the TV to report a new foreground app after a step. */
    const val CONFIRM_MS = 1_500L

    fun forKey(key: RemoteKey): TvShortcut? = when (key) {
        RemoteKey.Settings -> TvShortcut.Settings
        RemoteKey.Input -> TvShortcut.Inputs
        else -> null
    }

    /** Steps to try, the one that worked last time first, then the rest in their usual order. */
    fun ordered(shortcut: TvShortcut, lastWorked: Int?): List<TvStep> {
        val steps = shortcut.steps
        if (lastWorked == null || lastWorked !in steps.indices) return steps
        return listOf(steps[lastWorked]) + steps.filterIndexed { index, _ -> index != lastWorked }
    }
}
