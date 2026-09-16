package dev.kage.manager.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import dev.kage.manager.App
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.data.PermissionItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppsFragment : Fragment() {

    private lateinit var adapter: PermissionAdapter
    private var query: String = ""
    private var showSystem = false
    private var items: List<PermissionItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_apps, container, false)
        adapter = PermissionAdapter(::toggle)
        view.findViewById<RecyclerView>(R.id.apps_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }

        val search = view.findViewById<TextInputEditText>(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                apply()
            }
        })

        view.findViewById<MaterialButton>(R.id.btn_refresh_apps).setOnClickListener { load() }
        view.findViewById<MaterialButton>(R.id.btn_show_system).setOnClickListener {
            showSystem = !showSystem
            (it as MaterialButton).text = if (showSystem) "Sembunyikan app sistem" else "Tampilkan app sistem"
            apply()
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val list = App.permissions.candidates()
            activity?.runOnUiThread {
                items = list
                apply()
            }
        }.start()
    }

    private fun apply() {
        if (!isAdded) return
        val filtered = items.filter { item ->
            (showSystem || !item.system) &&
                (query.isEmpty() || item.pkg.lowercase().contains(query) || item.label.lowercase().contains(query))
        }
        adapter.submit(filtered)
        view?.findViewById<TextView>(R.id.apps_empty)?.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        view?.findViewById<View>(R.id.apps_list)?.visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggle(item: PermissionItem, granted: Boolean) {
        Log.i("Apps", "set ${item.pkg} granted=$granted")
        Thread {
            val ok = App.permissions.applyGrant(item.pkg, granted)
            activity?.runOnUiThread {
                if (!ok) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Gagal mengubah izin (server jalan?)",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                load()
            }
        }.start()
    }

    private class PermissionAdapter(val onToggle: (PermissionItem, Boolean) -> Unit) :
        RecyclerView.Adapter<PermissionAdapter.Holder>() {

        private var data: List<PermissionItem> = emptyList()

        fun submit(items: List<PermissionItem>) {
            data = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_permission, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(data[position], onToggle)
        }

        override fun getItemCount(): Int = data.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.app_icon)
            private val label: TextView = itemView.findViewById(R.id.app_label)
            private val pkg: TextView = itemView.findViewById(R.id.app_package)
            private val state: TextView = itemView.findViewById(R.id.app_state)
            private val switch: MaterialSwitch = itemView.findViewById(R.id.app_switch)

            fun bind(item: PermissionItem, onToggle: (PermissionItem, Boolean) -> Unit) {
                label.text = item.label
                pkg.text = item.pkg
                val stamp = if (item.grantedAt > 0) {
                    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(item.grantedAt))
                } else "-"
                state.text = buildString {
                    append(if (item.granted) "diizinkan" else "belum diizinkan")
                    append(" · uid ").append(item.uid)
                    append(" · sejak ").append(stamp)
                }
                runCatching {
                    val pm = itemView.context.packageManager
                    icon.setImageDrawable(pm.getApplicationIcon(item.pkg))
                }.onFailure { icon.setImageResource(R.drawable.ic_apps) }

                switch.setOnCheckedChangeListener(null)
                switch.isChecked = item.granted
                switch.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            }
        }
    }
}
