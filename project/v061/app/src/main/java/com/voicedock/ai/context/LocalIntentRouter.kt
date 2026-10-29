package com.voicedock.ai.context

/**
 * v0.6 stand-in for the planned 2M-parameter intent model.
 *
 * Deterministic Bangla/English keyword routing: works offline, costs nothing, invents nothing.
 * When a real local LLM arrives (v1.0) it implements the same `route()` contract, so neither
 * memory nor the UI has to change. This is design decision #1 (Memory != Model).
 */
object LocalIntentRouter {

    private val RULES: List<Pair<List<String>, AssistantAction>> = listOf(
        listOf("কী ঘটেছে", "ঘটনা", "খবর", "আপডেট", "what happened", "catch up", "catchup", "missed") to AssistantAction.CATCH_UP,
        listOf("সারাংশ", "সংক্ষেপ", "summary", "summarise", "summarize", "tldr") to AssistantAction.SUMMARISE,
        listOf("আজকের কাজ", "কাজের তালিকা", "করণীয়", "টাস্ক", "task", "todo", "to-do") to AssistantAction.TODAY_TASKS,
        listOf("মনে রাখ", "মেমোরি", "remember", "save this", "note this") to AssistantAction.REMEMBER,
        listOf("কপি", "copy", "clipboard") to AssistantAction.COPY,
        listOf("পড়ে দাও", "বুঝিয়ে", "ব্যাখ্যা", "explain", "read this", "what is on") to AssistantAction.EXPLAIN_SCREEN,
        listOf("উত্তর", "রিপ্লাই", "reply", "draft") to AssistantAction.DRAFT_REPLY,
        listOf("বসাও", "লিখে দাও", "insert", "type it") to AssistantAction.INSERT_APPROVED,
        listOf("সাহায্য", "help", "কী করতে পার", "what can you do") to AssistantAction.GENERAL_HELP,
    )

    /** First matching rule wins; order above is intentional (specific before generic). */
    fun route(text: String): AssistantAction? {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null
        return RULES.firstOrNull { (keys, _) -> keys.any { t.contains(it) } }?.second
    }

    /** Honest, offline answer. Never claims to have read a screen or memory it does not have. */
    fun reply(text: String, action: AssistantAction?, bn: Boolean, requestMayHaveBeenSent: Boolean = false): String {
        val head = when {
            requestMayHaveBeenSent && bn -> "AI অনুরোধ সম্পূর্ণ হয়নি; এটি প্রোভাইডারে পৌঁছে থাকতে পারে। এখন অফলাইন নিয়ম দিয়ে উত্তর দিচ্ছি।"
            requestMayHaveBeenSent -> "The AI request did not complete and may have reached the provider; answering with local rules now."
            bn -> "আমি এখন অফলাইন নিয়ম দিয়ে কাজ করি (কোনো ক্লাউডে কিছু পাঠানো হয়নি)।"
            else -> "I am running on local rules right now (nothing was sent to a cloud AI)."
        }
        val body = when (action) {
            AssistantAction.CATCH_UP -> if (bn)
                "ইভেন্ট সংগ্রহ এখনো চালু হয়নি — v0.7-এ notification memory যোগ হবে। এখনি ড্রাফট ও নলেজ খুঁজে দিতে পারি।"
            else "Event capture is not enabled yet — notification memory arrives in v0.7. I can search drafts and knowledge now."
            AssistantAction.SUMMARISE, AssistantAction.EXPLAIN_SCREEN -> if (bn)
                "উপরের ‘সারাংশ’ বা ‘স্ক্রিন বুঝাও’ বোতাম চাপলে অনুমতি নিয়ে স্ক্রিন পড়ব।"
            else "Tap ‘Summarise’ or ‘Explain screen’ above and I will read the screen with your consent."
            AssistantAction.TODAY_TASKS -> if (bn)
                "কাজের তালিকা v0.9-এ priority engine-এর সাথে আসবে।"
            else "The task list arrives with the priority engine in v0.9."
            AssistantAction.REMEMBER -> if (bn)
                "স্থায়ী মেমোরি v0.7-এ যোগ হবে; তখন source ও timestamp সহ সেভ করব।"
            else "Persistent memory arrives in v0.7 with source and timestamp."
            AssistantAction.COPY -> if (bn) "লেখা ক্লিপবোর্ডে কপি করা হলো।" else "Copied to the clipboard."
            AssistantAction.DRAFT_REPLY, AssistantAction.INSERT_APPROVED -> if (bn)
                "খসড়া তৈরি করেছি; বসানোর আগে আপনার চাপ লাগবে।" else "Draft ready; I need your tap before inserting."
            AssistantAction.GENERAL_HELP -> if (bn)
                "আমি ডিক্টেশন, স্ক্রিন-প্রসঙ্গ, সারাংশ, কপি ও নলেজ-অনুসন্ধানে সাহায্য করতে পারি।"
            else "I can help with dictation, screen context, summaries, copying and knowledge search."
            else -> if (bn)
                "আপনি লিখেছেন: “$text”। এই প্রশ্নের উত্তর দিতে হলে v1.0-এর লোকাল মডেল বা আপনার অনুমোদিত remote provider লাগবে।"
            else "You wrote: “$text”. Answering that needs the v1.0 local model or a remote provider you approve."
        }
        return "$head $body"
    }
}
