package dev.kage.manager.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.kage.manager.App
import dev.kage.manager.R
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton

/**
 * The dialog a client app triggers when it asks for the Kage permission.
 *
 * The manager is the only component that can call "pm grant", so this activity performs the
 * grant through the server and then syncs the list back.
 */
class RequestPermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_permission)

        val pkg = intent.getStringExtra("package")
            ?: intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
            ?: packageName
        val label = intent.getStringExtra("label") ?: runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val isSelf = pkg == packageName
        findViewById<android.widget.TextView>(R.id.req_title).text =
            if (isSelf) "Izin untuk Kage sendiri" else "Izinkan akses Kage?"
        findViewById<android.widget.TextView>(R.id.req_message).text = buildString {
            append(label).append(" (").append(pkg).append(")\n\n")
            append("Meminta izin ").append(Singleton.API_PERMISSION).append(".\n\n")
            append(
                if (isSelf) {
                    "Kage butuh izin ini untuk memakai layanan privileged-nya sendiri."
                } else {
                    "Dengan izin ini app tersebut bisa menjalankan perintah shell dengan akses ADB/root " +
                        "yang sedang aktif. Hanya berikan ke app yang kamu percaya."
                },
            )
        }

        runCatching {
            val icon = packageManager.getApplicationIcon(pkg)
            findViewById<android.widget.ImageView>(R.id.req_icon).setImageDrawable(icon)
        }.onFailure {
            findViewById<android.widget.ImageView>(R.id.req_icon).setImageResource(R.drawable.ic_apps)
        }

        findViewById<MaterialButton>(R.id.btn_allow).setOnClickListener {
            Log.i("RequestPermission", "granting to $pkg")
            Thread {
                App.permissions.markRequested(pkg)
                val ok = App.permissions.applyGrant(pkg, true)
                Singleton.push(pkg)
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra("granted", ok).putExtra("package", pkg))
                    finish()
                }
            }.start()
        }
        findViewById<MaterialButton>(R.id.btn_deny).setOnClickListener {
            setResult(RESULT_CANCELED, Intent().putExtra("granted", false).putExtra("package", pkg))
            finish()
        }
    }
}
