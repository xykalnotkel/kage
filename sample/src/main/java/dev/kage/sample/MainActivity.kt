package dev.kage.sample

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.kage.common.Protocol
import dev.kage.provider.Kage
import dev.kage.provider.KageRemoteProcess

/**
 * App contoh: memakai library Kage seperti app pihak ketiga mana pun.
 *
 * Tidak ada satu pun baris di sini yang punya hak istimewa; semuanya lewat server Kage yang
 * sudah dijalankan lewat adb/root, dan hanya bekerja kalau pengguna mengizinkannya di manager.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var output: TextView
    private lateinit var command: EditText

    private val permissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        output = findViewById(R.id.output)
        command = findViewById(R.id.command)

        Kage.addBinderReceivedListener {
            log("Binder diterima dari server Kage ✓")
            refresh()
        }
        Kage.addBinderDeadListener {
            log("Binder mati - server berhenti atau di-restart")
            refresh()
        }

        findViewById<MaterialButton>(R.id.btn_permission).setOnClickListener {
            if (Kage.checkSelfPermission(this)) {
                log("Izin sudah ada.")
                refresh()
            } else {
                Kage.requestPermission(this, permissionRequest)
            }
        }
        findViewById<MaterialButton>(R.id.btn_binder).setOnClickListener {
            Kage.requestBinder(this)
            log("Minta binder ke manager...")
            Thread {
                repeat(10) {
                    if (Kage.pingBinder()) return@Thread
                    Thread.sleep(400)
                }
            }.start()
        }
        findViewById<MaterialButton>(R.id.btn_run).setOnClickListener { runCommand() }
        findViewById<MaterialButton>(R.id.btn_status).setOnClickListener { showServerStatus() }

        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == permissionRequest) {
            val granted = data?.getBooleanExtra("granted", false) ?: (resultCode == RESULT_OK)
            log(if (granted) "Izin DIBERIKAN oleh user ✓" else "Izin ditolak")
            if (granted) Kage.requestBinder(this)
            refresh()
        }
    }

    private fun refresh() {
        val granted = Kage.checkSelfPermission(this)
        val binder = Kage.pingBinder()
        status.text = when {
            !granted -> "Belum ada izin"
            !binder -> "Izin ada, menunggu Binder"
            else -> "Siap dipakai (binder hidup)"
        }
        detail.text = buildString {
            append("izin ").append(Protocol.PERMISSION).append(": ")
            append(if (granted) "diberikan" else "belum").append('\n')
            append("binder: ").append(if (binder) "hidup" else "belum ada").append('\n')
            append("provider: ").append(packageName).append(Protocol.AUTHORITY_SUFFIX)
        }
    }

    private fun runCommand() {
        if (!Kage.pingBinder()) {
            log("Binder belum ada. Tekan tombol 1 & 2 dulu, atau tunggu server push (~2 detik).")
            return
        }
        val cmd = command.text.toString().trim()
        if (cmd.isEmpty()) return
        log("$ $cmd")
        try {
            val process: KageRemoteProcess? = Kage.newProcess(arrayOf("/system/bin/sh", "-c", cmd))
            if (process == null) {
                log("Server menolak membuat proses (izin?)")
                return
            }
            Thread({
                val stdout = process.inputStream
                val stderr = process.errorStream
                val buffer = ByteArray(4096)
                while (true) {
                    val read = stdout?.read(buffer) ?: -1
                    if (read <= 0) break
                    val chunk = String(buffer, 0, read)
                    runOnUiThread { output.append(chunk) }
                }
                val code = process.waitFor()
                runOnUiThread { log("[exit $code]") }
            }, "sample-read").start()
        } catch (t: Throwable) {
            log("Gagal: $t")
        }
    }

    private fun showServerStatus() {
        try {
            val bundle = Kage.getServerStatus()
            if (bundle == null) {
                log("Belum ada binder, tidak bisa membaca status.")
                return
            }
            log(
                buildString {
                    append("uid=").append(bundle.getInt("uid"))
                    append(" mode=").append(bundle.getString("mode"))
                    append(" pid=").append(bundle.getInt("pid"))
                    append("\nAndroid ").append(bundle.getString("release"))
                    append(" (API ").append(bundle.getInt("sdk")).append(')')
                    append("\nuptime=").append(bundle.getLong("uptime") / 1000).append("s")
                    append(" · izin terdaftar=").append(bundle.getInt("grants"))
                },
            )
        } catch (t: RemoteException) {
            log("RemoteException: $t")
        }
    }

    private fun log(text: String) {
        output.append(text + "\n")
    }
}
