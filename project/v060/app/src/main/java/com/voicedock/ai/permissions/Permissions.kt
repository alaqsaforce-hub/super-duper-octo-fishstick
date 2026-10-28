package com.voicedock.ai.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voicedock.ai.insertion.InsertAccessibilityService

/** Read-only permission status + intents that open the right system screen (always user-initiated). */
object Permissions {
    fun mic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun accessibility(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, InsertAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** OS-level notification access (separate AppSettings flag controls whether we USE it). */
    fun notificationListener(ctx: Context): Boolean {
        val me = ComponentName(ctx, com.voicedock.ai.events.VoiceDockNotificationListener::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.equals(me.flattenToString(), ignoreCase = true) }
    }
    fun contacts(ctx: Context): Boolean = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun notificationListenerSettings() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun overlaySettings(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun appSettings(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
}
