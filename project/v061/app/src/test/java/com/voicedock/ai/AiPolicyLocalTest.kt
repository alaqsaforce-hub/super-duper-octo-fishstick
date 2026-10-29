package com.voicedock.ai

import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiPolicy
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.OpenAiCompatClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the two v0.6 local-first bugs found in the audit. */
class AiPolicyLocalTest {

    private fun reason(url: String, localOnly: Boolean = true, cloudAllowed: Boolean = false) =
        AiPolicy.blockReason(
            p = AiProvider.CUSTOM,
            model = "local-model",
            hasKey = false,
            customUrl = url,
            allowPaid = false,
            cloudAllowed = cloudAllowed,
            localOnly = localOnly,
        )

    @Test fun loopbackIsLocal() {
        assertTrue(AiPolicy.isLocalEndpoint("http://127.0.0.1:11434/v1"))
        assertTrue(AiPolicy.isLocalEndpoint("http://localhost:8080/v1"))
        assertTrue(AiPolicy.isLocalEndpoint("https://127.0.0.1:11434/v1"))
    }

    @Test fun privateLanIsLocal() {
        assertTrue(AiPolicy.isLocalEndpoint("http://192.168.0.10:11434/v1"))
        assertTrue(AiPolicy.isLocalEndpoint("http://10.0.0.5:8000/v1"))
        assertTrue(AiPolicy.isLocalEndpoint("http://172.20.1.4:8000/v1"))
        assertFalse(AiPolicy.isLocalEndpoint("http://172.32.1.4:8000/v1"))
    }

    @Test fun publicHttpIsRefused() {
        assertFalse(AiPolicy.isLocalEndpoint("http://example.com/v1"))
        assertEquals("insecure_url", reason("http://example.com/v1"))
        assertEquals("local_only", reason("https://example.com/v1"))
        assertEquals("no_consent", reason("https://example.com/v1", localOnly = false))
        assertEquals(null, reason("https://example.com/v1", localOnly = false, cloudAllowed = true))
    }

    @Test fun localOnlyDoesNotBlockSelfHostedModel() {
        // The bug: previously returned "local_only" even for a LAN/Ollama endpoint.
        assertEquals(null, reason("http://192.168.0.10:11434/v1", localOnly = true))
        assertEquals(null, reason("http://127.0.0.1:11434/v1", localOnly = true))
    }

    @Test fun localOnlyStillBlocksCloud() {
        assertEquals(
            "local_only",
            AiPolicy.blockReason(AiProvider.GEMINI, "gemini-flash", true, "", false, true, true),
        )
    }

    @Test fun blankUrlStillReported() {
        assertEquals("no_url", reason(""))
    }

    @Test fun spoofedPrivateHostsAndUserInfoAreNotLocal() {
        assertFalse(AiPolicy.isLocalEndpoint("http://10.attacker.example/v1"))
        assertFalse(AiPolicy.isLocalEndpoint("http://192.168.0.10.attacker.example/v1"))
        assertFalse(AiPolicy.isLocalEndpoint("http://127.0.0.1:8080@attacker.example/v1"))
        assertFalse(AiPolicy.isLocalEndpoint("http://10.999.1.1/v1"))
        assertFalse(AiPolicy.isLocalEndpoint("file:///tmp/model"))
        assertTrue(AiPolicy.isLocalEndpoint("http://[::1]:11434/v1"))
        assertEquals("invalid_url", reason("http://127.0.0.1:8080@attacker.example/v1"))
        assertEquals("insecure_url", reason("http://10.attacker.example/v1"))
    }

    @Test fun modelListingObeysPrivacySettings() {
        assertEquals("local_only", AiPolicy.networkBlockReason(AiProvider.GEMINI, "", true, true))
        assertEquals("no_consent", AiPolicy.networkBlockReason(AiProvider.GEMINI, "", false, false))
        assertEquals(null, AiPolicy.networkBlockReason(AiProvider.CUSTOM, "http://127.0.0.1:11434/v1", false, true))
        assertEquals("insecure_url", AiPolicy.networkBlockReason(AiProvider.CUSTOM, "http://10.attacker.example/v1", true, false))
    }

    @Test fun customEndpointDoesNotFollowRedirects() {
        val local = MockWebServer()
        val destination = MockWebServer()
        local.start(); destination.start()
        try {
            local.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/models")))
            val client = OpenAiCompatClient(AiProvider.CUSTOM, null, local.url("/v1").toString())
            try { client.listModels(); throw AssertionError("redirect must be refused") }
            catch (e: AiException) { assertEquals(307, e.httpCode) }
            assertEquals(1, local.requestCount)
            assertEquals(0, destination.requestCount)
        } finally { local.shutdown(); destination.shutdown() }
    }
}
