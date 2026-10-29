package com.voicedock.ai

import com.voicedock.ai.accounts.ChatGptSession
import com.voicedock.ai.accounts.ChatGptSessionCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatGptSessionCacheTest {
    @Test fun oneSessionIsMemoizedPerAccount() {
        val cache = ChatGptSessionCache()
        val created = AtomicInteger()
        val factory: (String) -> ChatGptSession = {
            created.incrementAndGet()
            ChatGptSession(load = { null }, save = {}, onSignedOut = {})
        }
        val a1 = cache.get("account-a", factory)
        val a2 = cache.get("account-a", factory)
        val b = cache.get("account-b", factory)
        assertSame(a1, a2)
        assertNotSame(a1, b)
        assertEquals(2, created.get())
    }
}
