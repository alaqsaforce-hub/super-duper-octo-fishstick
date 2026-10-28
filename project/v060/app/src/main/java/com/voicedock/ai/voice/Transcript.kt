package com.voicedock.ai.voice

import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * One recognised piece of speech.
 * provisional = taken from a *partial* result because the recogniser ended without a final result
 * (we never throw recognised words away); a later final result for the same utterance replaces it.
 */
@Serializable
data class Segment(val text: String, val provisional: Boolean = false)

/**
 * Joins recognition results without duplicating words.
 *
 * Android's SpeechRecognizer ends after every utterance, so continuous dictation = many short
 * recognition sessions. Some engines repeat the tail of the previous utterance at the start of the
 * next one, and partial results may be committed before a late final arrives. This merger removes
 * those overlaps but keeps genuine repetitions such as "ধীরে ধীরে" or "very very".
 */
object TranscriptMerger {
    private const val MAX_OVERLAP = 12
    private val ZW = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val EDGE_PUNCT = Regex("^[\\p{P}\\s।]+|[\\p{P}\\s।]+$")

    fun words(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Comparison key for a word: NFC, no zero-width chars, no edge punctuation, lower case. */
    fun key(w: String): String =
        Normalizer.normalize(w, Normalizer.Form.NFC).replace(ZW, "").replace(EDGE_PUNCT, "").lowercase()

    /** Number of words at the end of [existing] that equal the first words of [incoming]. */
    fun overlap(existing: List<String>, incoming: List<String>): Int {
        val max = minOf(MAX_OVERLAP, existing.size, incoming.size)
        for (k in max downTo 1) {
            var same = true
            for (i in 0 until k) {
                if (key(existing[existing.size - k + i]) != key(incoming[i])) { same = false; break }
            }
            if (same) return k
        }
        return 0
    }

    /**
     * Text of [incoming] that is new relative to [previous].
     * - overlap of 2+ words is removed (restart echo);
     * - an incoming result that is *entirely* the tail of previous (2+ words) is a duplicate → "";
     * - a 1-word overlap is kept (it is usually a real repetition).
     */
    fun newPart(previous: String, incoming: String): String {
        val prev = words(previous)
        val inc = words(incoming)
        if (inc.isEmpty()) return ""
        val k = overlap(prev, inc)
        if (k >= 2) return inc.drop(k).joinToString(" ")
        return inc.joinToString(" ")
    }

    /** Similar enough that [final] is the finished version of the provisional partial [prov]. */
    fun sameUtterance(prov: String, final: String): Boolean {
        val p = words(prov).map(::key)
        val f = words(final).map(::key)
        if (p.isEmpty() || f.isEmpty()) return false
        if (p.first() != f.first()) return false
        val common = p.count { it in f.toSet() }
        return common * 2 >= p.size
    }

    /** Adds a FINAL result to the segment list (pure). */
    fun addFinal(segments: List<Segment>, final: String): List<Segment> {
        val text = final.trim()
        if (text.isEmpty()) return segments
        val last = segments.lastOrNull()
        if (last != null && last.provisional && sameUtterance(last.text, text)) {
            // the late final replaces the partial we had to keep
            val before = segments.dropLast(1)
            val add = newPart(before.joinToString(" ") { it.text }, text)
            return if (add.isEmpty()) before else before + Segment(add)
        }
        val add = newPart(segments.joinToString(" ") { it.text }, text)
        return if (add.isEmpty()) segments else segments + Segment(add)
    }

    /** Keeps an unfinished PARTIAL result when the recogniser stopped without a final result. */
    fun keepPartial(segments: List<Segment>, partial: String): List<Segment> {
        val text = partial.trim()
        if (text.isEmpty()) return segments
        val add = newPart(segments.joinToString(" ") { it.text }, text)
        return if (add.isEmpty()) segments else segments + Segment(add, provisional = true)
    }

    fun plain(segments: List<Segment>): String = segments.joinToString(" ") { it.text.trim() }.trim()
}
