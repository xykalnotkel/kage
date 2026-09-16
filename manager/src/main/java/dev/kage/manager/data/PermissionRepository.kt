package dev.kage.manager.data

import android.content.Context
import android.content.pm.PackageManager
import dev.kage.common.Protocol
import dev.kage.manager.core.GrantEntry
import dev.kage.manager.core.Log
import dev.kage.manager.core.Singleton

/** What a package wants / has. */
data class PermissionItem(
    val pkg: String,
    val uid: Int,
    val label: String,
    val requested: Boolean,
    val granted: Boolean,
    val system: Boolean,
    val grantedAt: Long,
)

/**
 * The manager owns the permission list: it is persisted locally (SQLite) and mirrored into the
 * server, which uses it to decide who may call it. Revoking touches three places at once - the
 * Android runtime permission, the local list and the server list.
 */
class PermissionRepository(private val context: Context, private val db: KageDatabase) {

    private val allPermission = Protocol.PERMISSION

    /** Packages that declare the Kage API permission (or already talked to us). */
    fun candidates(): List<PermissionItem> {
        val pm = context.packageManager
        val wanted = ArrayList<PermissionItem>()
        val requestedPackages = HashSet<String>()

        runCatching {
            pm.getPackagesHoldingPermissions(arrayOf(allPermission), 0).forEach { info ->
                requestedPackages.add(info.packageName)
            }
        }.onFailure { Log.d("Permissions", "getPackagesHoldingPermissions: $it") }

        val cursor = db.readableDatabase.rawQuery(
            "SELECT package, uid, label, granted, requestedAt, grantedAt FROM permissions",
            null,
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0)
                val granted = c.getInt(3) == 1
                if (granted || requestedPackages.contains(pkg)) requestedPackages.add(pkg)
            }
        }

        for (pkg in requestedPackages) {
            if (pkg == context.packageName) continue
            val uid = uidOf(pkg)
            val label = labelOf(pkg)
            val granted = grantedLocally(pkg)
            val row = readRow(pkg)
            wanted.add(
                PermissionItem(
                    pkg = pkg,
                    uid = uid,
                    label = label,
                    requested = true,
                    granted = granted || (row?.granted == true),
                    system = isSystem(pkg),
                    grantedAt = row?.grantedAt ?: 0L,
                ),
            )
        }
        return wanted.sortedBy { it.label.lowercase() }
    }

    data class Row(val granted: Boolean, val grantedAt: Long)

    private fun readRow(pkg: String): Row? {
        db.readableDatabase.rawQuery(
            "SELECT granted, grantedAt FROM permissions WHERE package = ?",
            arrayOf(pkg),
        ).use { c ->
            if (c.moveToFirst()) return Row(c.getInt(0) == 1, c.getLong(1))
        }
        return null
    }

    fun grantedPackages(): List<GrantEntry> {
        val list = ArrayList<GrantEntry>()
        db.readableDatabase.rawQuery(
            "SELECT package, uid FROM permissions WHERE granted = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                list.add(GrantEntry(c.getString(0), c.getInt(1)))
            }
        }
        return list
    }

    fun markRequested(pkg: String) {
        db.markedRequested(pkg, uidOf(pkg), labelOf(pkg))
    }

    fun setGranted(pkg: String, granted: Boolean) {
        db.setGranted(pkg, granted, uidOf(pkg), labelOf(pkg))
    }

    /**
     * Applies a grant: runtime permission through the server, local database, then the server copy.
     */
    fun applyGrant(pkg: String, granted: Boolean): Boolean {
        val transport = Singleton.transportOrNull()
        val action = if (granted) "grant" else "revoke"
        var ok = true
        if (transport != null) {
            val output = transport.execSync("pm $action ${quote(pkg)} $allPermission", 15000)
            ok = !output.contains("Exception") && !output.contains("Error")
            Log.i("Permissions", "pm $action $pkg -> $output")
        } else {
            Log.w("Permissions", "no transport, cannot run pm $action for $pkg")
            ok = false
        }
        db.setGranted(pkg, granted, uidOf(pkg), labelOf(pkg))
        syncToServer()
        return ok
    }

    /** Pushes the whole list into the server (and asks it to push binders where relevant). */
    fun syncToServer(push: Boolean = true) {
        val transport = Singleton.transportOrNull() ?: return
        val grants = grantedPackages()
        transport.syncGrants(grants, push)
        if (push) {
            grants.forEach { Singleton.push(it.pkg) }
            Singleton.push(Protocol.MANAGER_PACKAGE)
        }
    }

    fun uidOf(pkg: String): Int = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).applicationInfo.uid
    }.getOrDefault(-1)

    fun labelOf(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun isSystem(pkg: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private fun grantedLocally(pkg: String): Boolean = runCatching {
        context.packageManager.checkPermission(allPermission, pkg) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    companion object {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
