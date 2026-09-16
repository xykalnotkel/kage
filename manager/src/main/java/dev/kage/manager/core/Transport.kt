package dev.kage.manager.core

import android.os.Bundle

/** Status the server reports about itself. */
data class ServerStatus(
    val version: Int,
    val uid: Int,
    val pid: Int,
    val root: Boolean,
    val mode: String,
    val sdk: Int,
    val release: String,
    val startedAt: Long,
    val uptime: Long,
    val hasContext: Boolean,
    val grants: Int,
    val debug: Boolean,
    val pushViaContext: Int = 0,
    val pushViaHidden: Int = 0,
    val pushFailures: Int = 0,
    val pushError: String? = null,
    val extra: Bundle? = null,
) {
    val isShell: Boolean get() = uid == 2000
}

data class GrantEntry(val pkg: String, val uid: Int)

/** Handle of a running command. */
class ExecHandle(val id: Long) {
    @Volatile var serverPid: Int = 0
    @Volatile var exitCode: Int? = null
    @Volatile var finished: Boolean = false

    private var canceller: (() -> Unit)? = null

    fun onCancel(action: () -> Unit) { canceller = action }

    fun cancel() { canceller?.invoke() }
}

/**
 * Everything the manager needs from the server, independent of how it is reachable.
 *
 * Two implementations exist: the Binder binding (preferred, live streaming, fastest) and the
 * shared-directory binding (fallback that works even when the binder cannot be published).
 */
interface Transport {

    /** "binder" or "file" - shown in the UI. */
    val kind: String

    fun isAlive(): Boolean

    fun status(): ServerStatus?

    fun exec(
        command: String,
        dir: String? = null,
        env: List<String>? = null,
        onOutput: (String) -> Unit,
        onFinish: (Int) -> Unit,
    ): ExecHandle?

    /** Runs a command and returns its combined output (blocking, call it from a worker thread). */
    fun execSync(command: String, timeoutMs: Long = 20000): String

    fun killAll()

    fun grants(): List<GrantEntry>

    fun syncGrants(grants: List<GrantEntry>, push: Boolean)

    /** Asks the server to hand its binder to a package that just started. */
    fun requestPush(pkg: String): Boolean

    /** Runs a system service transaction with the server's privileges. */
    fun transact(service: String, code: Int, data: ByteArray): ByteArray?

    /** Resolves descriptor + transaction code of a system service method. */
    fun resolve(iface: String, method: String): Bundle?

    fun shutdown(): Boolean

    /** Binder transport exposes [dev.kage.provider.KageServiceProxy], file transport null. */
    fun delegate(): Any? = null
}
