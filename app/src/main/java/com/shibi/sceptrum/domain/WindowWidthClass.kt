package com.shibi.sceptrum.domain

/**
 * Material 3 window width classes. On the Galaxy Z Fold 7 the cover screen
 * (about 411dp) is [Compact], split-screen windows are [Medium] and the inner
 * screen (about 984dp, or 1092dp landscape) is [Expanded].
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    /** Two or more panes side by side. */
    val isMultiPane: Boolean get() = this != Compact

    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f

        fun fromWidthDp(widthDp: Float): WindowWidthClass = when {
            widthDp >= EXPANDED_MIN_DP -> Expanded
            widthDp >= MEDIUM_MIN_DP -> Medium
            else -> Compact
        }
    }
}
