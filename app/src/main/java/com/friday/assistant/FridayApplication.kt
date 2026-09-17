package com.friday.assistant

import android.app.Application
import android.util.Log

/** Keeps application startup passive and removes stale temporary runtime cache before the HUD starts. */
class FridayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        clearStaleRuntimeCache()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences("friday_diagnostics", MODE_PRIVATE)
                    .edit()
                    .putLong("crash_time", System.currentTimeMillis())
                    .putString("crash_thread", thread.name)
                    .putString("crash_type", error.javaClass.name)
                    .putString("crash_message", error.message.orEmpty().take(1000))
                    .putString("crash_stack", Log.getStackTraceString(error).take(12000))
                    .apply()
            }
            previous?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /**
     * FRIDAY does not persist user data in cache. Removing stale cache at process start
     * prevents corrupted/transient renderer, voice, or network artifacts from surviving
     * a crash. Preferences/Keystore data are intentionally untouched.
     */
    private fun clearStaleRuntimeCache() {
        runCatching { cacheDir.deleteRecursively() }
        runCatching { externalCacheDir?.deleteRecursively() }
        runCatching {
            codeCacheDir.deleteRecursively()
        }
        // Re-create the directories Android expects after cleanup.
        runCatching { cacheDir.mkdirs() }
        runCatching { externalCacheDir?.mkdirs() }
        runCatching { codeCacheDir.mkdirs() }
    }
}
