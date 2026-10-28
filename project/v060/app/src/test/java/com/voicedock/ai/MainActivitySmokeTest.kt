package com.voicedock.ai

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** Launches the real activity and renders every screen (catches startup/composition crashes). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySmokeTest {
    @Test fun everyScreenRenders() {
        for (screen in listOf(null, "workspace", "drafts", "settings", "privacy")) {
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            if (screen != null) intent.putExtra(MainActivity.EXTRA_SCREEN, screen)
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("screen $screen finished unexpectedly", controller.get().isFinishing)
            controller.pause().stop().destroy()
        }
    }
}
