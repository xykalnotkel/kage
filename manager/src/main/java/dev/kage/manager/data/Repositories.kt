package dev.kage.manager.data

import android.content.Context
import dev.kage.manager.core.Singleton

/** Command history for the terminal. */
class HistoryRepository(private val db: KageDatabase) {

    data class Entry(val id: Long, val command: String, val exit: Int, val at: Long, val source: String?)

    fun add(command: String, exit: Int, output: String?, source: String = "terminal") {
        db.addHistory(command, exit, output, source)
    }

    fun recent(limit: Int = 100): List<Entry> {
        val list = ArrayList<Entry>()
        db.readableDatabase.rawQuery(
            "SELECT id, command, exit, at, source FROM history ORDER BY id DESC LIMIT $limit",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Entry(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4)))
            }
        }
        return list
    }

    fun clear() {
        db.writableDatabase.execSQL("DELETE FROM history")
    }
}

/** Canned commands shown as chips in the terminal and the tools screen. */
class SnippetRepository(private val context: Context, private val db: KageDatabase) {

    data class Snippet(val id: Long, val name: String, val command: String, val builtin: Boolean)

    fun builtins(): List<Snippet> = listOf(
        Snippet(1, "id", "id", true),
        Snippet(2, "getprop", "getprop | head -40", true),
        Snippet(3, "Top processes", "top -n 1 -b -o %CPU,RES,CMDLINE | head -20", true),
        Snippet(4, "Battery", "dumpsys battery", true),
        Snippet(5, "CPU info", "dumpsys cpuinfo | head -25", true),
        Snippet(6, "Running services", "dumpsys activity services | head -40", true),
        Snippet(7, "Doze whitelist", "dumpsys deviceidle whitelist", true),
        Snippet(8, "Screen size", "wm size; wm density", true),
        Snippet(9, "Apps using net", "dumpsys netstats | grep -i uid | head -20", true),
        Snippet(10, "Storage", "df -h /data /storage/emulated/0", true),
    )

    fun custom(): List<Snippet> {
        val list = ArrayList<Snippet>()
        db.readableDatabase.rawQuery(
            "SELECT id, name, command, builtin FROM snippets ORDER BY id DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Snippet(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1))
            }
        }
        return list
    }

    fun save(name: String, command: String) {
        val values = android.content.ContentValues().apply {
            put("name", name)
            put("command", command)
            put("builtin", 0)
        }
        db.writableDatabase.insert("snippets", null, values)
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("snippets", "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<Snippet> = builtins() + custom()

    companion object {
        @Volatile private var instance: SnippetRepository? = null

        fun get(): SnippetRepository {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                if (instance == null) {
                    val context = Singleton.context()
                    instance = SnippetRepository(context, KageDatabase(context))
                }
                return instance!!
            }
        }
    }
}
