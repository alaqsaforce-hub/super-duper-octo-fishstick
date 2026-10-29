package com.voicedock.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.connectors.imapSessionProperties
import com.voicedock.ai.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class Phase1HardeningTest {
    @Test fun imapRequiresTlsHostnameIdentityVerification() {
        val properties = imapSessionProperties("imap.gmail.com")
        assertEquals("true", properties.getProperty("mail.imaps.ssl.enable"))
        assertEquals("true", properties.getProperty("mail.imaps.ssl.checkserveridentity"))
        assertEquals("imap.gmail.com", properties.getProperty("mail.imaps.host"))
    }

    @Test fun concurrentSettingsUpdatesAreNotLost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voicedock_settings", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.init(context)
        SettingsStore.update { it.copy(bubbleX = 0) }
        val workers = 8
        val increments = 50
        val pool = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        try {
            val futures = (1..workers).map {
                pool.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(increments) { SettingsStore.update { settings -> settings.copy(bubbleX = settings.bubbleX + 1) } }
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            assertEquals(workers * increments, SettingsStore.state.value.bubbleX)
            val saved = context.getSharedPreferences("voicedock_settings", Context.MODE_PRIVATE).getString("settings_v1", "")
            assertEquals(true, saved?.contains("\"bubbleX\":400"))
        } finally { pool.shutdownNow() }
    }
}
