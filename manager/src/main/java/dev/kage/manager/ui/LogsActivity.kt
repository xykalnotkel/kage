package dev.kage.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.Starter

class LogsActivity : AppCompatActivity() {

    private var showingServer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        findViewById<MaterialButton>(R.id.tab_app_log).setOnClickListener {
            showingServer = false
            render()
        }
        findViewById<MaterialButton>(R.id.tab_server_log).setOnClickListener {
            showingServer = true
            render()
        }
        findViewById<MaterialButton>(R.id.btn_clear_log).setOnClickListener {
            if (showingServer) {
                android.widget.Toast.makeText(this, "Log server ada di server.log, tidak bisa dihapus dari sini", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Log.clear()
                render()
            }
        }
        findViewById<MaterialButton>(R.id.btn_copy_log).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("kage-log", (findViewById<TextView>(R.id.log_text)).text))
            android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        val text: String = if (showingServer) {
            val log = Starter.serverLog(this)
            log.ifBlank { "(log server kosong - server belum pernah jalan)" }
        } else {
            Log.dump().ifBlank { "(belum ada log)" }
        }
        findViewById<TextView>(R.id.log_text).text = text
    }
}
