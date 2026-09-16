package dev.kage.manager.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton
import dev.kage.manager.data.HistoryRepository
import dev.kage.manager.App

class ToolsFragment : Fragment() {

    private lateinit var output: TextView
    private lateinit var adapter: ToolAdapter
    private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_tools, container, false)
        output = view.findViewById(R.id.tools_output)
        adapter = ToolAdapter(Tools.catalog(), ::invoke)
        view.findViewById<RecyclerView>(R.id.tools_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ToolsFragment.adapter
        }
        view.findViewById<SwipeRefreshLayout>(R.id.tools_swipe).setOnRefreshListener {
            output.text = ""
            it.isRefreshing = false
        }
        return view
    }

    private fun invoke(action: ToolAction, view: View?) {
        val context = context ?: return
        action.intent?.let {
            runCatching { startActivity(it(context)) }
                .onFailure { error -> toast("Tidak bisa membuka halaman: $error") }
            return
        }
        val command = action.command ?: return
        if (action.needsArg) {
            val input = EditText(context).apply { hint = action.argHint }
            MaterialAlertDialogBuilder(context)
                .setTitle(action.title)
                .setMessage(action.argHint)
                .setView(input)
                .setPositiveButton("Jalankan") { _, _ ->
                    val arg = input.text.toString().trim()
                    if (arg.isNotEmpty()) run(format(command, arg), action)
                }
                .setNegativeButton("Batal", null)
                .show()
        } else {
            run(command, action)
        }
    }

    /** Fills every "%s" with the same argument. */
    private fun format(command: String, arg: String): String {
        val quoted = "'" + arg.replace("'", "'\\''") + "'"
        return command.replace("%s", quoted)
    }

    private fun run(command: String, action: ToolAction) {
        if (busy) {
            toast("Masih ada perintah yang jalan")
            return
        }
        val transport = Singleton.transportOrNull()
        if (transport == null) {
            output.text = "Server belum jalan. Buka Beranda → Nyalakan server."
            return
        }
        busy = true
        output.text = "$ ${action.title}\n"
        Log.i("Tools", "run: $command")
        Thread({
            val text = transport.execSync(command, 60000)
            val exit = 0
            runCatching { HistoryRepository(App.database).add(command, exit, text, "tools") }
            activity?.runOnUiThread {
                output.append(if (text.isBlank()) "(tanpa output)\n" else text)
                busy = false
            }
        }, "tool-${action.id}").start()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private class ToolAdapter(
        private val actions: List<ToolAction>,
        private val onRun: (ToolAction, View?) -> Unit,
    ) : RecyclerView.Adapter<ToolAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tool, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(actions[position], onRun)
        }

        override fun getItemCount(): Int = actions.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.tool_title)
            private val desc: TextView = itemView.findViewById(R.id.tool_desc)
            private val button: MaterialButton = itemView.findViewById(R.id.tool_button)

            fun bind(action: ToolAction, onRun: (ToolAction, View?) -> Unit) {
                title.text = action.title
                desc.text = action.description
                button.text = if (action.command == null) "Buka" else "Jalankan"
                button.setOnClickListener { onRun(action, it) }
                itemView.setOnClickListener { onRun(action, it) }
            }
        }
    }
}
