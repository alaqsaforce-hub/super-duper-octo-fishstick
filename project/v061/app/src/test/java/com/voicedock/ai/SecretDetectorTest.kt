package com.voicedock.ai

import com.voicedock.ai.security.SecretDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretDetectorTest {
    @Test fun detectsInlineBengaliMobileBankingOtp() =
        assertTrue(SecretDetector.isSecret("Your bKash verification code is 123456"))

    @Test fun normalizesBengaliDigitsAndDetectsInlineCode() =
        assertTrue(SecretDetector.isSecret("আপনার যাচাইকরণ কোড ১২৩৪৫৬"))

    @Test fun usesFieldHintAndViewIdForFourDigitOtp() =
        assertTrue(SecretDetector.isSecret("4821", "Enter OTP / com.app:id/otp_input"))

    @Test fun genericEnglishCodeFieldLabelMarksFourDigitsAsSecret() =
        assertTrue(SecretDetector.isSecret("4821", "Enter code"))

    @Test fun genericBengaliCodeFieldLabelMarksBengaliDigitsAsSecret() =
        assertTrue(SecretDetector.isSecret("৪৮২১", "কোড লিখুন"))

    @Test fun adjacentCodeLabelAndNumberAreDetectedOnScreen() =
        assertTrue(SecretDetector.containsSecretScreenText("Enter code 4821"))

    @Test fun explicitCodeContextWinsOverOrdinaryNumberContext() =
        assertTrue(SecretDetector.isSecret("Verification code 123456 for order 555"))

    @Test fun calendarYearIsNotASecret() = assertFalse(SecretDetector.isSecret("2026"))

    @Test fun calendarAndPriceContextDoNotLookLikeOtp() {
        assertFalse(SecretDetector.isSecret("the year is 2026 in the calendar"))
        assertFalse(SecretDetector.isSecret("Price: 1500 taka"))
    }

    @Test fun postcodeAndOrderReferencesAreOrdinary() {
        assertFalse(SecretDetector.isSecret("postcode 4000"))
        assertFalse(SecretDetector.isSecret("order reference 482913"))
    }

    @Test fun bareSixToEightDigitsAreTreatedAsCodes() =
        assertTrue(SecretDetector.isSecret("482913"))

    @Test fun validLuhnCardIsDetected() =
        assertTrue(SecretDetector.isSecret("4111 1111 1111 1111"))

    @Test fun invalidLuhnCardIsNotTreatedAsCard() =
        assertFalse(SecretDetector.isSecret("1234 5678 9012 3456"))

    @Test fun phoneNumbersAreNotTreatedAsCardNumbers() {
        assertFalse(SecretDetector.isSecret("+8801712345678"))
        assertFalse(SecretDetector.isSecret("01712345678"))
    }
}
