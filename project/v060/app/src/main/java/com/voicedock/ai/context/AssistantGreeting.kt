package com.voicedock.ai.context

/**
 * Builds the text the assistant TYPES INTO ITS OWN BUBBLE on activation.
 *
 * Local-only by design: no AI call, no network, no invented numbers. Every digit comes from a
 * [CatchUp] built from local memory events. This is the "auto text bubble" from the product brief.
 */
object AssistantGreeting {

    private val BN_DIGITS = listOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun bnDigits(n: Int): String = n.coerceAtLeast(0).toString().map { BN_DIGITS[it - '0'] }.joinToString("")

    private fun num(n: Int, bn: Boolean) = if (bn) bnDigits(n) else n.toString()

    fun build(
        kind: ContextKind,
        catchUp: CatchUp?,
        bn: Boolean,
        appLabel: String = "",
    ): String {
        val prefix = catchUpPrefix(catchUp, bn)
        val body = when (kind) {
            ContextKind.FIELD_FOUND -> if (bn)
                "মেসেজ বক্স পেয়েছি। বলুন বা টাইপ করুন — লেখা বসানোর আগে আপনার অনুমোদন নেব।"
            else "I found a message box. Speak or type — I will ask before inserting anything."
            ContextKind.CONTENT_NO_FIELD -> if (bn)
                "এই পেজ পড়ে সারাংশ বা সহজ ব্যাখ্যা দিতে পারি। কী চাই?"
            else "I can summarise or explain this page. What would you like?"
            ContextKind.EMPTY_SCREEN -> if (bn)
                "এখানে লেখার ঘর বা পড়ার মতো কনটেন্ট নেই, তাই আমি নিজের বাবলে শুরু করছি।"
            else "No text box or content here, so I am starting in my own bubble."
            ContextKind.NO_ACCESS -> if (bn)
                "স্ক্রিন পড়তে পারিনি। তারপরও আমি আপনার মেমোরি ও প্রশ্নে সাহায্য করতে পারি।"
            else "I could not read the screen. I can still help with memory and questions."
            ContextKind.SECURE -> if (bn)
                "এই স্ক্রিনের কোনো তথ্য নেওয়া হয়নি। তবুও মেমোরি, কাজ ও সাধারণ প্রশ্নে সাহায্য করতে পারি।"
            else "Nothing was taken from this screen. I can still help with memory, tasks and general questions."
        }
        val tail = if (bn) " আমি কীভাবে আপনাকে সাহায্য করতে পারি?" else " How can I help you?"
        return (prefix + body + tail).trim()
    }

    /** Empty string when there are no events — never fabricate a summary. */
    fun catchUpPrefix(c: CatchUp?, bn: Boolean): String {
        if (c == null || c.isEmpty) return ""
        val h = num(c.windowHours, bn)
        val total = num(c.total, bn)
        val important = num(c.important, bn)
        val todos = num(c.todos, bn)
        val head = if (bn)
            "আপনি $h ঘণ্টা পর ফিরেছেন। $total টি নতুন ঘটনার মধ্যে $important টি গুরুত্বপূর্ণ, $todos টি করণীয় আছে। "
        else "You were away for $h hours. Of $total new events, $important are important and $todos need action. "
        val first = c.top.firstOrNull()?.takeIf { it.score >= CatchUpSummary.IMPORTANT_THRESHOLD }
        val next = if (first == null) "" else if (bn)
            "আগে দেখবেন: ${first.event.title} (${first.whyBn})। "
        else "First: ${first.event.title} (${first.whyEn}). "
        return head + next
    }
}
