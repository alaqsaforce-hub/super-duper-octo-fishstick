package com.voicedock.ai

import com.voicedock.ai.insertion.InsertionMath
import com.voicedock.ai.text.LocalFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFormatterTest {
    private fun f(vararg u: String) = LocalFormatter.format(u.toList())

    @Test fun bengaliSentencesGetDari() =
        assertEquals("আমি আজ অফিসে যাব। বিকেলে মিটিং আছে।", f("আমি আজ অফিসে যাব", "বিকেলে মিটিং আছে"))

    @Test fun bengaliQuestion() = assertEquals("আপনি কখন আসবেন?", f("আপনি কখন আসবেন"))

    @Test fun bengaliSubordinateIsNotQuestion() = assertEquals("আমি জানি সে কখন আসবে।", f("আমি জানি সে কখন আসবে"))

    @Test fun englishCapitalsAndQuestion() =
        assertEquals("I will send the report today. Can you check it?", f("i will send the report today", "can you check it"))

    @Test fun continuationWordSuppressesStop() =
        assertEquals("আমি যাব কারণ আজ জরুরি কাজ আছে।", f("আমি যাব কারণ", "আজ জরুরি কাজ আছে"))

    @Test fun spokenCommandsBn() =
        assertEquals("প্রিয় রহিম,\nকেমন আছো?", f("প্রিয় রহিম কমা নতুন লাইন কেমন আছো প্রশ্নবোধক চিহ্ন"))

    @Test fun spokenCommandsEn() =
        assertEquals("Hello team, please review.", f("hello team comma please review period"))

    @Test fun periodInsideSentenceIsAWord() =
        assertEquals("The trial period ends today.", f("the trial period ends today"))

    @Test fun englishStutterRemovedButIntentionalKept() =
        assertEquals("The report is very very good.", f("the the report is very very good"))

    @Test fun bengaliRepetitionUntouched() = assertEquals("ধীরে ধীরে হাঁটো।", f("ধীরে ধীরে হাঁটো"))

    @Test fun noWordsInvented() {
        val input = listOf("প্রথমত আমরা বাজেট দেখব", "তারপর সিদ্ধান্ত নেব")
        val out = LocalFormatter.format(input)
        val words = { s: String -> s.split(Regex("[\\s,।.?!]+")).filter { it.isNotBlank() } }
        assertEquals(words(input.joinToString(" ")), words(out))
    }

    @Test fun commaBeforeKintu() = assertEquals("আমি যেতে চাই, কিন্তু সময় নেই।", f("আমি যেতে চাই", "কিন্তু সময় নেই"))
}

class InsertionMathTest {
    @Test fun insertAtEndAddsSpace() = assertEquals(InsertionMath.Result("Hello world", 11), InsertionMath.insert("Hello", -1, -1, "world"))
    @Test fun insertIntoEmpty() = assertEquals(InsertionMath.Result("নমস্কার", 7), InsertionMath.insert("", 0, 0, "নমস্কার"))
    @Test fun replaceSelection() = assertEquals("I like tea a lot", InsertionMath.insert("I like coffee a lot", 7, 13, "tea").text)
    @Test fun insertInMiddleSpacesBothSides() = assertEquals("one two three", InsertionMath.insert("onethree", 3, 3, "two").text)
    @Test fun noSpaceBeforePunctuation() = assertEquals("Done.", InsertionMath.insert("Done", 4, 4, ".").text)
    @Test fun outOfRangeSelectionAppends() = assertEquals("abc def", InsertionMath.insert("abc", 99, 99, "def").text)
    @Test fun reversedSelectionHandled() = assertEquals("a X d", InsertionMath.insert("a bc d", 4, 2, "X").text)
}
