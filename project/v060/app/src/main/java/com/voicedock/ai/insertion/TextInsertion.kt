package com.voicedock.ai.insertion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Result of trying to put text into another app. */
enum class InsertResult(val bn: String, val en: String) {
    INSERTED("লেখা বসানো হয়েছে", "Text inserted"),
    COPIED("কপি হয়েছে — টেক্সট বক্সে লম্বা চেপে ‘পেস্ট’ করুন", "Copied — long-press the text field and choose Paste"),
    NO_SERVICE("সরাসরি বসানো বন্ধ (Accessibility চালু নেই) — কপি করা হলো", "Direct insertion is off (Accessibility not enabled) — copied instead"),
    NO_FIELD("কোনো টেক্সট বক্সে কার্সর নেই — কপি করা হলো", "No text field has the cursor — copied instead"),
    SECURE_FIELD("পাসওয়ার্ড/সুরক্ষিত বক্সে লেখা হয় না — কপি করা হলো", "Password/secure fields are never filled — copied instead"),
    REJECTED("এই অ্যাপ সরাসরি লেখা নেয়নি — কপি করা হলো", "This app did not accept direct insertion — copied instead"),
}

/** Strategy for getting approved text into the target app. */
interface TextInsertionStrategy {
    val id: String
    fun insert(context: Context, text: String): InsertResult
}

/** Method C: clipboard (always available). Written only on the user's explicit action. */
object ClipboardInsertion : TextInsertionStrategy {
    override val id = "clipboard"
    override fun insert(context: Context, text: String): InsertResult {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
        return InsertResult.COPIED
    }

    fun shareIntent(text: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "VoiceDock",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Picks the best available method: Accessibility (if the user enabled it) → clipboard fallback.
 * Every fallback is reported honestly to the user; nothing claims universal compatibility.
 */
object TextInserter {
    data class Outcome(val result: InsertResult, val destination: String)

    fun insert(context: Context, text: String, expectedPackage: String? = null): Outcome {
        val svc = InsertAccessibilityService.instance
        if (svc == null) {
            ClipboardInsertion.insert(context, text)
            return Outcome(InsertResult.NO_SERVICE, "clipboard")
        }
        val r = svc.insertIntoFocused(text, expectedPackage)
        if (r.result == InsertResult.INSERTED) return Outcome(r.result, "accessibility:${r.pkg}")
        ClipboardInsertion.insert(context, text)
        return Outcome(r.result, "clipboard")
    }
}

/** Pure text arithmetic for inserting at the cursor (unit tested). */
object InsertionMath {
    data class Result(val text: String, val cursor: Int)

    /**
     * Inserts [insert] into [existing] replacing the selection [selStart, selEnd] (−1 = end of text).
     * Adds a separating space when the neighbours would otherwise glue two words together.
     */
    fun insert(existing: String, selStart: Int, selEnd: Int, insert: String): Result {
        val len = existing.length
        var s = if (selStart in 0..len) selStart else len
        var e = if (selEnd in 0..len) selEnd else s
        if (e < s) { val t = s; s = e; e = t }
        val before = existing.substring(0, s)
        val after = existing.substring(e)
        val needLead = before.isNotEmpty() && !before.last().isWhitespace() && insert.firstOrNull()?.let { !it.isWhitespace() && it !in ",.?!।:;)" } == true
        val needTrail = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ",.?!।:;)" &&
            insert.lastOrNull()?.isWhitespace() == false
        val piece = (if (needLead) " " else "") + insert + (if (needTrail) " " else "")
        return Result(before + piece + after, before.length + piece.length)
    }
}
