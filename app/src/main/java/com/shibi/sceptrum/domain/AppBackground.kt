package com.shibi.sceptrum.domain

/**
 * User-chosen app backdrop, ported from Spectacula. Separate from the dark/light
 * theme flag. Ids are stable persistence keys.
 */
sealed class AppBackground {
    abstract val id: String

    data object Default : AppBackground() {
        override val id: String = ID_DEFAULT
    }

    data class Solid(val swatchId: String) : AppBackground() {
        override val id: String = "$PREFIX_SOLID$swatchId"
    }

    data class Gradient(val blendId: String) : AppBackground() {
        override val id: String = "$PREFIX_GRADIENT$blendId"
    }

    companion object {
        const val ID_DEFAULT = "default"
        const val PREFIX_SOLID = "solid:"
        const val PREFIX_GRADIENT = "gradient:"
    }
}

/**
 * The backdrop actually painted. Dark theme paints the dark default over any
 * choice; the stored choice is never rewritten, so turning dark off shows it
 * again exactly.
 */
fun AppBackground.shownWith(darkTheme: Boolean): AppBackground =
    if (darkTheme) AppBackground.Default else this

data class SolidPastel(
    val id: String,
    val argb: Long,
)

data class PastelBlend(
    val id: String,
    val colors: List<Long>,
)

object BackgroundCatalog {
    val solids: List<SolidPastel> = listOf(
        SolidPastel("blush", 0xFFF8C8D4),
        SolidPastel("peach", 0xFFFFD4B8),
        SolidPastel("butter", 0xFFF7E6A1),
        SolidPastel("mint", 0xFFC8EDE0),
        SolidPastel("sage", 0xFFC5D9C3),
        SolidPastel("sky", 0xFFC5E4F7),
        SolidPastel("periwinkle", 0xFFC9C8F0),
        SolidPastel("lavender", 0xFFDCC8F0),
        SolidPastel("lilac", 0xFFE6D0F0),
        SolidPastel("rose", 0xFFF0C4D0),
        SolidPastel("coral", 0xFFF5C4B8),
        SolidPastel("apricot", 0xFFF8D4B8),
        SolidPastel("seafoam", 0xFFBFE8DC),
        SolidPastel("powder_blue", 0xFFD0E4F5),
        SolidPastel("mauve", 0xFFE0C8D8),
        SolidPastel("cream", 0xFFF5EDE0),
    )

    val blends: List<PastelBlend> = listOf(
        PastelBlend("cotton_candy", listOf(0xFFF8C8E0, 0xFFC8E4F8, 0xFFF0D0F0)),
        PastelBlend("sunrise", listOf(0xFFFFE0B8, 0xFFFFD0C8, 0xFFF8C8D8)),
        PastelBlend("mermaid", listOf(0xFFB8E8E0, 0xFFC8E0F8, 0xFFD8C8F0)),
        PastelBlend("sorbet", listOf(0xFFFFD0C0, 0xFFFFE8B8, 0xFFF0C8E0)),
        PastelBlend("twilight", listOf(0xFFD0C8F0, 0xFFF0C8E0, 0xFFC8D8F8)),
        PastelBlend("peachy_keen", listOf(0xFFFFD8C0, 0xFFFFE8D0, 0xFFF8D0C8)),
        PastelBlend("lilac_mist", listOf(0xFFE8D8F8, 0xFFF0E0F8, 0xFFD8E0F8)),
        PastelBlend("mint_cream", listOf(0xFFD0F0E0, 0xFFE8F5D8, 0xFFC8E8E8)),
    )

    const val DEFAULT_ARGB: Long = 0xFF0E0E12

    private val solidsById = solids.associateBy { it.id }
    private val blendsById = blends.associateBy { it.id }

    fun solid(id: String): SolidPastel? = solidsById[id]

    fun blend(id: String): PastelBlend? = blendsById[id]

    /** Every pickable backdrop: the dark default, 16 solids, then 8 blends. */
    val all: List<AppBackground> =
        listOf(AppBackground.Default) +
            solids.map { AppBackground.Solid(it.id) } +
            blends.map { AppBackground.Gradient(it.id) }

    fun colorsFor(background: AppBackground): List<Long> {
        return when (background) {
            AppBackground.Default -> listOf(DEFAULT_ARGB)
            is AppBackground.Solid -> listOf(solid(background.swatchId)?.argb ?: DEFAULT_ARGB)
            is AppBackground.Gradient -> blend(background.blendId)?.colors ?: listOf(DEFAULT_ARGB)
        }
    }

    fun sampleArgb(background: AppBackground): Long {
        return colorsFor(background).firstOrNull() ?: DEFAULT_ARGB
    }
}
