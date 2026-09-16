package dev.kage.manager.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class KageDatabase(context: Context) : SQLiteOpenHelper(context, "kage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE permissions (
              package TEXT PRIMARY KEY,
              uid INTEGER NOT NULL DEFAULT -1,
              label TEXT,
              granted INTEGER NOT NULL DEFAULT 0,
              requestedAt INTEGER NOT NULL DEFAULT 0,
              grantedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE history (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              command TEXT NOT NULL,
              exit INTEGER NOT NULL DEFAULT -1,
              output TEXT,
              at INTEGER NOT NULL DEFAULT 0,
              source TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE snippets (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              command TEXT NOT NULL,
              builtin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only
    }

    fun upsertPackage(pkg: String, uid: Int, label: String?) {
        val values = ContentValues().apply {
            put("package", pkg)
            put("uid", uid)
            if (label != null) put("label", label)
        }
        writableDatabase.insertWithOnConflict("permissions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setGranted(pkg: String, granted: Boolean, uid: Int = -1, label: String? = null) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("package", pkg)
            put("granted", if (granted) 1 else 0)
            if (uid > 0) put("uid", uid)
            if (label != null) put("label", label)
            if (granted) put("grantedAt", now) else put("grantedAt", 0)
        }
        val db = writableDatabase
        val updated = db.update("permissions", values, "package = ?", arrayOf(pkg))
        if (updated == 0) {
            if (!granted) put("requestedAt", now)
            db.insertWithOnConflict("permissions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun markedRequested(pkg: String, uid: Int, label: String?) {
        val values = ContentValues().apply {
            put("package", pkg)
            put("uid", uid)
            put("label", label)
            put("requestedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("permissions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun addHistory(command: String, exit: Int, output: String?, source: String) {
        val values = ContentValues().apply {
            put("command", command)
            put("exit", exit)
            put("output", output?.take(20000))
            put("at", System.currentTimeMillis())
            put("source", source)
        }
        writableDatabase.insert("history", null, values)
        writableDatabase.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 300)")
    }
}
