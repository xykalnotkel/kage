package dev.kage.manager.core

import android.content.Context
import dev.kage.common.Crypto
import dev.kage.common.Protocol
import java.io.File

/**
 * Everything about getting the privileged side running.
 *
 * The server is a dex executed with app_process, so the app itself cannot start it (starting it
 * needs adb or root). What the app can do is make that one command as short and as stable as
 * possible: it extracts the dex and writes a start script whose contents never change between
 * runs, so the user only ever copies one line once.
 */
object Starter {

    const val NICE_NAME = "kage_server"
    const val MAIN_CLASS = "dev.kage.server.KageServer"

    data class Paths(
        val sharedDir: File,
        val dex: File,
        val script: File,
        val runtimeDir: String,
    )

    /** Copies the server dex out of the assets and rewrites the start script. */
    fun prepare(context: Context, token: String, debug: Boolean = false, logLevel: String = "info"): Paths {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        val shared = File(external, "kage")
        shared.mkdirs()
        File(shared, Protocol.DIR_REQUESTS).mkdirs()
        File(shared, Protocol.DIR_RESPONSES).mkdirs()
        File(shared, Protocol.DIR_OUTPUT).mkdirs()

        val dex = File(shared, "server.dex")
        writeIfChanged(dex, context.assets.open("server.dex").readBytes())

        val runtime = Protocol.RUNTIME_DIR
        val script = File(shared, Protocol.START_SCRIPT)
        writeIfChanged(script, scriptBody(context.packageName, dex.absolutePath, shared.absolutePath, runtime, token, debug, logLevel).toByteArray())

        return Paths(shared, dex, script, runtime)
    }

    /**
     * The one-liner the user has to run through adb. Deliberately references the script file so
     * that reinstalling the app (new dex, new token) does not invalidate what they already copied
     * into their terminal history.
     */
    fun adbCommand(paths: Paths): String = "adb shell sh ${paths.script.absolutePath}"

    /** Alternative for a rooted device. */
    fun rootCommand(paths: Paths): String = "su -c \"sh ${paths.script.absolutePath}\""

    /** Direct command, useful when the script cannot be read (custom ROMs, secondary users). */
    fun manualCommand(paths: Paths, token: String): String =
        "adb shell \"CLASSPATH=${paths.dex.absolutePath} app_process /system/bin --nice-name=$NICE_NAME $MAIN_CLASS " +
            "--token=$token --shared=${paths.sharedDir.absolutePath} --runtime=${paths.runtimeDir}\""

    /** Same as [manualCommand] but usable from a terminal emulator app on the device itself. */
    fun onDeviceCommand(paths: Paths, token: String): String =
        "CLASSPATH=${paths.dex.absolutePath} /system/bin/app_process /system/bin --nice-name=$NICE_NAME $MAIN_CLASS " +
            "--token=$token --shared=${paths.sharedDir.absolutePath} --runtime=${paths.runtimeDir}"

    private fun scriptBody(
        packageName: String,
        dexPath: String,
        shared: String,
        runtime: String,
        token: String,
        debug: Boolean,
        logLevel: String,
    ): String {
        // Ditulis apa adanya supaya gampang dibaca manusia; "~" adalah placeholder untuk "$"
        // sehingga tidak perlu escaping template Kotlin di setiap baris shell.
        val raw = """
            #!/system/bin/sh
            # Kage start script - dibuat otomatis oleh app Kage. Jangan diedit manual.
            #
            # Jalankan dari PC:
            #   adb shell sh ~SCRIPT
            # Dari HP (root):
            #   su -c "sh ~SCRIPT"

            SHARED="~SHARED"
            RUNTIME="~RUNTIME"
            DEX_SRC="~DEXPATH"
            LOG="~SHARED/~SERVER_LOG"
            REQ="~SHARED/~REQ_DIR"
            RES="~SHARED/~RES_DIR"
            OUT="~SHARED/~OUT_DIR"
            TOKEN="~TOKEN"
            PACKAGE="~PACKAGE"
            LOG_LEVEL="~LOG_LEVEL"
            DEBUG_FLAG="~DEBUG_FLAG"
            MAIN_CLASS="~MAIN_CLASS"
            NICE_NAME="~NICE_NAME"

            if [ ! -f "~DEX_SRC" ]; then
              echo "Kage: dex server tidak ditemukan di ~DEX_SRC"
              echo "Kage: buka app Kage sekali supaya dex diekstrak, lalu jalankan script ini lagi."
              exit 1
            fi

            mkdir -p "~RUNTIME" "~REQ" "~RES" "~OUT"

            # hentikan instance lama kalau masih hidup
            if [ -f "~RUNTIME/server.pid" ]; then
              OLD=$(cat "~RUNTIME/server.pid")
              if [ -n "~OLD" ] && kill -0 "~OLD" 2>/dev/null; then
                echo "Kage: menghentikan server lama (pid ~OLD)"
                kill "~OLD" 2>/dev/null
                sleep 1
              fi
            fi

            SDK=$(getprop ro.build.version.sdk)
            case "~SDK" in
              ''|*[!0-9]*) SDK=0 ;;
            esac

            DEX="~DEX_SRC"
            if [ "~SDK" -ge 34 ]; then
              # Android 14+ menolak memuat dex yang masih writable. /data/local/tmp adalah
              # filesystem sungguhan sehingga chmod di sana pasti berhasil.
              if cp -f "~DEX_SRC" "~RUNTIME/server.dex" 2>/dev/null; then
                :
              else
                cat "~DEX_SRC" > "~RUNTIME/server.dex"
              fi
              chmod 400 "~RUNTIME/server.dex" 2>/dev/null
              if [ -w "~RUNTIME/server.dex" ]; then
                echo "Kage: peringatan - dex masih writable, Android 14+ bisa menolak menjalankannya"
                echo "Kage: jalankan script ini sebagai root kalau android menolak start."
              fi
              DEX="~RUNTIME/server.dex"
            fi

            UID_NOW=$(id -u)
            echo "Kage: menjalankan server sebagai uid ~UID_NOW ..."

            if command -v setsid >/dev/null 2>&1; then
              setsid /system/bin/app_process \
                -Djava.class.path="~DEX" \
                /system/bin \
                --nice-name="~NICE_NAME" \
                "~MAIN_CLASS" \
                --token="~TOKEN" \
                --shared="~SHARED" \
                --runtime="~RUNTIME" \
                --log="~LOG_LEVEL" "~DEBUG_FLAG" \
                < /dev/null > "~LOG" 2>&1 &
            else
              nohup /system/bin/app_process \
                -Djava.class.path="~DEX" \
                /system/bin \
                --nice-name="~NICE_NAME" \
                "~MAIN_CLASS" \
                --token="~TOKEN" \
                --shared="~SHARED" \
                --runtime="~RUNTIME" \
                --log="~LOG_LEVEL" "~DEBUG_FLAG" \
                < /dev/null > "~LOG" 2>&1 &
            fi

            PID=$!
            echo "~PID" > "~RUNTIME/server.pid"
            sleep 2

            if kill -0 "~PID" 2>/dev/null; then
              echo "Kage: server JALAN (pid ~PID, uid ~UID_NOW)"
              echo "Kage: log: ~LOG"
              echo "Kage: buka app Kage -> Log -> tab Server untuk melihat log server."
              echo "---- 8 baris terakhir log ----"
              tail -n 8 "~LOG" 2>/dev/null
              echo "------------------------------"
              echo "Kage: aman menutup terminal ini, server tetap jalan sampai HP restart."
            else
              echo "Kage: server GAGAL start. 30 baris terakhir log:"
              tail -n 30 "~LOG" 2>/dev/null
              exit 1
            fi
        """.trimIndent()

        return raw
            .replace("~SCRIPT", "$shared/" + Protocol.START_SCRIPT)
            .replace("~SHARED", shared)
            .replace("~RUNTIME", runtime)
            .replace("~DEXPATH", dexPath)
            .replace("~SERVER_LOG", Protocol.FILE_SERVER_LOG)
            .replace("~REQ_DIR", Protocol.DIR_REQUESTS)
            .replace("~RES_DIR", Protocol.DIR_RESPONSES)
            .replace("~OUT_DIR", Protocol.DIR_OUTPUT)
            .replace("~TOKEN", token)
            .replace("~PACKAGE", packageName)
            .replace("~LOG_LEVEL", logLevel)
            .replace("~DEBUG_FLAG", if (debug) "--debug" else "")
            .replace("~MAIN_CLASS", MAIN_CLASS)
            .replace("~NICE_NAME", NICE_NAME)
            .replace("~DEX_SRC", "\$DEX_SRC")
            .replace("~PID", "\$PID")
            .replace("~OLD", "\$OLD")
            .replace("~SDK", "\$SDK")
            .replace("~UID_NOW", "\$UID_NOW")
            .replace("~DEX", "\$DEX")
            .replace("~", "\$")
    }

    private fun writeIfChanged(file: File, bytes: ByteArray) {
        if (file.exists() && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return
        file.writeBytes(bytes)
        runCatching { file.setExecutable(true, false) }
    }

    fun isAlive(context: Context): Boolean {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        val status = File(File(external, "kage"), Protocol.FILE_STATUS)
        return status.exists() && System.currentTimeMillis() - status.lastModified() < 6000
    }

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences("kage", Context.MODE_PRIVATE)
        val stored = prefs.getString("token", null)
        if (stored != null && stored.length == 64) return stored
        val token = Crypto.randomToken(32)
        prefs.edit().putString("token", token).apply()
        return token
    }

    fun serverLog(context: Context): String {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        val log = File(File(external, "kage"), Protocol.FILE_SERVER_LOG)
        return if (log.exists()) log.readText().takeLast(20000) else ""
    }
}
