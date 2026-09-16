package dev.kage.manager.wireless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.ui.SetupActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wireless debugging pairing, the way Shizuku does it: the code dialog on the phone would be
 * dismissed the moment the user switches to another app (and closing it invalidates both the code
 * and the pairing port), so the code is typed into a notification instead - the notification shade
 * can be pulled down while the dialog stays open.
 *
 * The pairing port is discovered over mDNS ([SERVICE_PAIRING]) so the user does not have to read or
 * type it. Executing `adb pair` itself is handed to Termux in this build; the native adb client is
 * the next step.
 */
object WirelessPairing {

    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    const val CHANNEL_PAIRING = "kage_pairing"
    const val CHANNEL_RESULT = "kage_result"
    const val NOTIF_CODE = 4201
    const val NOTIF_RESULT = 4202

    const val KEY_CODE = "kage_pairing_code"
    const val ACTION_SUBMIT = "dev.kage.manager.action.SUBMIT_PAIRING_CODE"
    const val ACTION_TERMUX_RESULT = "dev.kage.manager.action.TERMUX_RESULT"
    const val EXTRA_PAIR = "kage_pair"
    const val EXTRA_CONNECT = "kage_connect"
    const val EXTRA_SCRIPT = "kage_script"

    /** bundle Termux sends back when a `RUN_COMMAND_PENDING_INTENT` was supplied */
    const val TERMUX_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"

    /** host:port, IPv4, IPv6 in brackets or a host name - nothing else reaches the shell */
    private val hostPortPattern = Regex("^[A-Za-z0-9._\\[\\]:-]{3,64}$")
    private val codePattern = Regex("^[0-9]{6}$")

    data class Ports(val pair: String?, val connect: String?)

    private val discoveryRunning = AtomicBoolean(false)
    private val resolved = ConcurrentHashMap<String, String>()
    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var stopCallback: Runnable? = null
    private val resolvePool = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kage-nsd").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ notifications

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PAIRING,
                context.getString(R.string.wireless_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.wireless_channel_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.wireless_channel_result_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.wireless_channel_result_desc) }
        )
    }

    /**
     * The notification that carries the inline text field. Must be expanded by the user, so the
     * big style spells out the whole flow.
     */
    fun postCodeNotification(context: Context, ports: Ports) {
        ensureChannels(context)

        val reply = Intent(context, PairingCodeReceiver::class.java).apply {
            action = ACTION_SUBMIT
            putExtra(EXTRA_PAIR, ports.pair)
            putExtra(EXTRA_CONNECT, ports.connect)
            putExtra(EXTRA_SCRIPT, Singleton.paths?.script?.absolutePath)
        }
        val replyPending = PendingIntent.getBroadcast(
            context, 100, reply,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val input = RemoteInput.Builder(KEY_CODE)
            .setLabel(context.getString(R.string.wireless_code_hint))
            .build()

        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_kage_key,
            context.getString(R.string.wireless_send_code),
            replyPending
        ).addRemoteInput(input).build()

        val open = PendingIntent.getActivity(
            context, 101,
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val body = context.getString(R.string.wireless_notif_body) + "\n" +
            "pair: " + (ports.pair ?: "-") + "\n" +
            "connect: " + (ports.connect ?: "-")

        val notification = NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_kage_key)
            .setContentTitle(context.getString(R.string.wireless_notif_title))
            .setContentText(context.getString(R.string.wireless_notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_CODE, notification) }
            .onFailure { Log.w("Wireless", "notify gagal: ${it.message}") }
    }

    fun postResultNotification(context: Context, ok: Boolean, message: String) {
        ensureChannels(context)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_CODE) }
        val open = PendingIntent.getActivity(
            context, 101,
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_kage_key)
            .setContentTitle(
                context.getString(if (ok) R.string.wireless_result_ok else R.string.wireless_result_fail)
            )
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_RESULT, notification) }
    }

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    // ------------------------------------------------------------------ discovery

    /**
     * Adb advertises both services over mDNS while wireless debugging is on. Best effort: when the
     * ROM blocks local network access for background apps nothing is found and the user simply
     * types the ports by hand, exactly like before.
     */
    fun discover(context: Context, timeoutMs: Long = 30000L, onUpdate: (Ports) -> Unit) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        if (discoveryRunning.getAndSet(true)) {
            publish(onUpdate)
            return
        }
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i("Wireless", "mDNS mulai: $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                resolvePool.execute { resolveAndPublish(manager, service, onUpdate) }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("Wireless", "mDNS gagal mulai ($serviceType): $errorCode")
                discoveryRunning.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        val started = runCatching {
            manager.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
            manager.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.getOrElse {
            Log.w("Wireless", "discoverServices gagal: ${it.message}")
            false
        }
        if (!started) {
            discoveryRunning.set(false)
            return
        }

        discoveryListener = listener
        val stop = Runnable { stopDiscovery(manager, listener) }
        stopCallback = stop
        Handler(Looper.getMainLooper()).postDelayed(stop, timeoutMs)
    }

    private fun resolveAndPublish(manager: NsdManager, service: NsdServiceInfo, onUpdate: (Ports) -> Unit) {
        val isPairing = (service.serviceType ?: "").contains("pairing")
        if (resolved.containsKey(service.serviceType ?: "")) return

        var attempt = 0
        while (attempt < 6) {
            attempt++
            val latch = CountDownLatch(1)
            var value: String? = null
            runCatching {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        latch.countDown()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null) value = "$host:${serviceInfo.port}"
                        latch.countDown()
                    }
                })
            }.onFailure { latch.countDown() }
            runCatching { latch.await(3, TimeUnit.SECONDS) }
            val found = value
            if (found != null) {
                resolved[if (isPairing) SERVICE_PAIRING else SERVICE_CONNECT] = found
                Log.i("Wireless", "mDNS ${if (isPairing) "pairing" else "connect"} = $found")
                publish(onUpdate)
                return
            }
            runCatching { Thread.sleep(400) }
        }
    }

    private fun publish(onUpdate: (Ports) -> Unit) {
        val ports = Ports(resolved[SERVICE_PAIRING], resolved[SERVICE_CONNECT])
        Handler(Looper.getMainLooper()).post { runCatching { onUpdate(ports) } }
    }

    private fun stopDiscovery(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        stopCallback = null
        discoveryListener = null
        discoveryRunning.set(false)
        runCatching { manager.stopServiceDiscovery(listener) }
    }

    fun stop() {
        val manager = nsd ?: return
        stopCallback?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        stopCallback = null
        val listener = discoveryListener
        if (listener != null) {
            discoveryListener = null
            discoveryRunning.set(false)
            runCatching { manager.stopServiceDiscovery(listener) }
        } else {
            discoveryRunning.set(false)
        }
    }

    // ------------------------------------------------------------------ termux bridge

    fun isTermuxInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun cleanHostPort(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (hostPortPattern.matches(trimmed) && trimmed.contains(':')) trimmed else null
    }

    fun cleanCode(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return if (codePattern.matches(trimmed)) trimmed else null
    }

    /** What Termux executes. Kept readable on purpose - this is what the user sees on screen. */
    fun termuxScript(pair: String?, connect: String?, script: String?, code: String?): String {
        val path = "export PATH=$TERMUX_BIN:\$PATH"
        val lines = mutableListOf(path)
        if (pair != null && code != null) {
            lines += "echo '== adb pair =='"
            lines += "adb pair $pair $code"
        }
        if (connect != null) {
            lines += "echo '== adb connect =='"
            lines += "adb connect $connect"
        }
        if (script != null) {
            lines += "echo '== start server =='"
            lines += "adb shell sh $script"
        }
        lines += "echo '== selesai: cek status di app Kage =='"
        return lines.joinToString("; ")
    }

    fun runInTermux(context: Context, script: String): Boolean {
        if (!isTermuxInstalled(context)) return false
        val result = Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_TERMUX_RESULT)
        val resultPending = PendingIntent.getBroadcast(
            context, 102, result,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            // background on purpose: a foreground session would dismiss the pairing dialog
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Kage")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "pairing + start server")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPending)
        }
        return runCatching {
            context.startService(intent)
            Log.i("Wireless", "perintah dikirim ke Termux")
            true
        }.getOrElse {
            Log.w("Wireless", "Termux menolak: ${it.message}")
            false
        }
    }

    fun termuxHint(context: Context): String = context.getString(R.string.wireless_termux_hint, TERMUX_BIN)
}
