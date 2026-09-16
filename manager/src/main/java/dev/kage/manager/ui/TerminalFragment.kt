package dev.kage.manager.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.kage.manager.App
import dev.kage.manager.R
import dev.kage.manager.core.ExecHandle
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.data.HistoryRepository
import dev.kage.manager.data.SnippetRepository

class TerminalFragment : Fragment() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var chips: LinearLayout
    private var handle: ExecHandle? = null
    private val buffer = StringBuilder()
    private var lastCommand: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        output = view.findViewById(R.id.terminal_output)
        input = view.findViewById(R.id.command_input)
        chips = view.findViewById(R.id.quick_chips)

        view.findViewById<MaterialButton>(R.id.btn_run).setOnClickListener { runInput() }
        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { cancel() }
        view.findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener { clear() }
        view.findViewById<MaterialButton>(R.id.btn_history).setOnClickListener { showHistory() }
        view.findViewById<MaterialButton>(R.id.btn_save_snippet).setOnClickListener { saveSnippet() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_RUN || actionId == EditorInfo.IME_ACTION_DONE) {
                runInput()
                true
            } else false
        }

        buildChips()
        appendLine("Kage shell - perintah dijalankan dengan uid server (shell/root).")
        appendLine("")
        return view
    }

    private fun buildChips() {
        chips.removeAllViews()
        val context = context ?: return
        SnippetRepository.get().all().take(14).forEach { snippet ->
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = snippet.name
                textSize = 12f
                minimumWidth = 0
                minimumHeight = 0
                setPadding(24, 8, 24, 8)
                setOnClickListener {
                    input.setText(snippet.command)
                    input.setSelection(snippet.command.length)
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = 8 }
            chips.addView(button, params)
        }
    }

    private fun runInput() {
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        lastCommand = command
        appendLine("$ ${prefs().getString("prompt", "shell")} $command")
        handle?.cancel()
        val transport = Singleton.transportOrNull() ?: run {
            appendLine("[!] server belum jalan")
            return
        }
        var finished = false
        handle = transport.exec(
            command = command,
            dir = null,
            env = null,
            onOutput = { chunk -> post { append(chunk) } },
            onFinish = { code ->
                finished = true
                post {
                    appendLine("")
                    appendLine("[exit $code]")
                }
                App.database.let { db ->
                    runCatching { HistoryRepository(db).add(command, code, buffer.toString().takeLast(4000)) }
                }
            },
        )
        if (handle == null) appendLine("[!] gagal menjalankan perintah")
        input.setText("")
        if (!finished) input.requestFocus()
    }

    private fun cancel() {
        handle?.cancel()
        appendLine("[dibatalkan]")
    }

    private fun clear() {
        buffer.setLength(0)
        output.text = ""
    }

    private fun saveSnippet() {
        val command = input.text.toString().trim().ifEmpty { lastCommand }
        if (command.isEmpty()) return
        val edit = EditText(requireContext()).apply { setText(command.take(24)) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Simpan snippet")
            .setView(edit)
            .setPositiveButton("Simpan") { _, _ ->
                SnippetRepository.get().save(edit.text.toString().ifBlank { command.take(16) }, command)
                buildChips()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showHistory() {
        val entries = HistoryRepository(App.database).recent(80)
        if (entries.isEmpty()) {
            appendLine("(riwayat kosong)")
            return
        }
        val labels = entries.map { "${it.command}   [exit ${it.exit}]" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Riwayat perintah")
            .setItems(labels) { _, which ->
                val entry = entries[which]
                input.setText(entry.command)
                input.setSelection(entry.command.length)
            }
            .setNeutralButton("Bersihkan") { _, _ -> HistoryRepository(App.database).clear() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun append(chunk: String) {
        buffer.append(chunk)
        if (buffer.length > 200_000) buffer.delete(0, buffer.length - 200_000)
        output.text = buffer
        scrollDown()
    }

    private fun appendLine(line: String) {
        append(line + "\n")
    }

    private fun scrollDown() {
        val scroll = output.parent as? android.widget.ScrollView
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun post(action: () -> Unit) {
        activity?.runOnUiThread { if (isAdded) action() }
    }

    private fun prefs() = requireContext().getSharedPreferences("kage", Context.MODE_PRIVATE)
}
