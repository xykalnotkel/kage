package dev.kage.manager.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kage.common.Protocol
import dev.kage.provider.Kage

/**
 * Process wide state: which transport is usable right now, the shared paths, the token.
 */
object Singleton {

    private lateinit var appContext: Context

    @Volatile var transport: Transport? = null
        private set

    @Volatile var paths: Starter.Paths? = null
        private set

    @Volatile var binderReceived = false

    fun init(context: Context) {
        appContext = context.applicationContext
        val token = Starter.token(appContext)
        val prepared = Starter.prepare(appContext, token)
        paths = prepared
        runCatching { FileTransport(prepared.sharedDir, token, managerUid()).cleanup() }
        Log.i("Singleton", "paths ready: ${prepared.script}")
    }

    fun context(): Context = appContext

    fun token(): String = Starter.token(appContext)

    fun managerUid(): Int = appContext.applicationInfo.uid

    fun sharedDir() = paths?.sharedDir ?: appContext.getExternalFilesDir(null)!!

    /** Picks the best transport that currently works, preferring Binder. */
    fun refreshTransport(forceFile: Boolean = false): Transport? {
        if (!forceFile) {
            val binderTransport = BinderTransport.make()
            if (binderTransport != null && binderTransport.isAlive()) {
                transport = binderTransport
                Log.i("Singleton", "transport: binder")
                return binderTransport
            }
        }
        val file = FileTransport(sharedDir(), token(), managerUid())
        transport = if (file.isAlive()) {
            Log.i("Singleton", "transport: file")
            file
        } else {
            Log.w("Singleton", "no transport available")
            null
        }
        return transport
    }

    fun transportOrNull(forceFile: Boolean = false): Transport? {
        val current = transport
        if (!forceFile && current is BinderTransport && current.isAlive()) return current
        if (forceFile && current is FileTransport && current.isAlive()) return current
        return refreshTransport(forceFile)
    }

    val isBinder: Boolean get() = transport is BinderTransport

    val isRunning: Boolean get() = transport?.isAlive() == true

    // ------------------------------------------------------------- permissions

    /** true when the manager itself may call the server. */
    fun hasPermission(): Boolean = Kage.checkRemotePermission(appContext)

    fun requestPermissionForSelf() {
        Kage.requestPermission(appContext)
    }

    /** Asks the server to hand its binder to a client app that just came up. */
    fun push(pkg: String) {
        transportOrNull()?.requestPush(pkg)
    }

    fun openUrl(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    const val API_PERMISSION = Protocol.PERMISSION
}
