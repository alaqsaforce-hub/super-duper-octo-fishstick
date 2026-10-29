package com.voicedock.ai

import com.voicedock.ai.accounts.ChatGptAuth
import com.voicedock.ai.accounts.ChatGptCreds
import com.voicedock.ai.accounts.ChatGptSession
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ChatGptSessionRaceTest {
    private val now = 1_000_000L

    private fun expired(refresh: String = "old-refresh") = ChatGptCreds(
        clientId = "client", hostId = "host", subject = "subject", accessToken = "", refreshToken = refresh, expiresAt = 0,
    )

    private fun auth(server: MockWebServer) = ChatGptAuth(
        http = OkHttpClient(), authBase = server.url("/").toString().trimEnd('/'), now = { now },
    )

    @Test fun eightParallelCallersShareExactlyOneRefresh() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"openid"}"""))
        server.start()
        val credentials = AtomicReference(expired())
        val session = ChatGptSession(
            load = { credentials.get() }, save = { credentials.set(it) }, onSignedOut = { error("unexpected sign-out") },
            auth = auth(server), now = { now },
        )
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map {
                pool.submit(Callable {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    session.accessToken()
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(List(8) { "new-access" }, futures.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, server.requestCount)
            assertEquals("new-refresh", credentials.get().refreshToken)
        } finally {
            pool.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun terminalRefreshFailureUsesNewerStoredCredentialInsteadOfSigningOut() {
        val server = MockWebServer()
        val credentials = AtomicReference(expired())
        val newer = expired("rotated-by-other-call").copy(accessToken = "fresh-from-other-call", expiresAt = now + 3_600_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                credentials.set(newer)
                return MockResponse().setResponseCode(400).setBody("""{"error":{"code":"refresh_token_reused","message":"reused"}}""")
            }
        }
        server.start()
        val signedOut = AtomicBoolean(false)
        val session = ChatGptSession(
            load = { credentials.get() }, save = { credentials.set(it) }, onSignedOut = { signedOut.set(true) },
            auth = auth(server), now = { now },
        )
        try {
            assertEquals("fresh-from-other-call", session.accessToken())
            assertFalse(signedOut.get())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun staleRefreshRetriesOnceWithNewlyRotatedRefreshToken() {
        val server = MockWebServer()
        val credentials = AtomicReference(expired())
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (calls.incrementAndGet() == 1) {
                credentials.set(expired("other-refresh"))
                MockResponse().setResponseCode(400).setBody("""{"error":{"code":"refresh_token_reused","message":"reused"}}""")
            } else {
                MockResponse().setBody("""{"access_token":"recovered-access","refresh_token":"final-refresh","expires_in":3600}""")
            }
        }
        server.start()
        val session = ChatGptSession(
            load = { credentials.get() }, save = { credentials.set(it) }, onSignedOut = { error("must not sign out") },
            auth = auth(server), now = { now },
        )
        try {
            assertEquals("recovered-access", session.accessToken())
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("refresh_token=old-refresh"))
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("refresh_token=other-refresh"))
        } finally { server.shutdown() }
    }

    @Test fun terminalFailureSignsOutWhenStoredCredentialsHaveNotChanged() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_grant","message":"expired"}}"""))
        server.start()
        val credentials = AtomicReference(expired())
        val signedOut = AtomicBoolean(false)
        val session = ChatGptSession(
            load = { credentials.get() }, save = { credentials.set(it) }, onSignedOut = { signedOut.set(true) },
            auth = auth(server), now = { now },
        )
        try {
            try { session.accessToken(); error("expected invalid_grant") }
            catch (e: com.voicedock.ai.accounts.AuthException) { assertEquals("invalid_grant", e.code) }
            assertTrue(signedOut.get())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
