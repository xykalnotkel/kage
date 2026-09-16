package dev.kage.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.kage.manager.R
import dev.kage.manager.core.Singleton
import dev.kage.manager.core.Starter

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val paths = Singleton.paths
        val token = Singleton.token()
        val command = paths?.let { Starter.adbCommand(it) } ?: "-"
        val rootCommand = paths?.let { Starter.rootCommand(it) } ?: "-"
        val manual = paths?.let { Starter.manualCommand(it, token) } ?: "-"
        val onDevice = paths?.let { Starter.onDeviceCommand(it, token) } ?: "-"

        findViewById<TextView>(R.id.setup_command).text = command
        findViewById<TextView>(R.id.setup_ondevice).text = onDevice
        findViewById<TextView>(R.id.setup_paths).text = buildString {
            append("Folder bersama: ").append(paths?.sharedDir?.absolutePath ?: "-").append('\n')
            append("Script: ").append(paths?.script?.absolutePath ?: "-").append('\n')
            append("Dex server: ").append(paths?.dex?.absolutePath ?: "-").append('\n')
            append("Runtime: ").append(paths?.runtimeDir ?: "-").append('\n')
            append("Token tersimpan di app (8 karakter awal): ").append(token.take(8)).append('…')
        }

        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener { copy(command) }
        findViewById<MaterialButton>(R.id.btn_copy_root).setOnClickListener { copy(rootCommand) }
        findViewById<MaterialButton>(R.id.btn_copy_manual).setOnClickListener { copy(manual) }
        findViewById<MaterialButton>(R.id.btn_copy_ond).setOnClickListener { copy(onDevice) }
        findViewById<MaterialButton>(R.id.btn_pair_wireless).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        }
        findViewById<MaterialButton>(R.id.btn_check).setOnClickListener {
            Thread {
                val transport = Singleton.transportOrNull()
                val status = transport?.status()
                runOnUiThread {
                    findViewById<TextView>(R.id.setup_intro).text = when {
                        status != null && transport != null ->
                            "Server JALAN (${transport.kind}) - uid ${status.uid} (${status.mode}), pid ${status.pid}. " +
                                "Semua fitur siap dipakai."
                        Starter.isAlive(this) -> "Server menjawab tapi Binder belum masuk ke app ini."
                        else -> "Server belum jalan. Jalankan perintah di atas lewat ADB/root."
                    }
                }
            }.start()
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("kage", text))
        android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}
