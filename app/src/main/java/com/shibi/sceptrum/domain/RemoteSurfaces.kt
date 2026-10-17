package com.shibi.sceptrum.domain

/**
 * Fills for the painted remote parts (keys, d-pad disc, rockers, cards). Each
 * is composited over the page sample before its content colour is chosen, so
 * the pure numbers live here where the contrast test can reach them.
 */
object RemoteSurfaces {
    /** Glass key on a light pastel page: mostly white, a hint of the page. */
    const val GLASS_LIGHT: Long = 0xC7FFFFFF

    /** Glass key on the dark page: a faint white lift. */
    const val GLASS_DARK: Long = 0x24FFFFFF

    /** The d-pad disc and rockers sit a step brighter than keys. */
    const val DISC_LIGHT: Long = 0xE6FFFFFF
    const val DISC_DARK: Long = 0x33FFFFFF

    /** Power key: a deep Roman red on every backdrop. */
    const val POWER: Long = 0xFFB3261E

    fun glass(isLight: Boolean): Long = if (isLight) GLASS_LIGHT else GLASS_DARK

    fun disc(isLight: Boolean): Long = if (isLight) DISC_LIGHT else DISC_DARK

    /** Every container a remote control is painted on, for one page lightness. */
    fun containers(isLight: Boolean): List<Long> = listOf(glass(isLight), disc(isLight), POWER)
}
