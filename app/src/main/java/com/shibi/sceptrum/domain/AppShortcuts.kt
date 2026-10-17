package com.shibi.sceptrum.domain

import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.PairedDevice

/** Why "+ Add app" refused the input. */
enum class AppShortcutError {
    /** Neither a URL with a scheme nor a package id like com.example.app. */
    Invalid,

    /** A link whose scheme is not in [AppShortcuts.allowedSchemes], e.g. intent:, file: or javascript:. */
    BlockedScheme,

    /** A tile with the same link is already in the grid. */
    Duplicate,

    /** The grid holds [AppShortcuts.MAX] tiles. */
    Full,
}

/**
 * The apps grid (plan §3.4): defaults, parsing what the user typed, and the
 * list edits. Links are stored ready to send: a URL as typed, a package as
 * `market://launch?id=<package>` (androidtvremote2 `send_launch_app_command`).
 *
 * Only links with a scheme in [allowedSchemes] are accepted or sent from this
 * user path; the TV would otherwise open whatever an `intent:` URI names.
 * The built-in `intent:` fallbacks live in [TvShortcut] and never pass here.
 */
object AppShortcuts {
    const val MAX = 24
    private const val MARKET_PREFIX = "market://launch?id="

    /** Web and Play Store links. */
    val webSchemes: Set<String> = setOf("https", "http", "market")

    /** App deep-link schemes the TV apps register. */
    val appSchemes: Set<String> = setOf("plex", "netflix", "youtube", "vnd.youtube", "spotify", "primevideo")

    /** Every scheme a user-added link may use; anything else (intent:, file:, content:, javascript:, data:) is refused. */
    val allowedSchemes: Set<String> = webSchemes + appSchemes

    private val UriScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val Package = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Plan §3.4 defaults; each still needs a check on the real TVs. */
    val defaults: List<AppShortcut> = listOf(
        AppShortcut("YouTube", "https://www.youtube.com"),
        AppShortcut("Netflix", "https://www.netflix.com/title"),
        AppShortcut("Prime Video", "https://app.primevideo.com"),
        AppShortcut("Disney+", "https://www.disneyplus.com"),
        AppShortcut("Plex", "plex://"),
        AppShortcut("Settings", MARKET_PREFIX + "com.android.tv.settings"),
    )

    /** Friendly names for packages a Google TV reports as the current app. */
    private val knownPackages = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.plexapp.android" to "Plex",
        "com.spotify.tv.android" to "Spotify",
        "com.wbd.stream" to "Max",
        "com.hulu.livingroomplus" to "Hulu",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "org.xbmc.kodi" to "Kodi",
        "com.android.tv.settings" to "Settings",
        "com.google.android.tvlauncher" to "Home",
        "com.google.android.apps.tv.launcherx" to "Home",
    )

    /**
     * The device's own grid once edited, else the defaults; no device shows the
     * defaults. Stored tiles whose link is not [isAllowed] are left out.
     */
    fun effective(device: PairedDevice?): List<AppShortcut> {
        if (device == null || !device.app_shortcuts_edited) return defaults
        val stored = device.app_shortcuts
        // Same list instance when nothing is blocked, so the grid can skip on unrelated updates.
        return if (stored.all { isAllowed(it.link) }) stored else stored.filter { isAllowed(it.link) }
    }

    /** Stored tiles [effective] leaves out because their link is not [isAllowed]. */
    fun blocked(device: PairedDevice?): List<AppShortcut> =
        if (device != null && device.app_shortcuts_edited) device.app_shortcuts.filterNot { isAllowed(it.link) } else emptyList()

    /**
     * A URL with an allowed scheme stays as typed; a package id becomes a market
     * launch link; anything else is null ([refusalFor] says why).
     */
    fun linkFor(input: String): String? {
        val text = input.trim()
        return when {
            refusalFor(text) != null -> null
            // Lower-case scheme, so "HTTPS://x" is a duplicate of "https://x" and "Market://" is a market link.
            UriScheme.containsMatchIn(text) -> schemeOf(text) + ":" + text.substringAfter(':')
            else -> MARKET_PREFIX + text
        }
    }

    /** Why [linkFor] refuses [input], or null when it accepts it. */
    fun refusalFor(input: String): AppShortcutError? {
        val text = input.trim()
        return when {
            text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() } -> AppShortcutError.Invalid
            UriScheme.containsMatchIn(text) -> if (isAllowed(text)) null else AppShortcutError.BlockedScheme
            Package.matches(text) -> null
            else -> AppShortcutError.Invalid
        }
    }

    /**
     * True for a link this path may send: a scheme in [allowedSchemes], and for
     * web and market links a `//` authority. Case-insensitive, as URI schemes are.
     */
    fun isAllowed(link: String): Boolean {
        if (link.isEmpty() || link.any { it.isWhitespace() || it.isISOControl() }) return false
        // A fragment can carry "#Intent;component=…;end", which Intent.parseUri on the TV
        // turns into an arbitrary intent whatever the scheme. No tile needs one.
        if ('#' in link) return false
        val scheme = schemeOf(link) ?: return false
        if (scheme !in allowedSchemes) return false
        if (scheme !in webSchemes) return true
        val rest = link.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return false
        val authority = rest.substring(2)
        return authority.isNotEmpty() && authority.first() !in "/?#"
    }

    /**
     * [link] as the error log may show it: "https://host", "plex:", or a market
     * launch link with its package. Never a path, query or fragment, which may
     * carry tokens or extras. Built-in `intent:` fallbacks ([TvShortcut], which
     * users can't add) stay whole, so a hang-up names the step that caused it.
     */
    fun logLabel(link: String): String {
        val scheme = schemeOf(link) ?: return "(no scheme)"
        if (scheme == "intent") return link
        packageOf(link)?.takeIf { Package.matches(it) }?.let { return MARKET_PREFIX + it }
        val rest = link.trim().substring(scheme.length + 1)
        if (!rest.startsWith("//")) return "$scheme:"
        val host = rest.substring(2).takeWhile { it !in "/?#" }
        return "$scheme://$host"
    }

    /** The lower-case scheme of [link], or null when it has none. */
    fun schemeOf(link: String): String? = UriScheme.find(link.trim())?.groupValues?.get(1)?.lowercase()

    /** The package inside a market launch link, or null for a URL link. */
    fun packageOf(link: String): String? = link.removePrefix(MARKET_PREFIX).takeIf { link.startsWith(MARKET_PREFIX) }

    /**
     * A short label for [link]: a known app name, else the last package part or
     * the URL host without "www." / "app.", capitalised.
     */
    fun labelFor(link: String): String {
        val pkg = packageOf(link)
        if (pkg != null) {
            return knownPackages[pkg] ?: pkg.substringAfterLast('.').capitalised()
        }
        defaults.firstOrNull { it.link == link }?.let { return it.label }
        val afterScheme = link.substringAfter("://", missingDelimiterValue = link.substringAfter(':'))
        val host = afterScheme.substringBefore('/').substringBefore('?')
        val core = host.removePrefix("www.").removePrefix("app.").substringBefore('.')
        return core.ifEmpty { link.substringBefore(':') }.capitalised()
    }

    /** A new tile from the add form, or null ([refusalFor] says why). A blank [label] is derived from the link. */
    fun parse(label: String, input: String): AppShortcut? {
        val link = linkFor(input) ?: return null
        return AppShortcut(label.trim().ifEmpty { labelFor(link) }, link)
    }

    /** "Add current app": the package the TV last reported in `remote_ime_key_inject`. */
    fun forPackage(appPackage: String): AppShortcut? {
        if (!Package.matches(appPackage)) return null
        val link = MARKET_PREFIX + appPackage
        return AppShortcut(labelFor(link), link)
    }

    /** Appends [shortcut], or says why not. */
    fun add(list: List<AppShortcut>, shortcut: AppShortcut): Result {
        if (list.any { it.link == shortcut.link }) return Result.Refused(AppShortcutError.Duplicate)
        if (list.size >= MAX) return Result.Refused(AppShortcutError.Full)
        return Result.Ok(list + shortcut)
    }

    fun remove(list: List<AppShortcut>, index: Int): List<AppShortcut> =
        if (index in list.indices) list.filterIndexed { i, _ -> i != index } else list

    /** Moves the tile at [from] to [to]; out-of-range moves leave the list as it is. */
    fun move(list: List<AppShortcut>, from: Int, to: Int): List<AppShortcut> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val result = list.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /** One or two letters for the tile badge (no brand logos, plan §3.4). */
    fun initials(label: String): String {
        val words = label.split(' ', '-', '_').filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercaseChar() }
            else -> words.take(2).joinToString("") { it.take(1).uppercase() }
        }
    }

    sealed interface Result {
        data class Ok(val list: List<AppShortcut>) : Result

        data class Refused(val error: AppShortcutError) : Result
    }

    private fun String.capitalised(): String = replaceFirstChar { it.uppercaseChar() }
}
