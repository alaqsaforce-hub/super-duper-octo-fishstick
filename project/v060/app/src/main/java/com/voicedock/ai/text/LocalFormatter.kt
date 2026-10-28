package com.voicedock.ai.text

import com.voicedock.ai.voice.Segment

/**
 * Mode A — Faithful Dictation, done locally (no AI, no cost, works offline).
 *
 * Fixes punctuation, spacing, capitalisation and obvious recognition artefacts WITHOUT changing the
 * meaning: no words are added, reordered or replaced except spoken punctuation commands
 * ("কমা", "new paragraph" …) and accidental English word stutters ("the the").
 *
 * Sentence boundaries come from the recogniser's utterance boundaries, but a boundary is NOT turned
 * into a full stop when the utterance clearly continues (ends with "এবং", "কারণ", "and", "because" …
 * or the next one starts with such a word). Mode B (AI-structured writing) arrives with the AI phase.
 */
object LocalFormatter {

    data class Options(
        val spokenCommands: Boolean = true,
        val removeEnglishStutters: Boolean = true,
    )

    private val BENGALI = Regex("[\\u0980-\\u09FF]")
    private const val END_MARKS = ".?!।"

    // spoken punctuation → symbol. Longest phrases first. English "period"/"full stop" only as a command word.
    private val COMMANDS: List<Pair<Regex, String>> = listOf(
        "নতুন অনুচ্ছেদ|নতুন প্যারা|new paragraph" to "\n\n",
        "নতুন লাইন|new line|next line" to "\n",
        "প্রশ্নবোধক চিহ্ন|প্রশ্নচিহ্ন|question mark" to "?",
        "বিস্ময়সূচক চিহ্ন|বিস্ময় চিহ্ন|exclamation mark|exclamation point" to "!",
        "দাঁড়ি|দাড়ি|পূর্ণচ্ছেদ|full stop|full-stop" to "।",
        "কমা|comma" to ",",
        "সেমিকোলন|semicolon" to ";",
        "কোলন|colon" to ":",
        "হাইফেন|hyphen" to "-",
        "আন্ডারস্কোর|underscore" to "_",
        "ব্র্যাকেট শুরু|বন্ধনী শুরু|open bracket|open parenthesis" to "(",
        "ব্র্যাকেট শেষ|বন্ধনী শেষ|close bracket|close parenthesis" to ")",
    ).map { (words, sym) -> Regex("(?<![\\p{L}\\p{M}])(?:$words)(?![\\p{L}\\p{M}])", RegexOption.IGNORE_CASE) to sym }
    private val PERIOD_AT_END = Regex("(?<![\\p{L}\\p{M}])period\\s*$", RegexOption.IGNORE_CASE)

    private val CONTINUE_END = setOf(
        "এবং", "আর", "ও", "কিন্তু", "তবে", "অথবা", "বা", "যে", "কারণ", "তাই", "যদি", "যেহেতু", "সুতরাং", "নাকি",
        "and", "but", "or", "because", "so", "that", "which", "who", "if", "the", "a", "an", "to", "of", "with", "for", "in", "on", "at",
    )
    private val CONTINUE_START = setOf("এবং", "আর", "কারণ", "যেহেতু", "and", "or", "because", "which", "who", "that")
    private val COMMA_START = setOf("কিন্তু", "তবে", "but")

    private val BN_QUESTION = setOf("কি", "কী", "কেন", "কোথায়", "কখন", "কীভাবে", "কিভাবে", "কেমন", "কত", "কতটা", "কতজন",
        "কোন", "কোনটা", "কোনটি", "কে", "কারা", "কাকে", "কার", "কাদের", "নাকি")
    private val BN_NOT_QUESTION = setOf("যে", "যদি", "যখন", "যেখানে", "যেভাবে", "জানি", "জানিনা", "বলতে")
    private val EN_QUESTION_START = setOf("what", "why", "how", "when", "where", "who", "whom", "whose", "which",
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "shall", "may", "might", "have", "has", "had")
    private val TRANSITIONS = setOf("প্রথমত", "দ্বিতীয়ত", "তৃতীয়ত", "চতুর্থত", "পরিশেষে", "সবশেষে", "অবশেষে", "তারপর",
        "first", "firstly", "second", "secondly", "third", "thirdly", "next", "then", "finally", "lastly")
    private val EN_STUTTER_KEEP = setOf("very", "no", "bye", "so", "really", "ha", "hey", "now", "go", "yes")

    fun format(segments: List<Segment>, options: Options = Options()): String =
        format(segments.map { it.text }, options)

    @JvmName("formatTexts")
    fun format(utterances: List<String>, options: Options = Options()): String {
        val parts = utterances.map { clean(it, options) }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, raw) in parts.withIndex()) {
            var p = raw
            val next = parts.getOrNull(i + 1)
            val lastWord = lastWordKey(p)
            val nextFirst = next?.let { firstWordKey(it) }
            val endsWithMark = p.trimEnd().lastOrNull()?.let { it in END_MARKS || it == '\n' || it == ',' || it == ':' || it == ';' } == true
            if (!endsWithMark) {
                p = when {
                    next != null && (lastWord in CONTINUE_END || nextFirst in CONTINUE_START) -> p
                    next != null && nextFirst in COMMA_START -> "$p,"
                    else -> p + terminalFor(sentenceTail(sb.toString() + " " + p))
                }
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append(' ')
            sb.append(p.trim())
        }
        return finish(sb.toString())
    }

    /** Per-utterance cleanup: commands, stutters, spacing. */
    fun clean(utterance: String, options: Options = Options()): String {
        var s = utterance.replace(Regex("[\\u200B\\uFEFF]"), "").replace(Regex("[ \\t]+"), " ").trim()
        if (s.isEmpty()) return s
        if (options.spokenCommands) {
            s = PERIOD_AT_END.replace(s, ".")
            for ((rx, sym) in COMMANDS) s = rx.replace(s) { sym }
        }
        if (options.removeEnglishStutters) s = removeStutters(s)
        return tidySpacing(s)
    }

    private fun removeStutters(s: String): String {
        val words = s.split(' ')
        val out = ArrayList<String>(words.size)
        for (w in words) {
            val prev = out.lastOrNull()
            val isEnglish = w.isNotEmpty() && w.all { it.isLetter() && it.code < 0x250 || it == '\'' }
            if (prev != null && isEnglish && prev.equals(w, ignoreCase = true) && w.lowercase() !in EN_STUTTER_KEEP) continue
            out += w
        }
        return out.joinToString(" ")
    }

    /** No space before , . ? ! । : ; ) ; none after ( ; hyphen/underscore join words. */
    fun tidySpacing(s: String): String = s
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .replace(Regex("[ \\t]+([,.?!।:;)])"), "$1")
        .replace(Regex("\\([ \\t]+"), "(")
        .replace(Regex("[ \\t]*([-_])[ \\t]*(?=[\\p{L}\\p{N}])"), "$1")
        .replace(Regex("([,;:])(?=[\\p{L}\\p{N}])"), "$1 ")
        .replace(Regex("([.?!।])(?=[\\p{L}])"), "$1 ")
        .replace(Regex("([,.?!।:;]){2,}")) { m -> m.value.last().toString() }
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()

    private fun words(s: String) = s.split(Regex("[\\s]+")).map { it.trim(',', '.', '?', '!', '।', ':', ';', '(', ')', '"').lowercase() }.filter { it.isNotEmpty() }
    private fun lastWordKey(s: String) = words(s).lastOrNull()
    private fun firstWordKey(s: String) = words(s).firstOrNull()

    /** The current (unterminated) sentence: text after the last end mark / line break. */
    private fun sentenceTail(s: String): String {
        val idx = s.indexOfLast { it in END_MARKS || it == '\n' }
        return if (idx < 0) s else s.substring(idx + 1)
    }

    fun terminalFor(sentence: String): String {
        val w = words(sentence)
        val bengali = BENGALI.containsMatchIn(sentence)
        val question = if (bengali) w.any { it in BN_QUESTION } && w.none { it in BN_NOT_QUESTION }
            else w.firstOrNull { it !in TRANSITIONS } in EN_QUESTION_START
        return when {
            question -> "?"
            bengali -> "।"
            else -> "."
        }
    }

    /** Sentence-level finishing: transition commas, English capitals, "i" → "I", final spacing. */
    private fun finish(s: String): String {
        val sb = StringBuilder()
        var sentenceStart = true
        val tokens = Regex("\\n+|[^\\s\\n]+|[ \\t]+").findAll(s).map { it.value }.toList()
        for ((i, t) in tokens.withIndex()) {
            if (t.isBlank()) { sb.append(t); if (t.contains('\n')) sentenceStart = true; continue }
            var tok = t
            val bare = tok.trimEnd(',', '.', '?', '!', '।', ':', ';').lowercase()
            if (bare == "i" || bare.startsWith("i'")) tok = "I" + tok.substring(1)
            if (sentenceStart) {
                if (tok.first().isLowerCase() && tok.first().code < 0x250) tok = tok.replaceFirstChar { it.uppercaseChar() }
                // "প্রথমত আমরা…" → "প্রথমত, আমরা…"  (only when a word follows in the same sentence)
                val nextTok = tokens.getOrNull(i + 2)
                if (bare in TRANSITIONS && tok.last().isLetterOrDigit() && nextTok != null && !nextTok.startsWith("\n")) tok = "$tok,"
            }
            sb.append(tok)
            sentenceStart = tok.last() in END_MARKS || tok.contains('\n')
        }
        return sb.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
    }
}
