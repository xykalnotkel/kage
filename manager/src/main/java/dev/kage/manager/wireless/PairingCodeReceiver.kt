package dev.kage.manager.wireless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.kage.manager.Events
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.core.Starter

/**
 * Receives the code the user typed into the pairing notification.
 *
 * Kage >= 1.3 pairs natively: the pairing protocol (SPAKE2 + TLS 1.3 + AES-GCM, identical to
 * adb's) runs inside this app, then the ADB client starts the server - no Termux, no PC.
 * Termux stays as a fallback for the case where the native client fails on a given ROM.
 */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            WirelessPairing.ACTION_SUBMIT -> handleCode(app, intent)
            WirelessPairing.ACTION_TERMUX_RESULT -> logTermuxResult(intent)
        }
    }

    private fun handleCode(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(WirelessPairing.KEY_CODE)
        val code = WirelessPairing.cleanCode(typed?.toString())
        val pair = WirelessPairing.cleanHostPort(intent.getStringExtra(WirelessPairing.EXTRA_PAIR))
        val connect = WirelessPairing.cleanHostPort(intent.getStringExtra(WirelessPairing.EXTRA_CONNECT))
        val script = intent.getStringExtra(WirelessPairing.EXTRA_SCRIPT)
            ?: Singleton.paths?.script?.absolutePath

        Log.i("Wireless", "kode pairing diterima: pair=$pair connect=$connect kode=${code?.length ?: 0} digit")

        if (code == null) {
            WirelessPairing.postResultNotification(
                context, false, context.getString(R.string.wireless_code_invalid)
            )
            return
        }
        if (pair == null && connect == null) {
            WirelessPairing.postResultNotification(
                context, false, context.getString(R.string.wireless_no_ports)
            )
            return
        }

        val pending = goAsync()
        Thread {
            try {
                if (pair != null) {
                    // 1) native: pair + connect + start, all inside Kage
                    val (ok, message) = NativePairing.runBlocking(
                        context, pair, connect, code, script
                    ) { stage -> Log.i("Wireless", stage) }
                    if (ok || !WirelessPairing.isTermuxInstalled(context)) {
                        WirelessPairing.postResultNotification(context, ok, message)
                        return@Thread
                    }
                    Log.w("Wireless", "native gagal, coba fallback Termux: $message")
                }

                // 2) fallback: let Termux run adb pair/connect/shell
                if (!WirelessPairing.isTermuxInstalled(context)) {
                    WirelessPairing.postResultNotification(
                        context, false, context.getString(R.string.wireless_no_termux)
                    )
                    return@Thread
                }
                val command = WirelessPairing.termuxScript(pair, connect, script, code)
                val sent = WirelessPairing.runInTermux(context, command)
                if (!sent) {
                    WirelessPairing.postResultNotification(
                        context, false, context.getString(R.string.wireless_termux_blocked)
                    )
                    return@Thread
                }
                // adb pair + connect + app_process normally takes a few seconds
                Thread.sleep(15000)
                val alive = runCatching { Starter.isAlive(context) }.getOrDefault(false)
                if (alive) {
                    runCatching { Singleton.refreshTransport() }
                    runCatching { Events.binderChanged() }
                }
                WirelessPairing.postResultNotification(
                    context,
                    alive,
                    context.getString(
                        if (alive) R.string.wireless_result_running else R.string.wireless_result_not_running
                    )
                )
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    /** Termux (0.118+) reports exit code and output back through the pending intent we sent. */
    private fun logTermuxResult(intent: Intent) {
        val bundle = intent.getBundleExtra(WirelessPairing.TERMUX_RESULT_BUNDLE) ?: return
        Log.i(
            "Wireless",
            "hasil Termux: keys=${bundle.keySet()} exit=${bundle.getInt("exitCode", Int.MIN_VALUE)}"
        )
        bundle.getString("errmsg")?.takeIf { it.isNotBlank() }
            ?.let { Log.w("Wireless", "errmsg: ${it.take(400)}") }
        bundle.getString("stderr")?.takeIf { it.isNotBlank() }
            ?.let { Log.w("Wireless", "stderr: ${it.take(400)}") }
    }
}
