package dev.kage.manager.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.kage.manager.BuildConfig
import dev.kage.manager.R
import dev.kage.manager.core.Singleton
import dev.kage.manager.core.Starter

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        val prefs = requireContext().getSharedPreferences("kage", Context.MODE_PRIVATE)

        val debug = view.findViewById<MaterialSwitch>(R.id.switch_debug)
        val hint = view.findViewById<MaterialSwitch>(R.id.switch_autostart_hint)
        val fileOnly = view.findViewById<MaterialSwitch>(R.id.switch_file_transport)

        debug.isChecked = prefs.getBoolean("debug", false)
        hint.isChecked = prefs.getBoolean("show_hint", true)
        fileOnly.isChecked = prefs.getBoolean("file_only", false)

        debug.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("debug", checked).apply()
            Singleton.paths?.let { Starter.prepare(requireContext(), Singleton.token(), checked, if (checked) "debug" else "info") }
            toast("Debug ${if (checked) "aktif" else "nonaktif"} - restart server agar berlaku")
        }
        hint.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_hint", checked).apply()
        }
        fileOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("file_only", checked).apply()
            Thread { Singleton.refreshTransport(forceFile = checked) }.start()
        }

        view.findViewById<MaterialButton>(R.id.btn_about).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), AboutActivity::class.java))
        }

        view.findViewById<MaterialButton>(R.id.btn_regenerate).setOnClickListener {
            prefs.edit().remove("token").apply()
            Starter.token(requireContext())
            Singleton.init(requireContext())
            toast("Token & script dibuat ulang - jalankan lagi perintah start")
            render(view.findViewById(R.id.settings_info))
        }

        render(view.findViewById(R.id.settings_info))
        return view
    }

    private fun render(info: TextView) {
        val paths = Singleton.paths
        info.text = buildString {
            append("Kage v").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · API ").append(Build.VERSION.SDK_INT).append('\n')
            append("transport: ").append(Singleton.transport?.kind ?: "-").append('\n')
            append("token: ").append(Singleton.token().take(8)).append("…\n")
            append("folder bersama: ").append(paths?.sharedDir?.absolutePath ?: "-").append('\n')
            append("dex server: ").append(paths?.dex?.absolutePath ?: "-").append('\n')
            append("runtime: ").append(paths?.runtimeDir ?: "-").append('\n')
            append("\nPerintah start:\n")
            append(paths?.let { Starter.adbCommand(it) } ?: "-")
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }
}
