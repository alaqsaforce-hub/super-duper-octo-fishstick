package com.voicedock.ai.security

import android.content.Context
import com.voicedock.ai.settings.SettingsStore
import java.io.File
import java.lang.Thread.UncaughtExceptionHandler

/** Records a scrubbed crash report only when the user-enabled setting is on, then delegates to Android. */
class CrashLogUncaughtExceptionHandler(context: Context) : UncaughtExceptionHandler {
    private val store = CrashLogStore(File(context.applicationContext.filesDir, "crash-reports"))
    private val delegate = Thread.getDefaultUncaughtExceptionHandler()?.takeUnless { it is CrashLogUncaughtExceptionHandler }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (runCatching { SettingsStore.state.value.crashLoggingEnabled }.getOrDefault(false)) {
            runCatching { store.saveThrowable(error) }
        }
        delegate?.uncaughtException(thread, error)
    }
}
