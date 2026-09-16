package dev.kage.manager.ui

import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.kage.manager.BuildConfig
import dev.kage.manager.R
import dev.kage.manager.core.Singleton

/** Layar "Tentang": versi, lisensi, tautan dokumentasi, dan atribusi. */
class AboutActivity : AppCompatActivity() {

    private val repo = "https://github.com/xykalnotkel/kage"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<TextView>(R.id.about_version).text =
            buildString {
                append("versi ").append(BuildConfig.VERSION_NAME)
                append(" (build ").append(BuildConfig.VERSION_CODE).append(")")
                append(" · ").append(BuildConfig.APPLICATION_ID)
            }

        findViewById<TextView>(R.id.about_build).text = buildString {
            append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Server: ").append(
                Singleton.transport?.let { "${it.kind} (${if (it.isAlive()) "hidup" else "mati"})" } ?: "belum terhubung",
            ).append('\n')
            append("Dibangun dengan Kotlin, Java, AndroidX, dan Material Components. Tidak ada data yang dikirim ke server mana pun: semuanya berjalan lokal di perangkat.")
        }

        fun open(url: String) = Singleton.openUrl(url)

        findViewById<MaterialButton>(R.id.btn_repo).setOnClickListener { open(repo) }
        findViewById<MaterialButton>(R.id.btn_releases).setOnClickListener { open("$repo/releases") }
        findViewById<MaterialButton>(R.id.btn_guide).setOnClickListener { open("$repo/blob/main/docs/PANDUAN.md") }
        findViewById<MaterialButton>(R.id.btn_arch).setOnClickListener { open("$repo/blob/main/docs/ARSITEKTUR.md") }
        findViewById<MaterialButton>(R.id.btn_license_file).setOnClickListener { open("$repo/blob/main/LICENSE") }
        findViewById<MaterialButton>(R.id.btn_lib_guide).setOnClickListener { open("$repo/blob/main/docs/DIPAKAI-APP-LAIN.md") }
    }
}
