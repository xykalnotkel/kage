package dev.kage.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
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

        // ---------- wizard wireless debugging ----------
        val pairInput = findViewById<EditText>(R.id.input_pair_port)
        val connectInput = findViewById<EditText>(R.id.input_connect_port)
        val wirelessPc = findViewById<TextView>(R.id.wireless_pc)
        val wirelessTermux = findViewById<TextView>(R.id.wireless_termux)

        val buildWireless: () -> Unit = {
            val pair = pairInput.text.toString().trim().ifBlank { "IP:PORT_PAIRING" }
            val connect = connectInput.text.toString().trim().ifBlank { "IP:PORT_CONNECT" }
            val start = paths?.script?.absolutePath ?: "-"
            wirelessPc.text = buildString {
                append("adb pair ").append(pair).append("      # masukkan kode 6 digit dari HP\n")
                append("adb connect ").append(connect).append("\n")
                append("adb shell sh ").append(start)
            }
            wirelessTermux.text = buildString {
                append("pkg install android-tools -y\n")
                append("adb pair ").append(pair.replace(Regex("^[0-9.]+(?=:)"), "localhost")).append("\n")
                append("adb connect ").append(connect.replace(Regex("^[0-9.]+(?=:)"), "localhost")).append("\n")
                append("adb shell sh ").append(start)
            }
        }
        buildWireless()
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                buildWireless()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        pairInput.addTextChangedListener(watcher)
        connectInput.addTextChangedListener(watcher)
        findViewById<MaterialButton>(R.id.btn_make_wireless).setOnClickListener { buildWireless() }
        findViewById<MaterialButton>(R.id.btn_copy_wireless_pc).setOnClickListener { copy(wirelessPc.text.toString()) }
        findViewById<MaterialButton>(R.id.btn_copy_wireless_termux).setOnClickListener { copy(wirelessTermux.text.toString()) }
        findViewById<MaterialButton>(R.id.btn_open_wireless_settings).setOnClickListener { openWirelessDebugging() }
        findViewById<MaterialButton>(R.id.btn_open_dev_settings).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
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

    private fun openWirelessDebugging() {
        // halaman ini tidak punya konstanta publik, jadi dicoba beberapa aksi
        val candidates = listOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        )
        for (action in candidates) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) return
        }
        runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("kage", text))
        android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}
