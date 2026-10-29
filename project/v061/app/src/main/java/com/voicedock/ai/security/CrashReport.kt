package com.voicedock.ai.security

/** Linear-time, conservative scrubber for local crash reports. */
object CrashReportScrubber {
    private val secretKeys = setOf(
        "apikey", "accesstoken", "refreshtoken", "idtoken", "clientsecret", "password", "passwd",
        "secret", "authorization", "bearer", "token", "oauthcode", "authorizationcode", "codeverifier",
        "verifier", "cookie", "setcookie", "otp", "pin",
    )
    private val valueDelimiters = setOf(' ', '\t', '\r', '\n', ',', ';', '&', '}', ')')

    /** Scrubs e-mail addresses, token-like strings, credential assignments and long digit runs. */
    fun scrub(input: String): String {
        if (input.isEmpty()) return input
        val text = input.take(100_000)
        val masked = BooleanArray(text.length)

        // Scan identifier-like tokens once for e-mails, JWTs and common provider-key prefixes.
        var i = 0
        while (i < text.length) {
            if (!tokenChar(text[i])) { i++; continue }
            val start = i
            while (i < text.length && tokenChar(text[i])) i++
            val end = i
            val token = text.substring(start, end)
            val trimmedEnd = token.length - token.reversed().takeWhile { it == '.' }.length
            val candidate = token.substring(0, trimmedEnd)
            if (looksLikeEmail(candidate) || looksLikeJwt(candidate) || looksLikeProviderKey(candidate)) mark(masked, start, start + candidate.length)
        }

        // Credential assignments, e.g. apiKey=..., Authorization: Bearer ..., refresh_token: "...".
        i = 0
        while (i < text.length) {
            if (!wordChar(text[i])) { i++; continue }
            val keyStart = i
            while (i < text.length && (wordChar(text[i]) || text[i] == '-')) i++
            val keyEnd = i
            val key = text.substring(keyStart, keyEnd).lowercase().filter { it.isLetterOrDigit() }
            if (key !in secretKeys) continue
            var valueStart = keyEnd
            while (valueStart < text.length && text[valueStart].isWhitespace()) valueStart++
            if (valueStart >= text.length || text[valueStart] !in setOf('=', ':')) {
                if (key == "bearer") {
                    val bearerValue = valueStart
                    val valueEnd = scanValueEnd(text, bearerValue)
                    if (valueEnd > bearerValue) mark(masked, bearerValue, valueEnd)
                }
                continue
            }
            valueStart++
            while (valueStart < text.length && text[valueStart].isWhitespace()) valueStart++
            if (valueStart >= text.length) continue
            val valueEnd = if (text[valueStart] == '"' || text[valueStart] == '\'') {
                val quote = text[valueStart]
                var end = valueStart + 1
                while (end < text.length && text[end] != quote && text[end] != '\n') end++
                if (end < text.length && text[end] == quote) end + 1 else end
            } else scanValueEnd(text, valueStart)
            if (valueEnd > valueStart) {
                var finalEnd = valueEnd
                if (key == "authorization" && text.regionMatches(valueStart, "Bearer ", 0, 7, ignoreCase = true)) {
                    finalEnd = scanValueEnd(text, valueStart + 7)
                }
                mark(masked, valueStart, finalEnd)
            }
        }

        // Mask long numeric runs, including card-like groups separated by spaces or hyphens.
        i = 0
        while (i < text.length) {
            if (Character.digit(text[i], 10) < 0) { i++; continue }
            val start = i
            var count = 0
            var end = i
            while (end < text.length) {
                if (Character.digit(text[end], 10) >= 0) {
                    count++
                    end++
                } else if ((text[end] == ' ' || text[end] == '-') && end + 1 < text.length && Character.digit(text[end + 1], 10) >= 0) {
                    end++
                } else break
            }
            if (count >= 6) mark(masked, start, end)
            i = end.coerceAtLeast(i + 1)
        }

        val out = StringBuilder(text.length)
        i = 0
        while (i < text.length) {
            if (!masked[i]) { out.append(text[i]); i++; continue }
            out.append("[REDACTED]")
            while (i < text.length && masked[i]) i++
        }
        if (input.length > text.length) out.append("\n[truncated]")
        return out.toString()
    }

    private fun scanValueEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] !in valueDelimiters && text[end] != '"' && text[end] != '\'') end++
        return end
    }

    private fun tokenChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c in "._%+-@"
    private fun wordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_'

    private fun looksLikeEmail(token: String): Boolean {
        val at = token.indexOf('@')
        if (at <= 0 || at != token.lastIndexOf('@') || at >= token.lastIndex) return false
        val local = token.substring(0, at)
        val domain = token.substring(at + 1).trimEnd('.')
        if (local.startsWith('.') || local.endsWith('.') || domain.startsWith('.') || domain.endsWith('.')) return false
        val dot = domain.lastIndexOf('.')
        return dot > 0 && domain.length - dot - 1 >= 2 &&
            local.all { it.isLetterOrDigit() || it in "._%+-" } &&
            domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    private fun looksLikeJwt(token: String): Boolean {
        if (token.count { it == '.' } != 2) return false
        val pieces = token.split('.')
        return pieces.size == 3 && pieces.all { p -> p.length >= 8 && p.all { it.isLetterOrDigit() || it == '_' || it == '-' } }
    }

    private fun looksLikeProviderKey(token: String): Boolean {
        val lower = token.lowercase()
        return (lower.startsWith("sk-") && token.length >= 24) ||
            (lower.startsWith("ghp_") && token.length >= 24) ||
            (lower.startsWith("gho_") && token.length >= 24) ||
            (lower.startsWith("ghu_") && token.length >= 24) ||
            (lower.startsWith("AIza") && token.length >= 24) ||
            (lower.startsWith("xoxb-") && token.length >= 24) ||
            (lower.startsWith("xoxp-") && token.length >= 24)
    }

    private fun mark(masked: BooleanArray, start: Int, end: Int) {
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(masked.size)) masked[i] = true
    }
}
