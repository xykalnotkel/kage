package dev.kage.manager.core

import android.os.Bundle
import dev.kage.common.Protocol
import dev.kage.provider.Kage
import dev.kage.provider.KageServiceProxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binder based transport: what makes Kage feel like Shizuku - instant calls and live output
 * straight from the server process.
 */
class BinderTransport(private val proxy: KageServiceProxy) : Transport {

    override val kind = "binder"

    override fun delegate(): Any = proxy

    override fun isAlive(): Boolean = proxy.ping()

    override fun status(): ServerStatus? {
        return try {
            val b = proxy.status ?: return null
            val push = b.getBundle("push")
            ServerStatus(
                version = b.getInt("version"),
                uid = b.getInt("uid"),
                pid = b.getInt("pid"),
                root = b.getBoolean("root"),
                mode = b.getString("mode") ?: "?",
                sdk = b.getInt("sdk"),
                release = b.getString("release") ?: "?",
                abi = b.getString("abi") ?: "?",
                startedAt = b.getLong("startedAt"),
                uptime = b.getLong("uptime"),
                hasContext = b.getBoolean("hasContext"),
                grants = b.getInt("grants"),
                debug = b.getBoolean("debug"),
                pushViaContext = push?.let { b2 -> b2.getInt("viaContext") } ?: 0,
                pushViaHidden = push?.let { b2 -> b2.getInt("viaHidden") } ?: 0,
                pushFailures = push?.let { b2 -> b2.getInt("failures") } ?: 0,
                pushError = push?.getString("lastError"),
                extra = b,
            )
        } catch (t: Throwable) {
            Log.w("BinderTransport", "status failed: $t")
            null
        }
    }

    override fun exec(
        command: String,
        dir: String?,
        env: List<String>?,
        onOutput: (String) -> Unit,
        onFinish: (Int) -> Unit,
    ): ExecHandle? {
        return try {
            val process = proxy.newProcess(
                arrayOf("/system/bin/sh", "-c", command),
                env?.toTypedArray(),
                dir,
            ) ?: return null

            val handle = ExecHandle(System.nanoTime())
            handle.onCancel {
                try {
                    process.destroy()
                } catch (t: Throwable) {
                    Log.w("BinderTransport", "cancel failed: $t")
                }
            }

            val stdout = process.inputStream
            val stderr = process.errorStream

            Thread({
                stdout?.let { stream ->
                    val buffer = ByteArray(4096)
                    try {
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            onOutput(String(buffer, 0, read, Charsets.UTF_8))
                        }
                    } catch (t: Throwable) {
                        Log.d("BinderTransport", "stdout ended: $t")
                    } finally {
                        try { stream.close() } catch (_: Throwable) {}
                    }
                }
            }, "kage-stdout").start()

            Thread({
                stderr?.let { stream ->
                    val buffer = ByteArray(4096)
                    try {
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            onOutput(String(buffer, 0, read, Charsets.UTF_8))
                        }
                    } catch (t: Throwable) {
                        Log.d("BinderTransport", "stderr ended: $t")
                    } finally {
                        try { stream.close() } catch (_: Throwable) {}
                    }
                }
                // both streams are closed at that point, so the exit code is available
                val code = try {
                    process.waitFor()
                } catch (t: Throwable) {
                    -1
                }
                handle.exitCode = code
                handle.finished = true
                onFinish(code)
            }, "kage-wait").start()

            handle
        } catch (t: Throwable) {
            Log.w("BinderTransport", "exec failed: $t")
            null
        }
    }

    override fun execSync(command: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        val output = StringBuilder()
        val handle = exec(command, null, null, { output.append(it) }, { latch.countDown() })
        if (handle == null) return ""
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return output.toString()
    }

    override fun killAll() {
        try {
            proxy.forceStopPackage("") // ignored, the server filters empty input
        } catch (_: Throwable) {
        }
    }

    override fun grants(): List<GrantEntry> {
        return try {
            (proxy.grantedPackages ?: emptyArray()).mapNotNull { pkg ->
                if (pkg.isBlank()) null else GrantEntry(pkg, -1)
            }
        } catch (t: Throwable) {
            Log.w("BinderTransport", "grants failed: $t")
            emptyList()
        }
    }

    override fun syncGrants(grants: List<GrantEntry>, push: Boolean) {
        try {
            val list = grants.map {
                Bundle().apply {
                    putString("package", it.pkg)
                    putInt("uid", it.uid)
                }
            }
            proxy.syncGrants(managerUid, list, push)
        } catch (t: Throwable) {
            Log.w("BinderTransport", "syncGrants failed: $t")
        }
    }

    override fun requestPush(pkg: String): Boolean {
        return try {
            proxy.pushToPackage(pkg)
        } catch (t: Throwable) {
            Log.w("BinderTransport", "push failed: $t")
            false
        }
    }

    override fun transact(service: String, code: Int, data: ByteArray): ByteArray? {
        return try {
            proxy.transact(service, code, data)
        } catch (t: Throwable) {
            Log.w("BinderTransport", "transact failed: $t")
            null
        }
    }

    override fun resolve(iface: String, method: String): Bundle? {
        return try {
            proxy.resolveTransaction(iface, method)
        } catch (t: Throwable) {
            Log.w("BinderTransport", "resolve failed: $t")
            null
        }
    }

    override fun shutdown(): Boolean {
        return try {
            proxy.exit()
            true
        } catch (t: Throwable) {
            Log.w("BinderTransport", "shutdown failed: $t")
            false
        }
    }

    private val managerUid: Int
        get() = Kage.context()?.applicationInfo?.uid ?: -1

    companion object {
        /** The binder is only useful when the manager itself is allowed to call it. */
        fun available(): Boolean = Kage.pingBinder() && Kage.checkRemotePermission(Kage.context())

        fun make(): BinderTransport? {
            val proxy = Kage.service() ?: return null
            return if (proxy.ping()) BinderTransport(proxy) else null
        }

        val DESCRIPTOR = Protocol.BINDER_DESCRIPTOR
    }
}
