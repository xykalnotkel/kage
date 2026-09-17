package dev.kage.manager.adb

import android.content.Context
import dev.kage.manager.core.Log
import java.io.File

/**
 * Holds the process-wide [AdbKey]. The key persists in the app's private dir so the pairing
 * survives restarts - exactly like ~/.android/adbkey does on a workstation.
 */
object AdbKeyManager {

    private const val TAG = "AdbKey"
    private const val DIR = "adbkey"

    @Volatile
    private var cached: AdbKey? = null

    fun get(context: Context): AdbKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val dir = File(context.applicationInfo.dataDir, DIR)
            val key = AdbKey.loadOrCreate(dir)
            Log.i(TAG, "adb key siap: ${key.publicKeyLine.take(48)}…")
            cached = key
            return key
        }
    }
}
