package com.voicedock.ai.events

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voicedock.ai.settings.SettingsStore

/**
 * v0.6 scaffolding for Feature 04 (background event capture).
 *
 * IMPORTANT: this service is declared in the manifest but is a **no-op** until the user turns on
 * `assistantBackgroundEvents` in Settings AND grants "Notification access" in Android Settings.
 * v0.8 will replace the TODO with the normalise -> dedup -> checkpoint -> encrypted-memory
 * pipeline. Until then VoiceDock stores NOTHING from other apps' notifications.
 *
 * Keeping the class present in v0.6 lets Permissions.notificationListener() give an honest
 * ON/OFF status in the Privacy screen from day one.
 */
class VoiceDockNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!SettingsStore.state.value.assistantBackgroundEvents) return
        if (sbn == null || sbn.packageName == packageName) return
        // TODO(v0.8): EventNormalizer -> dedupHash -> checkpoint -> SQLCipher memory.
        // Deliberately no logging of title/text/people here.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action: removal is not an event we need for catch-up summaries.
    }
}
