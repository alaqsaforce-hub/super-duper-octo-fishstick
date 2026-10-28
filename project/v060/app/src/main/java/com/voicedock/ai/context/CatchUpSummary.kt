package com.voicedock.ai.context

/**
 * Seed of the Priority Engine (v0.9 will add learned weights; the interface stays the same).
 *
 * Pure rules, fully offline, explainable: every ranked item carries "why". No model can invent a
 * number here — counts come only from events actually handed in by the local memory layer.
 */

enum class EventKind { MESSAGE, CALL, EMAIL, PAYMENT, ORDER, APP_UPDATE, FILE, NOTE, OTHER }

data class MemoryEvent(
    val id: String,
    val source: String,
    val occurredAt: Long,
    val kind: EventKind,
    val title: String,
    val person: String = "",
    val amount: String = "",
    /** Same key = same real-world event (notification re-post, duplicate mail thread, ...). */
    val dedupKey: String = id,
)

data class RankedEvent(
    val event: MemoryEvent,
    val score: Int,
    val whyBn: String,
    val whyEn: String,
) {
    fun why(bn: Boolean) = if (bn) whyBn else whyEn
}

data class CatchUp(
    val windowHours: Int,
    val total: Int,
    val important: Int,
    val todos: Int,
    val top: List<RankedEvent>,
    val generatedAt: Long,
) {
    val isEmpty: Boolean get() = total == 0
}

object CatchUpSummary {
    const val IMPORTANT_THRESHOLD = 7
    private val ACTIONABLE = setOf(EventKind.MESSAGE, EventKind.CALL, EventKind.EMAIL, EventKind.PAYMENT, EventKind.ORDER)

    private val URGENT = listOf("জরুরি", "এখনই", "তাড়াতাড়ি", "আজ", "কাল", "৫টা", "urgent", "asap", "deadline", "today", "tomorrow")
    private val MONEY = listOf("টাকা", "৳", "tk", "payment", "পেমেন্ট", "ইনভয়েস", "invoice", "বাকি", "due")
    private val ORDER = listOf("অর্ডার", "order", "ক্লায়েন্ট", "client", "চুক্তি", "contract")
    private val PROMO = listOf("অফার", "promo", "discount", "ছাড়", "গ্রুপ", "group", "notification", "আপডেট")

    fun build(
        events: List<MemoryEvent>,
        now: Long,
        windowHours: Int = 6,
        personBoost: Map<String, Int> = emptyMap(),
        topN: Int = 5,
    ): CatchUp {
        val from = now - windowHours.coerceAtLeast(1) * 3_600_000L
        // Dedup by key, keep newest, remember repeats for the +1 signal.
        val grouped = LinkedHashMap<String, MutableList<MemoryEvent>>()
        events.filter { it.occurredAt >= from }.forEach { grouped.getOrPut(it.dedupKey) { mutableListOf() }.add(it) }
        val deduped = grouped.values.map { list -> list.maxByOrNull { it.occurredAt }!! to list.size }

        val ranked = deduped.map { (e, repeats) ->
            val (s, why) = score(e, now, personBoost, repeats)
            RankedEvent(e, s, why.first, why.second)
        }.sortedWith(compareByDescending<RankedEvent> { it.score }.thenByDescending { it.event.occurredAt })

        return CatchUp(
            windowHours = windowHours,
            total = deduped.size,
            important = ranked.count { it.score >= IMPORTANT_THRESHOLD },
            todos = deduped.count { (e, _) -> e.kind in ACTIONABLE },
            top = ranked.take(topN),
            generatedAt = now,
        )
    }

    /** Returns score + human-readable "why" in (bn, en). Deterministic and side-effect free. */
    fun score(
        e: MemoryEvent,
        now: Long,
        personBoost: Map<String, Int> = emptyMap(),
        repeats: Int = 1,
    ): Pair<Int, Pair<String, String>> {
        var s = 1
        val bn = mutableListOf<String>()
        val en = mutableListOf<String>()
        val t = e.title.lowercase()

        if (URGENT.any { t.contains(it) }) {
            s += 3; bn += "জরুরি/সময়সীমা"; en += "urgent / deadline"
        }
        if (personBoost[e.person]?.let { it >= 3 } == true) {
            s += 3; bn += "গুরুত্বপূর্ণ ব্যক্তি"; en += "important person"
        }
        if (e.kind == EventKind.PAYMENT || e.kind == EventKind.ORDER || e.amount.isNotBlank() || MONEY.any { t.contains(it) }) {
            s += 2; bn += "টাকা/অর্ডার"; en += "money / order"
        }
        if (ORDER.any { t.contains(it) }) {
            s += 2; bn += "ক্লায়েন্ট অর্ডার"; en += "client order"
        }
        if (e.kind == EventKind.MESSAGE && MONEY.any { t.contains(it) }) {
            s += 2; bn += "আগের অসম্পূর্ণ কাজ"; en += "linked pending task"
        }
        if (repeats >= 2) {
            s += 1; bn += "একাধিক বার এসেছে"; en += "repeated contact"
        }
        if (PROMO.any { t.contains(it) } && s < IMPORTANT_THRESHOLD) {
            s -= 1; bn += "প্রচার/গ্রুপ"; en += "promo / group"
        }
        return s.coerceAtLeast(0) to (bn.joinToString(" · ") to en.joinToString(" · "))
    }
}
