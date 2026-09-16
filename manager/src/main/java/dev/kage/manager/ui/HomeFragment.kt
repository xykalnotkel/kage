package dev.kage.manager.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import dev.kage.manager.Events
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.ServerStatus
import dev.kage.manager.core.Singleton
import dev.kage.manager.core.Starter
import dev.kage.manager.core.Transport
import dev.kage.manager.data.SnippetRepository

class HomeFragment : Fragment(), Events.Listener {

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBadge: TextView
    private lateinit var statusDetail: TextView
    private lateinit var output: TextView
    private lateinit var quickCommand: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        statusDot = view.findViewById(R.id.status_dot)
        statusTitle = view.findViewById(R.id.status_title)
        statusBadge = view.findViewById(R.id.status_badge)
        statusDetail = view.findViewById(R.id.status_detail)
        output = view.findViewById(R.id.home_output)
        quickCommand = view.findViewById(R.id.quick_command)

        val swipe: SwipeRefreshLayout = view.findViewById(R.id.swipe)
        swipe.setOnRefreshListener {
            refresh()
            swipe.isRefreshing = false
        }

        view.findViewById<MaterialButton>(R.id.btn_setup).setOnClickListener {
            startActivity(Intent(requireContext(), SetupActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btn_stop).setOnClickListener {
            stopServer()
        }
        view.findViewById<MaterialButton>(R.id.btn_copy_command).setOnClickListener {
            copy(quickCommand.text.toString())
        }
        view.findViewById<MaterialButton>(R.id.btn_guide).setOnClickListener {
            startActivity(Intent(requireContext(), SetupActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btn_env).setOnClickListener {
            runBlocking("id; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; uname -a")
        }
        view.findViewById<MaterialButton>(R.id.btn_sync).setOnClickListener {
            Thread {
                App.permissionsSync()
                activity?.runOnUiThread { output.text = "Izin disinkronkan ke server." }
            }.start()
        }
        view.findViewById<MaterialButton>(R.id.btn_diag).setOnClickListener { diagnose() }
        view.findViewById<MaterialButton>(R.id.btn_logs).setOnClickListener {
            startActivity(Intent(requireContext(), LogsActivity::class.java))
        }

        quickCommand.text = Singleton.paths?.let { Starter.adbCommand(it) } ?: "-"
        return view
    }

    override fun onResume() {
        super.onResume()
        Events.register(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Events.unregister(this)
    }

    override fun onServerStateChanged() {
        activity?.runOnUiThread { refresh() }
    }

    private fun refresh() {
        Thread {
            val transport = Singleton.transportOrNull(forceFile = prefs().getBoolean("file_only", false))
            val status = transport?.status()
            activity?.runOnUiThread { render(transport, status) }
        }.start()
    }

    private fun render(transport: Transport?, status: ServerStatus?) {
        if (!isAdded) return
        val running = transport?.isAlive() == true && status != null
        val color = if (running) R.color.kage_ok else R.color.kage_error
        statusDot.setBackgroundResource(R.drawable.bg_status_dot)
        statusDot.background.setTint(resources.getColor(color, null))
        statusTitle.setText(if (running) R.string.server_running else R.string.server_stopped)
        statusBadge.text = when {
            !running -> "-"
            status == null -> "-"
            status.root -> "root"
            status.uid == 2000 -> "adb shell"
            else -> status.mode
        }
        statusDetail.text = if (running && status != null) {
            buildString {
                append("uid ${status.uid} · pid ${status.pid} · ${status.mode}\n")
                append("Android ${status.release} (API ${status.sdk}) · ${status.abi}\n")
                append("uptime ${formatUptime(status.uptime)} · ${status.grants} izin terdaftar\n")
                append("transport: ${transport?.kind} · context server: ${if (status.hasContext) "ada" else "tidak"}\n")
                if (status.pushFailures > 0) append("push binder gagal ${status.pushFailures}x: ${status.pushError ?: "-"}\n")
                append("lib Kage v${status.version}")
            }
        } else {
            "Server belum jalan. Buka \"Nyalakan server\" untuk melihat perintah ADB/root yang harus dijalankan sekali."
        }
        quickCommand.text = Singleton.paths?.let { Starter.adbCommand(it) } ?: "-"
    }

    private fun stopServer() {
        Thread {
            val transport = Singleton.transportOrNull()
            val ok = transport?.shutdown() == true
            activity?.runOnUiThread {
                output.text = if (ok) "Server dihentikan." else "Tidak bisa menghentikan server (mungkin sudah mati)."
                refresh()
            }
        }.start()
    }

    private fun diagnose() {
        Thread {
            val transport = Singleton.transportOrNull()
            val sb = StringBuilder()
            sb.append("status file: ").append(Starter.isAlive(requireContext())).append('\n')
            sb.append("transport: ").append(transport?.kind ?: "none").append('\n')
            sb.append("binder ping: ").append(dev.kage.provider.Kage.pingBinder()).append('\n')
            val status = transport?.status()
            if (status != null) {
                sb.append("uid=").append(status.uid).append(" mode=").append(status.mode).append('\n')
                sb.append("hasContext=").append(status.hasContext).append('\n')
                sb.append("push viaContext=").append(status.pushViaContext)
                    .append(" viaHidden=").append(status.pushViaHidden)
                    .append(" gagal=").append(status.pushFailures).append('\n')
                sb.append("lastError=").append(status.pushError ?: "-").append('\n')
            }
            sb.append("paths=").append(Singleton.paths?.script).append('\n')
            sb.append("token=").append(Singleton.token().take(6)).append("…\n")
            val snippet = SnippetRepository.get()
            sb.append("snippets=").append(snippet.all().size).append('\n')
            activity?.runOnUiThread { output.text = sb.toString() }
        }.start()
    }

    private fun runBlocking(command: String) {
        Thread {
            val transport = Singleton.transportOrNull()
            if (transport == null) {
                activity?.runOnUiThread { output.text = "Server belum jalan." }
                return@Thread
            }
            val text = transport.execSync(command, 20000)
            activity?.runOnUiThread { output.text = text.ifBlank { "(tidak ada output)" } }
        }.start()
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("kage", text))
        android.widget.Toast.makeText(requireContext(), R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = requireContext().getSharedPreferences("kage", android.content.Context.MODE_PRIVATE)

    private fun formatUptime(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}j ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private object App {
        fun permissionsSync() {
            dev.kage.manager.App.permissions.syncToServer(push = true)
            Log.i("Home", "grants synced")
        }
    }
}
