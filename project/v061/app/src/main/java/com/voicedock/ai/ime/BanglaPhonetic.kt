package com.voicedock.ai.ime

/**
 * Avro-style phonetic Bengali (Latin keys → Bengali), greedy longest match. Pure function — no dictionary.
 *
 * Joining (hasanta) between two consonants without a vowel in between happens only for a whitelist of common
 * conjuncts (ন্ত, স্ত, ক্র, প্র, ত্ত, ম্প …), য-ফলা (consonant + y), ব-ফলা (consonant + w) and র-ফলা for stops.
 * Colloquial words therefore stay correct: korte → করতে, korbe → করবে, amra → আমরা.
 * Force a join with "+" ("kor+ta" → কর্তা); break a whitelisted join with "`" ("kin`te" → কিনতে).
 */
object BanglaPhonetic {
    private const val HASANTA = "\u09CD"

    private val consonants: Map<String, String> = mapOf(
        "kkh" to "ক্ষ", "kSh" to "ক্ষ", "kh" to "খ", "k" to "ক", "q" to "ক", "x" to "ক্স",
        "gg" to "জ্ঞ", "gh" to "ঘ", "g" to "গ", "Ng" to "ঙ",
        "ch" to "ছ", "c" to "চ", "jh" to "ঝ", "j" to "জ", "NG" to "ঞ",
        "Th" to "ঠ", "T" to "ট", "Dh" to "ঢ", "D" to "ড", "N" to "ণ",
        "th" to "থ", "t" to "ত", "dh" to "ধ", "d" to "দ", "n" to "ন",
        "ph" to "ফ", "f" to "ফ", "p" to "প", "bh" to "ভ", "v" to "ভ", "b" to "ব", "m" to "ম",
        "z" to "য", "J" to "য", "r" to "র", "l" to "ল",
        "sh" to "শ", "S" to "শ", "Sh" to "ষ", "s" to "স", "h" to "হ",
        "Rh" to "ঢ়", "R" to "ড়", "y" to "য়", "Y" to "য়", "w" to "ও",
    )

    /** key → (independent vowel, vowel sign). "o" is the inherent vowel. */
    private val vowels: Map<String, Pair<String, String>> = mapOf(
        "rri" to ("ঋ" to "ৃ"), "OI" to ("ঐ" to "ৈ"), "OU" to ("ঔ" to "ৌ"), "ou" to ("ঔ" to "ৌ"),
        "ee" to ("ঈ" to "ী"), "oo" to ("ঊ" to "ূ"),
        "a" to ("আ" to "া"), "i" to ("ই" to "ি"), "I" to ("ঈ" to "ী"), "u" to ("উ" to "ু"), "U" to ("ঊ" to "ূ"),
        "e" to ("এ" to "ে"), "O" to ("ও" to "ো"), "o" to ("অ" to ""),
    )

    private val specials: Map<String, String> = mapOf(
        "t``" to "ৎ", "ng" to "ং", "^" to "ঁ", ":`" to "ঃ", "." to "।", "$" to "৳",
        "0" to "০", "1" to "১", "2" to "২", "3" to "৩", "4" to "৪", "5" to "৫", "6" to "৬", "7" to "৭", "8" to "৮", "9" to "৯",
    )

    /** Common conjuncts: first consonant key → second consonant keys that join with hasanta. */
    private val joins: Map<String, Set<String>> = run {
        val rPhala = setOf("k", "kh", "g", "gh", "T", "D", "t", "th", "d", "dh", "p", "ph", "f", "b", "bh", "v", "sh", "S", "s")
        val m = HashMap<String, MutableSet<String>>()
        fun j(a: String, vararg b: String) { m.getOrPut(a) { HashSet() }.addAll(b) }
        rPhala.forEach { j(it, "r") }
        j("k", "k", "t", "T", "s", "l", "Sh"); j("g", "n", "l", "dh"); j("c", "c", "ch"); j("j", "j", "jh")
        j("T", "T"); j("D", "D"); j("t", "t", "m", "n"); j("d", "d", "m", "dh", "bh", "b")
        j("n", "t", "th", "d", "dh", "T", "D", "n"); j("N", "T", "D", "N")
        j("p", "p", "t", "l"); j("b", "b", "d", "l"); j("m", "m", "p", "b", "bh"); j("l", "l", "p")
        j("s", "t", "th", "k", "p", "m", "n", "T", "ph", "f"); j("Sh", "T", "Th", "N", "k", "p")
        j("sh", "c", "ch", "n", "l"); j("S", "c", "ch", "n", "l")
        j("r", "m", "th", "Sh", "y")
        m
    }

    private val maxKey = (consonants.keys + vowels.keys + specials.keys).maxOf { it.length }

    /** True for characters that belong to a phonetic word (kept in the composing buffer). */
    fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '`' || c == '^' || c == '+'

    fun convert(input: String): String {
        val out = StringBuilder()
        var prevCons: String? = null      // key of the last consonant if no vowel followed it yet
        var prevVowel = false             // last output was a vowel (next "o" → ও, next vowel independent)
        var forceJoin = false
        var noJoin = false
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '+' && prevCons != null) { forceJoin = true; i++; continue }
            // a lone backtick (not part of "t``" / ":`", which match() consumes first) breaks a join
            if (ch == '`') { noJoin = true; i++; continue }
            val (key, kind) = match(input, i) ?: run {
                out.append(ch); prevCons = null; prevVowel = false; forceJoin = false; noJoin = false; i++; null
            } ?: continue
            i += key.length
            when (kind) {
                Kind.VOWEL -> {
                    val (ind, sign) = vowels.getValue(key)
                    when {
                        key == "o" && prevCons != null -> {}
                        key == "o" && prevVowel -> out.append("ও")
                        prevCons != null -> out.append(sign)
                        else -> out.append(ind)
                    }
                    prevCons = null; prevVowel = true
                }
                Kind.CONSONANT -> {
                    val b = consonants.getValue(key)
                    val p = prevCons
                    when {
                        p != null && (key == "y" || key == "Y") && !noJoin -> { out.append(HASANTA).append("য"); }
                        p != null && key == "w" && !noJoin -> out.append(HASANTA).append("ব")
                        key == "w" -> { out.append("ও"); prevCons = null; prevVowel = true; forceJoin = false; noJoin = false; continue }
                        p != null && !noJoin && (forceJoin || joins[p]?.contains(key) == true) -> out.append(HASANTA).append(b)
                        else -> out.append(b)
                    }
                    prevCons = key; prevVowel = false
                }
                Kind.SPECIAL -> {
                    out.append(specials.getValue(key))
                    val nasal = key == "ng" || key == "^" || key == ":`"
                    prevCons = null; prevVowel = nasal
                }
            }
            forceJoin = false; noJoin = false
        }
        return out.toString()
    }

    private enum class Kind { VOWEL, CONSONANT, SPECIAL }

    private fun match(s: String, at: Int): Pair<String, Kind>? {
        for (len in minOf(maxKey, s.length - at) downTo 1) {
            val sub = s.substring(at, at + len)
            lookup(sub)?.let { return it }
            // Keys are case-sensitive only where Avro is (T/D/N/R/S/I/U/O/J/Y…); otherwise "K" = "k".
            val lower = sub.lowercase()
            if (lower != sub && lookup(sub) == null) {
                val hasSensitive = sub.any { it.isUpperCase() && it in SENSITIVE }
                if (!hasSensitive) lookup(lower)?.let { return it }
            }
        }
        return null
    }

    private const val SENSITIVE = "TDNRSIUOJY"

    private fun lookup(k: String): Pair<String, Kind>? = when {
        k in specials -> k to Kind.SPECIAL
        k in vowels -> k to Kind.VOWEL
        k in consonants -> k to Kind.CONSONANT
        else -> null
    }

    /** Small hint shown on a key in Bengali mode (e.g. "k" → ক). */
    fun hint(key: String): String = when (key) {
        "o" -> "অ"; "w" -> "ও"; "a" -> "আ"
        else -> convert(key)
    }
}
