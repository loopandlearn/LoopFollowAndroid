package com.loopandlearn.loopfollow.settings.importexport

import java.net.URI
import java.security.MessageDigest

/** Reduces secrets in settings imports to a form that is safe to write to the log. */
internal object LogRedactor {
    private val knownHostSuffixes = listOf("nightscoutpro.com", "10be.de", "herokuapp.com")

    /** Byte count and a SHA-256 prefix, for blobs such as scanned QR contents. */
    fun fingerprint(string: String): String {
        val data = string.toByteArray(Charsets.UTF_8)
        val hex = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        return "${data.size} bytes, sha256=${hex.take(8)}…"
    }

    /** The last [keep] characters of [secret], prefixed with `…`. */
    fun tail(secret: String, keep: Int = 8): String {
        if (secret.isEmpty()) return "(empty)"
        if (secret.length <= keep) return "(redacted)"
        return "…" + secret.takeLast(keep)
    }

    /** Scheme and a masked host; path and query are dropped. */
    fun url(raw: String): String {
        if (raw.isEmpty()) return "(empty)"
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return "(redacted)"
        }
        val host = uri.host ?: return "(redacted)"
        val scheme = uri.scheme?.let { "$it://" } ?: ""
        return "$scheme${maskHost(host)}/…"
    }

    private fun maskHost(host: String): String {
        if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) return "***"
        if (host.contains(":") || host.startsWith("[")) return "***"
        val lower = host.lowercase()
        for (suffix in knownHostSuffixes) {
            if (lower == suffix || lower.endsWith(".$suffix")) return "***.$suffix"
        }
        val parts = host.split(".")
        val tld = parts.lastOrNull()
        return if (parts.size >= 2 && !tld.isNullOrEmpty()) "***.$tld" else "***"
    }

    /** First character, plus the shape of an email domain. */
    fun username(name: String): String {
        if (name.isEmpty()) return "(empty)"
        if (name.contains("@")) {
            val local = name.substringBefore("@")
            val domain = name.substringAfter("@")
            return "${local.firstOrNull() ?: '?'}***@${domain.firstOrNull() ?: '?'}***"
        }
        return "${name.first()}***"
    }
}
