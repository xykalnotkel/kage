package dev.kage.manager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.kage.manager.core.Singleton

/** One shell action exposed by the tools screen. */
data class ToolAction(
    val id: String,
    val title: String,
    val description: String,
    val command: String?,
    val needsArg: Boolean = false,
    val argHint: String? = null,
    val intent: ((Context) -> Intent)? = null,
)

/**
 * The toolbox: everything here runs through the server with shell/root rights, which is exactly
 * what makes them useful for debloating, permission fixing and inspecting the system.
 */
object Tools {

    private const val PM_LIST = "pm list packages -3 | sed 's/package://' | sort"

    fun catalog(): List<ToolAction> = listOf(
        ToolAction(
            "device", "Info device",
            "Model, Android, kernel, ABI, uptime",
            "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; " +
                "getprop ro.build.version.sdk; getprop ro.product.cpu.abi; uname -a; uptime",
        ),
        ToolAction(
            "battery", "Baterai",
            "Status, level, suhu, voltase, health",
            "dumpsys battery",
        ),
        ToolAction(
            "net", "Jaringan",
            "Konektivitas, wifi, data, IP",
            "dumpsys connectivity | head -60; ip addr show wlan0 2>/dev/null | grep inet",
        ),
        ToolAction(
            "mem", "Memori",
            "Total/pakai RAM dan swap",
            "cat /proc/meminfo | head -12; free -h 2>/dev/null",
        ),
        ToolAction(
            "storage", "Penyimpanan",
            "Kapasitas tiap partisi penting",
            "df -h /data /system /cache /storage/emulated/0 2>/dev/null",
        ),
        ToolAction(
            "third_party", "Daftar app pihak ketiga",
            "App yang kamu install sendiri",
            PM_LIST,
        ),
        ToolAction(
            "system_apps", "Daftar app sistem",
            "Hati-hati saat mematikan salah satu",
            "pm list packages -s | sed 's/package://' | sort",
        ),
        ToolAction(
            "disabled", "App yang dimatikan",
            "Paket disabled / frozen",
            "pm list packages -d | sed 's/package://' | sort",
        ),
        ToolAction(
            "freeze", "Freeze app",
            "Matikan app (bisa di-unfreeze lagi)",
            "pm disable-user --user 0 %s; pm list packages -d | grep %s",
            needsArg = true,
            argHint = "contoh: com.example.bloatware",
        ),
        ToolAction(
            "unfreeze", "Unfreeze app",
            "Aktifkan lagi app yang di-freeze",
            "pm enable %s; pm list packages -d | grep %s",
            needsArg = true,
            argHint = "contoh: com.example.bloatware",
        ),
        ToolAction(
            "uninstall_user", "Uninstall untuk user 0",
            "Hapus app tanpa menyentuh partisi sistem (bisa dikembalikan dengan install-existing)",
            "pm uninstall --user 0 %s",
            needsArg = true,
            argHint = "contoh: com.example.bloatware",
        ),
        ToolAction(
            "restore_app", "Kembalikan app sistem",
            "Restore app yang tadi di-uninstall untuk user 0",
            "pm install-existing %s",
            needsArg = true,
            argHint = "contoh: com.example.bloatware",
        ),
        ToolAction(
            "forcestop", "Force stop app",
            "Hentikan paket sekarang",
            "am force-stop %s",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "clear_cache", "Hapus data app",
            "Menghapus data & cache paket (tidak bisa dibatalkan)",
            "pm clear %s",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "appops", "AppOps sebuah app",
            "Mode runtime ops (kamera, lokasi, dll)",
            "appops get %s | head -60",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "appops_deny", "Tolak izin AppOps",
            "Contoh: kunci kamera/lokasi sebuah app",
            "appops set %s OP_CAMERA deny; appops set %s OP_FINE_LOCATION deny; appops get %s | head -20",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "grant", "Grant izin runtime",
            "Berikan izin dangerous ke app",
            "pm grant %s android.permission.READ_CONTACTS; dumpsys package %s | grep -A2 READ_CONTACTS | head -6",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "revoke", "Revoke izin runtime",
            "Cabut izin dangerous dari app",
            "pm revoke %s android.permission.READ_CONTACTS",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "doze", "Doze whitelist (app)",
            "Kecualikan app dari optimasi baterai",
            "dumpsys deviceidle whitelist +%s",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "doze_list", "Lihat Doze whitelist",
            "Siapa saja yang dikecualikan",
            "dumpsys deviceidle whitelist",
        ),
        ToolAction(
            "wifi_adb", "Wireless debugging",
            "Buka halaman pengaturan ADB wireless",
            null,
            intent = { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        ),
        ToolAction(
            "dev_settings", "Developer options",
            "Buka halaman developer",
            null,
            intent = { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        ),
        ToolAction(
            "allperms", "Semua izin app",
            "Dump semua izin runtime paket",
            "dumpsys package %s | grep -E 'granted=|requested' | head -40",
            needsArg = true,
            argHint = "contoh: com.example.app",
        ),
        ToolAction(
            "trim", "Trim cache semua app",
            "Minta sistem membersihkan cache",
            "pm trim-caches 10G",
        ),
        ToolAction(
            "bg_kill", "Kill background processes",
            "Hentikan app yang tidak terpakai",
            "am kill-all",
        ),
        ToolAction(
            "screenrec", "Mulai screen record",
            "Rekam layar 30 detik ke /sdcard/kage-rec.mp4",
            "screenrecord --time-limit 30 /sdcard/kage-rec.mp4",
        ),
        ToolAction(
            "screenshot", "Screenshot",
            "Simpan ke /sdcard/kage-shot.png",
            "screencap -p /sdcard/kage-shot.png",
        ),
        ToolAction(
            "anim", "Matikan animasi",
            "Percepat transisi (bisa dikembalikan 1.0)",
            "settings put global window_animation_scale 0.0; settings put global transition_animation_scale 0.0; " +
                "settings put global animator_duration_scale 0.0",
        ),
        ToolAction(
            "anim_restore", "Nyalakan animasi",
            "Balikkan ke skala normal",
            "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; " +
                "settings put global animator_duration_scale 1.0",
        ),
        ToolAction(
            "dns", "DNS publik (private DNS off)",
            "Set DNS ke 1.1.1.1 lewat setprop (butuh root)",
            "setprop net.dns1 1.1.1.1; getprop net.dns1",
        ),
        ToolAction(
            "logcat", "Logcat singkat",
            "200 baris terakhir, tampilkan saja",
            "logcat -d -t 200 | tail -100",
        ),
        ToolAction(
            "props", "Semua property",
            "getprop lengkap",
            "getprop | sort | head -120",
        ),
        ToolAction(
            "open_perm_settings", "Pengaturan izin app",
            "Buka halaman App info",
            null,
            intent = { Settings.ACTION_APPLICATION_DETAILS_SETTINGS.toIntent() },
        ),
    )

    private fun String.toIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${Singleton.context().packageName}"))
}
