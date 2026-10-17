package com.shibi.sceptrum.diagnostics

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One caught error, kept so it can be read back after a restart. */
data class ErrorEntry(
    val timeMs: Long,
    val step: String,          // what the app was doing, e.g. "Launch app", "Connection", "Pairing"
    val message: String,       // short human message
    val chain: List<String>,   // exception chain, outermost first, each "ClassName: message"
    val tv: String,            // TV name, address and connection status at the time, may be ""
    val appVersion: String,
    val phone: String,         // phone model
)

/**
 * A small error log that survives app restarts and turns into one plain-text
 * [report] for a "Copy all" button. Callers pass messages only, and every
 * field also goes through [Redaction.scrub] on the way in and when read back
 * from disk, so keys, certificates, pairing codes and tokens never reach the
 * report even if a platform exception message carries them. Device names and
 * addresses are kept. No Android types, so plain JUnit can test it.
 */
class ErrorLog(
    private val file: File,
    private val appVersion: String,
    private val phone: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val max: Int = MAX_ENTRIES,
) {
    private val _entries: MutableStateFlow<List<ErrorEntry>> = MutableStateFlow(load())

    /** Recorded errors, newest first. */
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    /** Prepends one entry, drops the oldest beyond [max] and rewrites the file. */
    @Synchronized
    fun record(step: String, message: String, error: Throwable? = null, tv: String = "") {
        val entry = ErrorEntry(
            timeMs = clock(),
            step = step,
            message = message,
            chain = chainOf(error),
            tv = tv,
            appVersion = appVersion,
            phone = phone,
        ).scrubbed()
        val updated = (listOf(entry) + _entries.value).take(max)
        _entries.value = updated
        save(updated)
    }

    private fun save(entries: List<ErrorEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString("\n") { encode(it) })
        }
    }

    /** The whole log as one plain-text report for a "Copy all" button. */
    fun report(): String {
        val header = "Sceptrum error log: ${_entries.value.size} entries, app $appVersion, phone $phone"
        if (_entries.value.isEmpty()) return header
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(zone)
        return buildString {
            append(header)
            for (entry in _entries.value) {
                append("\n\n")
                append(formatter.format(Instant.ofEpochMilli(entry.timeMs)))
                append("  ${entry.step}: ${entry.message}")
                if (entry.tv.isNotBlank()) append("\n  TV: ${entry.tv}")
                entry.chain.forEachIndexed { index, link ->
                    append("\n  ")
                    if (index > 0) append("caused by ")
                    append(link)
                }
                append("\n  app ${entry.appVersion}, phone ${entry.phone}")
            }
        }
    }

    /** Entries written before redaction existed are scrubbed, and then the file is rewritten without them. */
    private fun load(): List<ErrorEntry> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readLines() }
            .getOrDefault(emptyList())
            .mapNotNull { decode(it) }
            .take(max)
        val clean = raw.map { it.scrubbed() }
        if (clean != raw) save(clean)
        return clean
    }

    private fun ErrorEntry.scrubbed(): ErrorEntry = copy(
        step = Redaction.scrub(step),
        message = Redaction.scrub(message),
        // Scrubbed as one text: a multi-line PEM read back from disk spans several chain lines.
        chain = Redaction.scrub(chain.joinToString("\n")).split("\n").filter { it.isNotEmpty() },
        tv = Redaction.scrub(tv),
    )

    private fun encode(entry: ErrorEntry): String = listOf(
        entry.timeMs.toString(),
        entry.step,
        entry.message,
        entry.chain.joinToString("\n"),
        entry.tv,
        entry.appVersion,
        entry.phone,
    ).joinToString("\t") { escape(it) }

    private fun decode(line: String): ErrorEntry? {
        if (line.isBlank()) return null
        val fields = splitFields(line)
        if (fields.size != 7) return null
        val timeMs = fields[0].toLongOrNull() ?: return null
        return ErrorEntry(
            timeMs = timeMs,
            step = unescape(fields[1]),
            message = unescape(fields[2]),
            chain = unescape(fields[3]).split("\n").filter { it.isNotEmpty() },
            tv = unescape(fields[4]),
            appVersion = unescape(fields[5]),
            phone = unescape(fields[6]),
        )
    }

    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        for (c in line) {
            if (c == '\t') {
                fields += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        fields += current.toString()
        return fields
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        /** Most recent entries kept on disk. */
        const val MAX_ENTRIES = 200

        /** Longest exception message kept, so scrubbing and the report stay small. */
        const val MAX_MESSAGE = 500

        /** Outermost first, at most 8 links, each "SimpleName: message" (message omitted when null, cut at [MAX_MESSAGE]). */
        fun chainOf(error: Throwable?): List<String> {
            val chain = mutableListOf<String>()
            var current = error
            while (current != null && chain.size < 8) {
                val message = current.message?.take(MAX_MESSAGE)
                chain += if (message == null) current.javaClass.simpleName else "${current.javaClass.simpleName}: $message"
                current = current.cause
            }
            return chain
        }
    }
}

/**
 * Removes secret material from free text before it is logged: PEM blocks,
 * long base64 and hex runs (DER certificates, keys, digests), colon-separated
 * fingerprints, six-character pairing codes and the values of known secret
 * fields. Names, IP addresses, ports, MAC addresses and ordinary words stay.
 */
object Redaction {
    const val PEM = "[redacted PEM]"
    const val BLOB = "[redacted]"

    private val PemBlock = Regex("-----BEGIN [A-Z0-9 ]+-----.*?(-----END [A-Z0-9 ]+-----|$)", RegexOption.DOT_MATCHES_ALL)

    /** `secret=…`, `"private_key": "…"`, `pairing code: 4FA21C`, `Authorization: Bearer …`. */
    private val SecretField = Regex(
        "(?i)(\"?\\b(?:private[ _-]?key|secret|pairing[ _-]?secret|pairing[ _-]?code|password|passwd|token|access[ _-]?token|" +
            "refresh[ _-]?token|api[ _-]?key|authorization|pkcs8|certificate[ _-]?der|cert[ _-]?der)\\b\"?\\s*[:=]\\s*)" +
            "(?:bearer\\s+)?(\"[^\"]*\"|(?!\\[redacted)\\S+)",
    )

    /** A pairing code after the word "code": six hex characters, e.g. "code 4fa21c". */
    private val PairingCode = Regex("(?i)(\\bcode\\b\\W{0,3})[0-9a-f]{6}\\b")

    /** 16+ octets as AA:BB:…; a 6-octet MAC address is too short to match. */
    private val Fingerprint = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){15,}[0-9A-Fa-f]{2}\\b")

    private val HexRun = Regex("\\b[0-9A-Fa-f]{32,}\\b")

    /** Base64 or base64url of 40+ characters that mixes letters and digits. */
    private val Base64Run = Regex("[A-Za-z0-9+/_-]{40,}={0,2}")

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = PemBlock.replace(text, PEM)
        out = SecretField.replace(out) { it.groupValues[1] + BLOB }
        out = PairingCode.replace(out) { it.groupValues[1] + BLOB }
        out = Fingerprint.replace(out, BLOB)
        out = HexRun.replace(out, BLOB)
        out = Base64Run.replace(out) { match ->
            val run = match.value
            if (run.any(Char::isDigit) && run.any(Char::isLetter)) BLOB else run
        }
        return out
    }
}
