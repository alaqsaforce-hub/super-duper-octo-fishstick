package com.voicedock.ai

import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.context.AssistantGreeting
import com.voicedock.ai.context.AssistantPrefs
import com.voicedock.ai.context.BlockReason
import com.voicedock.ai.context.CatchUpSummary
import com.voicedock.ai.context.ContextClassifier
import com.voicedock.ai.context.ContextKind
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.context.DefaultSafetyOracle
import com.voicedock.ai.context.EventKind
import com.voicedock.ai.context.FieldInfo
import com.voicedock.ai.context.LocalIntentRouter
import com.voicedock.ai.context.MemoryEvent
import com.voicedock.ai.context.PolicyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM mirror of verify/CoreSelfTest.kt — runs with `./gradlew :app:testDebugUnitTest`. */
class ContextCoreTest {
    private val oracle = DefaultSafetyOracle
    private val cfg = PolicyConfig(screenContextConsent = true)

    @Test fun consentIsRequired() {
        val a = ContextClassifier.assess(ContextSnapshot(pkg = "com.whatsapp"), PolicyConfig(), oracle)
        assertEquals(ContextKind.SECURE, a.kind)
        assertEquals(BlockReason.CONSENT_OFF, a.blockReason)
    }

    @Test fun sensitiveScreensAreNeverRead() {
        listOf(
            ContextSnapshot(pkg = "com.bKash.customerapp"),
            ContextSnapshot(pkg = "x", appLabel = "বিকাশ"),
            ContextSnapshot(pkg = "x", fields = listOf(FieldInfo(editable = true, password = true))),
            ContextSnapshot(pkg = "x", secureTextHits = 1),
            ContextSnapshot(pkg = "x", flagSecure = true),
            ContextSnapshot(pkg = "com.voicedock.ai", isSelf = true),
        ).forEach { assertEquals(ContextKind.SECURE, ContextClassifier.assess(it, cfg, oracle).kind) }
    }

    @Test fun blockedSnapshotLosesTextButKeepsCounters() {
        val a = ContextClassifier.assess(
            ContextSnapshot(pkg = "com.bank.app", fields = listOf(FieldInfo(label = "SECRET", editable = true)), visibleCharCount = 300),
            cfg, oracle,
        )
        assertTrue(a.snapshot.fields.isEmpty())
        assertEquals("", a.snapshot.pkg)
        assertEquals("", a.snapshot.appLabel)
        assertEquals(300, a.snapshot.visibleCharCount)
        assertFalse(AssistantAction.INSERT_APPROVED in a.actions)
    }

    @Test fun incompleteProbeNeverOffersInsertionAndLosesMetadata() {
        val snapshot = ContextSnapshot(pkg = "com.example", appLabel = "Private app", probeOk = false,
            fields = listOf(FieldInfo(label = "private", editable = true, focused = true)))
        val a = ContextClassifier.assess(snapshot, cfg, oracle)
        assertEquals(ContextKind.NO_ACCESS, a.kind)
        assertEquals("", a.snapshot.pkg)
        assertEquals("", a.snapshot.appLabel)
        assertTrue(a.snapshot.fields.isEmpty())
        assertFalse(AssistantAction.INSERT_APPROVED in a.actions)
    }

    @Test fun detectedPasswordTakesPrecedenceEvenIfScanWasIncomplete() {
        val a = ContextClassifier.assess(ContextSnapshot(probeOk = false,
            fields = listOf(FieldInfo(password = true))), cfg, oracle)
        assertEquals(ContextKind.SECURE, a.kind)
        assertEquals(BlockReason.PASSWORD_FIELD, a.blockReason)
    }

    @Test fun classificationBranches() {
        assertEquals(ContextKind.FIELD_FOUND, ContextClassifier.assess(
            ContextSnapshot(pkg = "com.whatsapp", fields = listOf(FieldInfo(focused = true, editable = true))), cfg, oracle).kind)
        assertEquals(ContextKind.CONTENT_NO_FIELD, ContextClassifier.assess(
            ContextSnapshot(pkg = "com.android.chrome", visibleCharCount = 900), cfg, oracle).kind)
        assertEquals(ContextKind.EMPTY_SCREEN, ContextClassifier.assess(
            ContextSnapshot(pkg = "com.miui.home"), cfg, oracle).kind)
        assertEquals(ContextKind.NO_ACCESS, ContextClassifier.assess(
            ContextSnapshot(probeOk = false), cfg, oracle).kind)
    }

    @Test fun fieldOffersInsertSecureDoesNot() {
        assertTrue(AssistantAction.INSERT_APPROVED in ContextClassifier.assess(
            ContextSnapshot(pkg = "com.whatsapp", fields = listOf(FieldInfo(editable = true))), cfg, oracle).actions)
        assertTrue(AssistantAction.OPEN_SETTINGS in ContextClassifier.assess(
            ContextSnapshot(probeOk = false), cfg, oracle).actions)
    }

    @Test fun secretTextRules() {
        assertTrue(oracle.secretText("483910"))
        assertTrue(oracle.secretText("4111 1111 1111 1111"))
        assertFalse(oracle.secretText("+8801712345678"))
        assertFalse(oracle.secretText("01712345678"))
        assertFalse(oracle.secretText("hello"))
    }

    @Test fun catchUpDedupsAndFiltersWindow() {
        val now = 1_700_000_000_000L
        val events = listOf(
            MemoryEvent("1", "n", now - 600_000, EventKind.ORDER, "জরুরি ক্লায়েন্ট অর্ডার", "রহিম"),
            MemoryEvent("2", "n", now - 700_000, EventKind.PAYMENT, "পেমেন্ট ১২,৫০০ টাকা", "রহিম"),
            MemoryEvent("3", "n", now - 800_000, EventKind.MESSAGE, "অফার ছাড়", "গ্রুপ"),
            MemoryEvent("4", "n", now - 900_000, EventKind.MESSAGE, "অর্ডার কনফার্ম?", "রহিম", dedupKey = "o1"),
            MemoryEvent("4b", "n", now - 890_000, EventKind.MESSAGE, "অর্ডার কনফার্ম?", "রহিম", dedupKey = "o1"),
            MemoryEvent("old", "n", now - 10 * 3_600_000L, EventKind.MESSAGE, "পুরনো"),
        )
        val cu = CatchUpSummary.build(events, now, 6, mapOf("রহিম" to 3))
        assertEquals(4, cu.total)
        assertTrue(cu.top.none { it.event.id == "old" })
        assertEquals(1, cu.top.count { it.event.dedupKey == "o1" })
        assertEquals("4b", cu.top.first { it.event.dedupKey == "o1" }.event.id)
        assertTrue(cu.top.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(cu.top.first().whyBn.isNotBlank())
    }

    @Test fun greetingNeverInventsNumbers() {
        assertEquals("৫০", AssistantGreeting.bnDigits(50))
        val plain = AssistantGreeting.build(ContextKind.EMPTY_SCREEN, null, true)
        assertFalse(plain.any { it in '০'..'৯' })
        val en = AssistantGreeting.build(ContextKind.FIELD_FOUND, null, false)
        assertTrue(en.contains("message box"))
    }

    @Test fun greetingUsesRealCounts() {
        val now = 1_700_000_000_000L
        val cu = CatchUpSummary.build(
            listOf(MemoryEvent("1", "n", now - 1000, EventKind.ORDER, "জরুরি অর্ডার", "রহিম")),
            now, 6, mapOf("রহিম" to 3),
        )
        val g = AssistantGreeting.build(ContextKind.EMPTY_SCREEN, cu, true)
        assertTrue(g.contains(AssistantGreeting.bnDigits(6)))
        assertTrue(g.contains("সাহায্য"))
    }

    @Test fun noConsentIsAGenericAssistant() {
        val a = ContextClassifier.noConsentAssessment()
        assertEquals(ContextKind.EMPTY_SCREEN, a.kind)
        assertTrue(AssistantAction.OPEN_SETTINGS in a.actions)
    }

    @Test fun offlineIntentRouter() {
        assertEquals(AssistantAction.CATCH_UP, LocalIntentRouter.route("কী ঘটেছে?"))
        assertEquals(AssistantAction.SUMMARISE, LocalIntentRouter.route("please summarise"))
        assertEquals(null, LocalIntentRouter.route("quantum chromodynamics"))
        assertTrue(LocalIntentRouter.reply("hi", null, false).contains("local rules"))
    }

    @Test fun prefsArePrivateByDefault() {
        val p = AssistantPrefs()
        assertFalse(p.screenContextConsent)
        assertFalse(p.backgroundEventsConsent)
        assertFalse(p.contactsConsent)
        assertFalse(p.screenshotConsent)
        assertTrue(p.localOnly)
        assertFalse(p.policy().screenContextConsent)
    }
}
