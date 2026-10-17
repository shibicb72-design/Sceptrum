package com.shibi.sceptrum.atv

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** Friendly name and MAC that a TV puts in its certificate subject. */
data class TvIdentity(val name: String, val mac: String?)

/**
 * androidtvremote2 `_parse_name_and_mac`:
 * - SHIELD style: `CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX`
 * - Nexus style: `dnQualifier=fugu/fugu/Nexus Player, CN=atvremote/XX:XX:XX:XX:XX:XX`
 *
 * The name is the last dnQualifier part, else the second-to-last CN part. The
 * MAC is the last CN part, kept only when it looks like a MAC.
 */
object CertificateNames {
    private const val DN_QUALIFIER_OID = "2.5.4.46"
    private val Mac = Regex("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")

    fun parse(certificate: X509Certificate): TvIdentity = parse(certificate.subjectX500Principal)

    fun parse(subject: X500Principal): TvIdentity {
        val attributes = rfc2253Attributes(subject.getName(X500Principal.RFC2253, mapOf(DN_QUALIFIER_OID to "DNQ")))
        val commonName = attributes["CN"].orEmpty()
        val dnQualifier = attributes["DNQ"].orEmpty()
        val cnParts = commonName.split("/")
        val name = when {
            dnQualifier.isNotEmpty() -> dnQualifier.split("/").last()
            cnParts.size > 1 -> cnParts[cnParts.size - 2]
            else -> commonName
        }
        val mac = cnParts.last().takeIf { Mac.matches(it) }?.uppercase()?.replace('-', ':')
        return TvIdentity(name = name, mac = mac)
    }

    /** Splits an RFC 2253 name into keyword to value (first wins), undoing escapes. */
    internal fun rfc2253Attributes(name: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var i = 0
        fun flush() {
            val k = key.toString().trim().uppercase()
            if (k.isNotEmpty() && k !in result) result[k] = value.toString()
            key.clear()
            value.clear()
            inValue = false
        }
        while (i < name.length) {
            val c = name[i]
            when {
                c == '\\' && i + 1 < name.length -> {
                    val next = name[i + 1]
                    val isHexPair = i + 2 < name.length && next.isHex() && name[i + 2].isHex()
                    if (isHexPair) {
                        value.append(name.substring(i + 1, i + 3).toInt(16).toChar())
                        i += 2
                    } else {
                        value.append(next)
                        i += 1
                    }
                }
                !inValue && c == '=' -> inValue = true
                c == ',' || c == '+' -> flush()
                inValue -> value.append(c)
                else -> key.append(c)
            }
            i++
        }
        flush()
        return result
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
