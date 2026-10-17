package com.shibi.sceptrum.domain

/**
 * String encoding for DataStore. No Android types so tests stay on JUnit.
 */
object AppearancePersistence {
    const val KEY_BACKGROUND = "background_id"
    const val KEY_DARK_THEME = "dark_theme"

    /** A fresh install opens dark, like Spectacula. */
    const val DEFAULT_DARK_THEME = true

    fun encode(background: AppBackground): String = background.id

    fun decode(raw: String?): AppBackground {
        if (raw.isNullOrBlank() || raw == AppBackground.ID_DEFAULT) {
            return AppBackground.Default
        }
        return when {
            raw.startsWith(AppBackground.PREFIX_SOLID) -> {
                val id = raw.removePrefix(AppBackground.PREFIX_SOLID)
                if (BackgroundCatalog.solid(id) == null) AppBackground.Default else AppBackground.Solid(id)
            }
            raw.startsWith(AppBackground.PREFIX_GRADIENT) -> {
                val id = raw.removePrefix(AppBackground.PREFIX_GRADIENT)
                if (BackgroundCatalog.blend(id) == null) AppBackground.Default else AppBackground.Gradient(id)
            }
            else -> AppBackground.Default
        }
    }
}
