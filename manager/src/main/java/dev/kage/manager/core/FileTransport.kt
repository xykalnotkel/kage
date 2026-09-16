package dev.kage.manager.core

import android.os.Bundle
import dev.kage.common.Crypto
import dev.kage.common.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Fallback transport: signed request/response files in the directory the server and the manager
 * share. Slower than Binder (polling) but needs nothing from the framework, which makes it the
 * safety net for devices where the binder push is not possible.
 *
 * Requests are authenticated with a token that the manager generates and hands to the server on
 * its command line, so another app that can read the shared directory still cannot inject work.
 */
class FileTransport(
    private val sharedDir: File,
    private val token: String,
    private val managerUid: Int,
) : Transport {

    override val kind = "file"

    private val requests = File(sharedDir, Protocol.DIR_REQUESTS)
    private val responses = File(sharedDir, Protocol.DIR_RESPONSES)
    private val outputs = File(sharedDir, Protocol.DIR_OUTPUT)
    private val nextId = AtomicLong(System.currentTimeMillis() % 1_000_000 + 1)

    init {
        requests.mkdirs()
        responses.mkdirs()
        outputs.mkdirs()
    }

    override fun isAlive(): Boolean = statusFileFresh()

    private fun statusFileFresh(): Boolean {
        val file = File(sharedDir, Protocol.FILE_STATUS)
        return file.exists() && System.currentTimeMillis() - file.lastModified() < 6000
    }

    private fun send(id: Long, cmd: String, args: JSONObject) {
        val payload = args.toString()
        val signature = Crypto.hmac(token, "$id:$cmd:$payload")
        val body = """{"id":$id,"cmd":"$cmd","sig":"$signature","payload":${JSONObject.quote(payload)}}"""
        writeAtomic(File(requests, "$id.json"), body)
    }

    /** Sends a request and waits for its answer. */
    private fun call(cmd: String, args: JSONObject, timeoutMs: Long = 15000): JSONObject? {
        val id = nextId.getAndIncrement()
        send(id, cmd, args)
        val responseFile = File(responses, "$id.json")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (responseFile.exists()) {
                try {
                    val response = JSONObject(responseFile.readText())
                    val ok = response.optBoolean("ok", false)
                    val payload = JSONObject(response.optString("payload", "{}"))
                    if (!ok && payload.has("error")) Log.w("FileTransport", "$cmd rejected: ${payload.optString("error")}")
                    return payload
                } catch (t: Throwable) {
                    Log.d("FileTransport", "unreadable response: $t")
                } finally {
                    responseFile.delete()
                }
            }
            Thread.sleep(50)
        }
        Log.w("FileTransport", "$cmd timed out after ${timeoutMs}ms")
        return null
    }

    private fun writeAtomic(file: File, body: String): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (t: Throwable) {
            Log.w("FileTransport", "write failed for ${file.name}: $t")
            false
        }
    }

    override fun status(): ServerStatus? {
        val file = File(sharedDir, Protocol.FILE_STATUS)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val push = json.optJSONObject("push")
            ServerStatus(
                version = json.optInt("version", Protocol.VERSION),
                uid = json.optInt("uid"),
                pid = json.optInt("pid"),
                root = json.optBoolean("root"),
                mode = json.optString("mode", "?"),
                sdk = json.optInt("sdk"),
                release = json.optString("release", "?"),
                abi = json.optString("abi", "?"),
                startedAt = json.optLong("startedAt"),
                uptime = json.optLong("uptime"),
                hasContext = json.optBoolean("hasContext"),
                grants = json.optJSONArray("packages")?.length() ?: 0,
                debug = json.optBoolean("debug"),
                pushViaContext = push?.optInt("viaContext") ?: 0,
                pushViaHidden = push?.optInt("viaHidden") ?: 0,
                pushFailures = push?.optInt("failures") ?: 0,
                pushError = push?.optString("lastError"),
            )
        } catch (t: Throwable) {
            Log.w("FileTransport", "status unreadable: $t")
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
        val id = nextId.getAndIncrement()
        val handle = ExecHandle(id)

        handle.onCancel {
            if (handle.serverPid > 0) {
                call(Protocol.CMD_KILL, JSONObject().put("id", handle.serverPid), 5000)
            } else {
                call(Protocol.CMD_KILL, JSONObject().put("all", true), 5000)
            }
        }

        val thread = Thread({
            val args = JSONObject().put("cmd", command)
            dir?.let { args.put("dir", it) }
            env?.let { args.put("env", JSONArray(it)) }
            send(id, Protocol.CMD_EXEC, args)

            val ackFile = File(responses, "$id.json")
            val doneFile = File(responses, "$id.done")
            val logFile = File(outputs, "$id.log")
            val errFile = File(outputs, "$id.err")
            var started = false
            var logPos = 0L
            var errPos = 0L
            val deadline = System.currentTimeMillis() + 30 * 60 * 1000

            while (System.currentTimeMillis() < deadline) {
                if (!started && ackFile.exists()) {
                    try {
                        val payload = JSONObject(JSONObject(ackFile.readText()).optString("payload", "{}"))
                        handle.serverPid = payload.optInt("pid")
                        started = true
                    } catch (t: Throwable) {
                        Log.d("FileTransport", "ack unreadable: $t")
                    } finally {
                        ackFile.delete()
                    }
                }
                if (started) {
                    logPos += drain(logFile, logPos, onOutput)
                    errPos += drain(errFile, errPos, onOutput)
                }
                if (doneFile.exists()) {
                    val code = try {
                        JSONObject(JSONObject(doneFile.readText()).optString("payload", "{}")).optInt("exit", -1)
                    } catch (t: Throwable) {
                        -1
                    } finally {
                        doneFile.delete()
                    }
                    drain(logFile, logPos, onOutput)
                    drain(errFile, errPos, onOutput)
                    handle.exitCode = code
                    handle.finished = true
                    logFile.delete()
                    errFile.delete()
                    onFinish(code)
                    return@Thread
                }
                Thread.sleep(130)
            }
            handle.finished = true
            onFinish(-1)
        }, "kage-exec-$id")
        thread.isDaemon = true
        thread.start()
        return handle
    }

    /** Returns how many bytes were forwarded. */
    private fun drain(file: File, position: Long, onOutput: (String) -> Unit): Long {
        if (!file.exists()) return 0
        var forwarded = 0L
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() <= position) return 0
                raf.seek(position)
                val size = (raf.length() - position).toInt().coerceAtMost(64 * 1024)
                val buffer = ByteArray(size)
                val read = raf.read(buffer)
                if (read > 0) {
                    onOutput(String(buffer, 0, read, Charsets.UTF_8))
                    forwarded = read.toLong()
                }
            }
        } catch (t: Throwable) {
            Log.d("FileTransport", "drain failed: $t")
        }
        return forwarded
    }

    override fun execSync(command: String, timeoutMs: Long): String {
        val response = call(Protocol.CMD_EXEC_WAIT, JSONObject().put("cmd", command), timeoutMs) ?: return ""
        val out = response.optString("stdout", "")
        val err = response.optString("stderr", "")
        return if (err.isBlank()) out else out + "\n" + err
    }

    override fun killAll() {
        call(Protocol.CMD_KILL, JSONObject().put("all", true), 5000)
    }

    override fun grants(): List<GrantEntry> {
        val response = call(Protocol.CMD_GRANTS, JSONObject(), 8000) ?: return emptyList()
        val array = response.optJSONArray("packages") ?: return emptyList()
        val list = ArrayList<GrantEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val pkg = item.optString("package")
            if (pkg.isNotBlank()) list.add(GrantEntry(pkg, item.optInt("uid", -1)))
        }
        return list
    }

    override fun syncGrants(grants: List<GrantEntry>, push: Boolean) {
        val array = JSONArray()
        grants.forEach { array.put(JSONObject().put("package", it.pkg).put("uid", it.uid)) }
        val args = JSONObject()
            .put("managerUid", managerUid)
            .put("packages", array)
            .put("push", push)
        call(Protocol.CMD_SYNC_GRANTS, args, 25000)
    }

    override fun requestPush(pkg: String): Boolean {
        val response = call(Protocol.CMD_PUSH, JSONObject().put("package", pkg), 12000)
        return response?.optBoolean("pushed", false) ?: false
    }

    override fun transact(service: String, code: Int, data: ByteArray): ByteArray? {
        val args = JSONObject()
            .put("service", service)
            .put("code", code)
            .put("data", Crypto.hex(data))
        val response = call(Protocol.CMD_TRANSACT, args, 25000) ?: return null
        return Crypto.unhex(response.optString("data", ""))
    }

    override fun resolve(iface: String, method: String): Bundle? {
        val response = call(
            Protocol.CMD_RESOLVE,
            JSONObject().put("interface", iface).put("method", method),
            12000,
        ) ?: return null
        return Bundle().apply {
            putBoolean("ok", response.optBoolean("ok"))
            putString("descriptor", response.optString("descriptor"))
            putInt("code", response.optInt("code", -1))
            putString("error", response.optString("error"))
        }
    }

    override fun shutdown(): Boolean {
        call(Protocol.CMD_SHUTDOWN, JSONObject(), 6000)
        Thread.sleep(400)
        return !statusFileFresh()
    }

    /** Removes stale request/response files, called when the manager starts. */
    fun cleanup(olderThanMs: Long = 3600_000) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        listOf(requests, responses, outputs).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }
}
