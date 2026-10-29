package com.voicedock.ai.security

/**
 * Shared, platform-free detector for likely OTP/PIN and payment-card data.
 *
 * It intentionally favours blocking a suspicious code over sending it to an AI model. Numeric
 * context (price/order/postcode/year) reduces false positives; explicit OTP/PIN wording always wins.
 * The detector is heuristic, not a substitute for the user disabling screen context on sensitive apps.
 */
object SecretDetector {
    private val digitRun = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
    private val cardRun = Regex("(?<![0-9])[0-9](?:[0-9 -]{11,23}[0-9])(?![0-9])")

    private val otpTerms = listOf(
        "otp", "pin", "passcode", "one time password", "one-time password",
        "verification code", "security code", "authentication code", "login code",
        "verify code", "enter code", "enter the code", "type code", "type the code",
        "input code", "input the code", "code field", "code entry", "code is", "code:", "code -",
        "কোড", "ওটিপি", "পিন", "যাচাইকরণ কোড",
        "নিরাপত্তা কোড", "ভেরিফিকেশন কোড", "প্রবেশ কোড",
    )
    private val nonSecretNumberTerms = listOf(
        "price", "amount", "cost", "paid", "money", "balance", "taka", "tk", "৳",
        "order", "invoice", "reference", "ref no", "transaction id", "tracking", "receipt",
        "postcode", "post code", "postal", "zip code", "zip", "year", "calendar",
        "দাম", "মূল্য", "পরিমাণ", "টাকা", "ব্যালেন্স", "বিল", "অর্ডার", "ইনভয়েস",
        "রেফারেন্স", "ট্র্যাকিং", "রসিদ", "পোস্টকোড", "পোস্ট কোড", "জিপ", "সাল", "বছর",
    )

    /** True when [value] contains a likely secret; [context] may contain a field hint or view id. */
    fun isSecret(value: String, context: String = ""): Boolean {
        if (value.isBlank()) return false
        val text = normalizeDigits(value).lowercase()
        val contextText = normalizeDigits(context).lowercase().replace('_', ' ').replace('-', ' ')
        if (hasLuhnValidCard(text)) return true

        val codeCue = containsAnyCue(text, otpTerms) || containsAnyCue(contextText, otpTerms)
        val ordinaryNumberCue = containsAnyCue(text, nonSecretNumberTerms) || containsAnyCue(contextText, nonSecretNumberTerms)
        val numbers = digitRun.findAll(text).toList()
        if (numbers.isEmpty()) return false
        if (codeCue && numbers.any { it.value.length in 4..8 }) return true

        return numbers.any { match ->
            val digits = match.value
            // A four-digit year is ordinary unless labelled as a code. Six-to-eight digit bare
            // codes are treated as secrets unless surrounding words explain the number.
            digits.length in 6..8 && !ordinaryNumberCue && !isPlausibleYear(digits)
        }
    }

    /** Screen-level variant: also catches a label and its number exposed as adjacent UI nodes. */
    fun containsSecretScreenText(text: String): Boolean = isSecret(text)

    /** Convert every Unicode decimal digit (including Bengali numerals) to ASCII for matching. */
    fun normalizeDigits(value: String): String = buildString(value.length) {
        for (c in value) {
            val d = Character.digit(c, 10)
            append(if (d in 0..9) ('0'.code + d).toChar() else c)
        }
    }

    private fun containsAnyCue(text: String, cues: List<String>): Boolean = cues.any { cue ->
        if (cue.firstOrNull()?.isLetter() == true && cue.all { it.code < 128 }) {
            Regex("(?i)(?<![a-z0-9])${Regex.escape(cue)}(?![a-z0-9])").containsMatchIn(text)
        } else text.contains(cue, ignoreCase = true)
    }

    private fun isPlausibleYear(digits: String): Boolean =
        digits.length == 4 && digits.toIntOrNull() in 1900..2099

    private fun hasLuhnValidCard(text: String): Boolean = cardRun.findAll(text).any { match ->
        val digits = match.value.filter { it in '0'..'9' }
        digits.length in 13..19 && luhn(digits)
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.lastIndex downTo 0) {
            var n = digits[i] - '0'
            if (double) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            double = !double
        }
        return sum % 10 == 0
    }
}
