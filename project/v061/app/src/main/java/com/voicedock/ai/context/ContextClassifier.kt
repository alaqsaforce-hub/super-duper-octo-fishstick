package com.voicedock.ai.context

/** Actions the assistant may OFFER. Offering is never the same as doing: INSERT_APPROVED needs a tap. */
enum class AssistantAction {
    DICTATE,
    DRAFT_REPLY,
    INSERT_APPROVED,
    COPY,
    EXPLAIN_SCREEN,
    SUMMARISE,
    ASK_QUESTION,
    REMEMBER,
    CATCH_UP,
    TODAY_TASKS,
    GENERAL_HELP,
    WHY_BLOCKED,
    OPEN_SETTINGS,
}

data class ContextAssessment(
    val kind: ContextKind,
    val blockReason: BlockReason,
    /** Redacted snapshot, safe to hand to UI/memory. */
    val snapshot: ContextSnapshot,
    val actions: List<AssistantAction>,
    val titleBn: String,
    val titleEn: String,
    val explanationBn: String,
    val explanationEn: String,
) {
    val safe: Boolean get() = kind != ContextKind.SECURE
    fun title(bn: Boolean) = if (bn) titleBn else titleEn
    fun explanation(bn: Boolean) = if (bn) explanationBn else explanationEn
}

/**
 * Turns a snapshot into a small, explainable decision. Pure rules — no model, no network, no battery.
 * A 2M-parameter intent model can replace/extend this later WITHOUT touching memory or device code.
 */
object ContextClassifier {

    fun assess(
        snapshot: ContextSnapshot,
        cfg: PolicyConfig,
        oracle: SafetyOracle = DefaultSafetyOracle,
    ): ContextAssessment {
        val (kind, reason) = ContextPolicy.evaluate(snapshot, cfg, oracle)
        val safe = ContextPolicy.safeSnapshot(snapshot, kind)
        val actions = actionsFor(kind)
        val (tBn, tEn) = titles(kind)
        val (eBn, eEn) = explanations(kind, reason, safe)
        return ContextAssessment(kind, reason, safe, actions, tBn, tEn, eBn, eEn)
    }

    /**
     * When the user has NOT granted screen-context consent we simply behave as a plain assistant.
     * This is NOT a "secure/blocked" screen — showing a security warning would be misleading.
     * The policy still guarantees that no probe ran (see AssistantOverlayService.activate()).
     */
    fun noConsentAssessment(): ContextAssessment = ContextAssessment(
        kind = ContextKind.EMPTY_SCREEN,
        blockReason = BlockReason.CONSENT_OFF,
        snapshot = ContextSnapshot(),
        actions = listOf(
            AssistantAction.OPEN_SETTINGS, AssistantAction.CATCH_UP,
            AssistantAction.TODAY_TASKS, AssistantAction.GENERAL_HELP,
        ),
        titleBn = "আমি কীভাবে সাহায্য করব?",
        titleEn = "How can I help?",
        explanationBn = "স্ক্রিন পড়ার অনুমতি দেওয়া হয়নি, তাই কোনো স্ক্রিন তথ্য নেওয়া হয়নি। চাইলে সেটিংসে চালু করতে পারেন।",
        explanationEn = "Screen-context consent is off, so no screen data was taken. You can enable it in Settings.",
    )

    private fun actionsFor(k: ContextKind): List<AssistantAction> = when (k) {
        ContextKind.FIELD_FOUND -> listOf(
            AssistantAction.DICTATE, AssistantAction.DRAFT_REPLY,
            AssistantAction.INSERT_APPROVED, AssistantAction.COPY, AssistantAction.SUMMARISE,
        )
        ContextKind.CONTENT_NO_FIELD -> listOf(
            AssistantAction.EXPLAIN_SCREEN, AssistantAction.SUMMARISE,
            AssistantAction.ASK_QUESTION, AssistantAction.REMEMBER,
        )
        ContextKind.EMPTY_SCREEN -> listOf(
            AssistantAction.CATCH_UP, AssistantAction.TODAY_TASKS,
            AssistantAction.ASK_QUESTION, AssistantAction.GENERAL_HELP,
        )
        ContextKind.NO_ACCESS -> listOf(
            AssistantAction.OPEN_SETTINGS, AssistantAction.CATCH_UP, AssistantAction.GENERAL_HELP,
        )
        ContextKind.SECURE -> listOf(
            AssistantAction.WHY_BLOCKED, AssistantAction.TODAY_TASKS, AssistantAction.GENERAL_HELP,
        )
    }

    private fun titles(k: ContextKind): Pair<String, String> = when (k) {
        ContextKind.FIELD_FOUND -> "মেসেজ বক্স পেয়েছি" to "I found a message box"
        ContextKind.CONTENT_NO_FIELD -> "এই পেজ পড়ে বুঝিয়ে দেব?" to "Shall I read this page for you?"
        ContextKind.EMPTY_SCREEN -> "আমি কীভাবে সাহায্য করব?" to "How can I help?"
        ContextKind.NO_ACCESS -> "স্ক্রিন পড়তে পারিনি" to "Could not read the screen"
        ContextKind.SECURE -> "এই স্ক্রিন পড়ছি না" to "I'm not reading this screen"
    }

    private fun explanations(k: ContextKind, reason: BlockReason, s: ContextSnapshot): Pair<String, String> =
        when (k) {
            ContextKind.FIELD_FOUND ->
                ("এখানে একটি লেখার ঘর আছে। বলুন বা টাইপ করুন — লেখা বসানোর আগে আপনার অনুমোদন নেব।" to
                    "There is an editable field. Speak or type — I will ask before inserting anything.")
            ContextKind.CONTENT_NO_FIELD ->
                ("এই স্ক্রিনে পড়ার মতো লেখা আছে, কিন্তু লেখার ঘর নেই। সারাংশ বা ব্যাখ্যা দিতে পারি।" to
                    "This screen has readable content but no text box. I can summarise or explain it.")
            ContextKind.EMPTY_SCREEN ->
                ("এখানে লেখার ঘর বা পড়ার মতো কনটেন্ট নেই। তাই আমি নিজের বাবলে শুরু করছি।" to
                    "No text box and nothing to read here, so I am starting in my own bubble.")
            ContextKind.NO_ACCESS ->
                ("স্ক্রিন পড়া যায়নি (Accessibility বন্ধ বা অ্যাপ সাড়া দেয়নি)। চাইলে সেটিংস খুলতে পারি।" to
                    "Screen probe failed (Accessibility off or the app did not respond). I can open Settings.")
            ContextKind.SECURE -> {
                val whyBn = ContextPolicy.blockExplanation(reason, true)
                val whyEn = ContextPolicy.blockExplanation(reason, false)
                ("$whyBn তবুও মেমোরি, কাজের তালিকা ও সাধারণ প্রশ্নে সাহায্য করতে পারি।" to
                    "$whyEn I can still help with memory, tasks and general questions.")
            }
        }
}
