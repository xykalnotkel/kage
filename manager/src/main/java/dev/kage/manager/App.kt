package dev.kage.manager

import android.app.Application
import android.os.Build
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.data.KageDatabase
import dev.kage.manager.data.PermissionRepository
import dev.kage.provider.Kage

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("App", "Kage manager starting on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        Singleton.init(this)

        database = KageDatabase(this)
        permissions = PermissionRepository(this, database)

        // the server pushes its binder as soon as it sees our process
        Kage.addBinderReceivedListener {
            Log.i("App", "binder received from server")
            Singleton.binderReceived = true
            Singleton.refreshTransport()
            runCatching { permissions.syncToServer(push = false) }
            Events.binderChanged()
        }
        Kage.addBinderDeadListener {
            Log.w("App", "binder died")
            Singleton.binderReceived = false
            Events.binderChanged()
        }

        // a client that just started asks the manager for the binder -> forward to the server
        dev.kage.provider.KageProvider.setRequestBinderHandler { pkg ->
            Log.i("App", "binder requested by $pkg")
            Thread { runCatching { Singleton.push(pkg) } }.start()
        }

        Thread({
            Singleton.refreshTransport()
            runCatching { permissions.syncToServer(push = true) }
            Events.binderChanged()
        }, "kage-bootstrap").start()
    }

    companion object {
        lateinit var database: KageDatabase
            private set
        lateinit var permissions: PermissionRepository
            private set
    }
}

/** Minimal event bus so fragments can react to server state changes. */
object Events {

    interface Listener {
        fun onServerStateChanged()
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun register(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun binderChanged() {
        listeners.forEach { runCatching { it.onServerStateChanged() } }
    }
}
