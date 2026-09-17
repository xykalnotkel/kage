package dev.kage.manager.wireless

import android.content.Context
import dev.kage.manager.Events
import dev.kage.manager.R
import dev.kage.manager.adb.AdbClient
import dev.kage.manager.adb.AdbKeyManager
import dev.kage.manager.adb.PairingClient
import dev.kage.manager.adb.PairingPeerInfo
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.core.Starter
import java.io.IOException
import java.net.NoRouteToHostException

/**
 * The whole wireless flow running natively inside Kage - no PC, no Termux:
 *
 *  1. `adb pair`  - PairingClient against the mDNS-discovered pairing port;
 *  2. `adb connect` + `adb shell sh <start.sh>` - AdbClient against the connect port;
 *  3. verify the privileged server came up (heartbeat) and refresh transports.
 *
 * Everything is blocking and meant to run on a worker thread (see [start]).
 */
object NativePairing {

    private const val TAG = "NativePairing"

    @Volatile
    private var current: Thread? = null

    fun isRunning(): Boolean = current?.isAlive == true

    fun cancel() {
        current?.interrupt()
    }

    /**
     * Async entry point used by SetupActivity. Progress and result are posted back on the
     * main thread; the result notification is posted by [WirelessPairing].
     */
    fun start(
        context: Context,
        pair: String?,
        connect: String?,
        code: String,
        scriptPath: String?,
        onStage: (String) -> Unit,
        onDone: (ok: Boolean, message: String) -> Unit,
    ): Boolean {
        if (isRunning()) return false
        val appContext = context.applicationContext
        val thread = Thread {
            val result = runBlocking(appContext, pair, connect, code, scriptPath, onStage)
            runCatching { onDone(result.first, result.second) }
        }.apply { name = "kage-native-pairing" }
        current = thread
        thread.start()
        return true
    }

    /** Blocking variant used by PairingCodeReceiver inside its own goAsync thread. */
    fun runBlocking(
        context: Context,
        pair: String?,
        connect: String?,
        code: String,
        scriptPath: String?,
        onStage: (String) -> Unit,
    ): Pair<Boolean, String> {
        val appContext = context.applicationContext
        return try {
            if (pair != null) {
                onStage(appContext.getString(R.string.wireless_native_stage_pair, pair))
                val key = AdbKeyManager.get(appContext)
                val (host, port) = splitHostPort(pair)
                val client = PairingClient(host, port, code.toByteArray(Charsets.US_ASCII), key)
                val peer = client.run()
                Log.i(TAG, "pairing sukses, peer type=${peer.type}")
                if (peer.type == PairingPeerInfo.TYPE_DEVICE_GUID) {
                    Log.i(TAG, "device guid: ${peer.asCString()}")
                }
            }

            if (connect != null) {
                onStage(appContext.getString(R.string.wireless_native_stage_connect, connect))
                val key = AdbKeyManager.get(appContext)
                val (host, port) = splitHostPort(connect)
                val script = scriptPath
                val command = if (script != null) "sh $script" else "echo kage-connect-ok"
                val adb = AdbClient(key)
                val out = StringBuilder()
                adb.shell(host, port, command, { chunk ->
                    val text = String(chunk, Charsets.UTF_8)
                    synchronized(out) { out.append(text) }
                })
                Log.i(TAG, "shell selesai, output: ${out.takeLast(400)}")
            } else {
                return true to appContext.getString(R.string.wireless_native_ok_paired)
            }

            onStage(appContext.getString(R.string.wireless_native_stage_check))
            Thread.sleep(1500)
            val alive = runCatching { Starter.isAlive(appContext) }.getOrDefault(false)
            if (alive) {
                runCatching { Singleton.refreshTransport() }
                runCatching { Events.binderChanged() }
                true to appContext.getString(R.string.wireless_native_ok_running)
            } else {
                false to appContext.getString(R.string.wireless_native_not_running)
            }
        } catch (e: InterruptedException) {
            false to appContext.getString(R.string.wireless_native_cancelled)
        } catch (e: Exception) {
            Log.w(TAG, "native pairing gagal: ${e.javaClass.simpleName}: ${e.message}")
            false to describeFailure(appContext, e)
        }
    }

    private fun describeFailure(context: Context, e: Exception): String = when {
        e.message?.contains("pairing header", ignoreCase = true) == true ||
            e.message?.contains("decrypt", ignoreCase = true) == true ||
            e.message?.contains("spake2", ignoreCase = true) == true ||
            e.message?.contains("not a curve point", ignoreCase = true) == true ->
            context.getString(R.string.wireless_native_fail_code)

        e is NoRouteToHostException || e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ||
            e.message?.contains("Connection refused", ignoreCase = true) == true ->
            context.getString(R.string.wireless_native_fail_refused)

        e is IOException && e.message?.contains("timeout", ignoreCase = true) == true ->
            context.getString(R.string.wireless_native_fail_timeout)

        else -> context.getString(R.string.wireless_native_fail_generic, e.message ?: e.javaClass.simpleName)
    }

    /** "192.168.1.9:41234" / "[::1]:41234" / "localhost:41234" -> Pair(host, port). */
    fun splitHostPort(value: String): Pair<String, Int> {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 0 && trimmed.length > end + 2 && trimmed[end + 1] == ':') {
                return trimmed.substring(1, end) to trimmed.substring(end + 2).toInt()
            }
        }
        val idx = trimmed.lastIndexOf(':')
        if (idx <= 0 || idx == trimmed.length - 1) throw IOException("alamat tidak valid: $trimmed")
        return trimmed.substring(0, idx) to trimmed.substring(idx + 1).toInt()
    }
}
