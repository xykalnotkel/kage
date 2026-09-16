package dev.kage.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.kage.common.Json;
import dev.kage.common.Protocol;

/**
 * Who is allowed to talk to us.
 *
 * The manager app is always allowed. Every other app has to be granted
 * {@link Protocol#PERMISSION} and its uid has to show up here.</p>
 *
 * State lives in the server runtime dir (shell/root writable) and a copy is dropped into the
 * shared dir so the manager can render it without a live connection.
 */
public final class PermissionStore {

    private static final Object LOCK = new Object();
    private static final Map<String, Integer> GRANTS = new LinkedHashMap<String, Integer>();
    private static int managerUid = -1;

    private PermissionStore() { }

    public static void load() {
        File f = new File(ServerState.get().runtimeDir, "permissions.json");
        synchronized (LOCK) {
            GRANTS.clear();
            if (!f.exists()) return;
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                in.close();
                if (read <= 0) return;
                Map<String, Object> root = Json.parseObject(new String(buf, 0, read, "UTF-8"));
                Object pkgs = root.get("packages");
                for (Object o : Json.asList(pkgs)) {
                    Map<String, Object> m = Json.asObject(o);
                    String pkg = Json.str(m, "package");
                    if (pkg != null) GRANTS.put(pkg, Json.integer(m, "uid", -1));
                }
                managerUid = Json.integer(root, "managerUid", -1);
                ServerLog.i("PermissionStore", "loaded " + GRANTS.size() + " grant(s)");
            } catch (Throwable t) {
                ServerLog.e("PermissionStore", t);
            }
        }
    }

    public static void save() {
        Map<String, Object> root = Json.obj();
        List<Object> list = new ArrayList<Object>();
        synchronized (LOCK) {
            root.put("managerUid", managerUid);
            for (Map.Entry<String, Integer> e : GRANTS.entrySet()) {
                list.add(Json.obj("package", e.getKey(), "uid", e.getValue()));
            }
        }
        root.put("packages", list);
        root.put("updated", System.currentTimeMillis());
        write(new File(ServerState.get().runtimeDir, "permissions.json"), Json.write(root));
        File shared = ServerState.get().grantsFile();
        if (shared != null) write(shared, Json.write(root));
    }

    private static void write(File f, String body) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(f, false);
            out.write(body.getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            ServerLog.w("PermissionStore", "cannot write " + f + ": " + e);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    public static void setManagerUid(int uid) {
        synchronized (LOCK) {
            managerUid = uid;
            // the manager is implicitly allowed
            GRANTS.put(Protocol.MANAGER_PACKAGE, uid);
        }
    }

    public static int managerUid() {
        synchronized (LOCK) {
            return managerUid;
        }
    }

    public static boolean isManager(int uid) {
        synchronized (LOCK) {
            return managerUid != -1 && uid == managerUid;
        }
    }

    public static void grant(String pkg, int uid) {
        synchronized (LOCK) {
            GRANTS.put(pkg, uid);
        }
        save();
    }

    public static void revoke(String pkg) {
        synchronized (LOCK) {
            GRANTS.remove(pkg);
        }
        save();
    }

    public static boolean isGranted(int uid) {
        synchronized (LOCK) {
            if (uid == managerUid) return true;
            for (Integer value : GRANTS.values()) {
                if (value != null && value == uid) return true;
            }
            return false;
        }
    }

    public static boolean isGrantedPackage(String pkg) {
        synchronized (LOCK) {
            return GRANTS.containsKey(pkg);
        }
    }

    /** Replaces the whole list with what the manager reports. */
    public static void sync(List<Map<String, Object>> entries, int managerUidValue, int ownUid) {
        synchronized (LOCK) {
            if (managerUidValue > 0) managerUid = managerUidValue;
            GRANTS.clear();
            if (managerUid > 0) GRANTS.put(Protocol.MANAGER_PACKAGE, managerUid);
            for (Map<String, Object> m : entries) {
                String pkg = Json.str(m, "package");
                int uid = Json.integer(m, "uid", -1);
                if (pkg == null || uid < 0) continue;
                if (uid == ownUid) continue;
                GRANTS.put(pkg, uid);
            }
        }
        save();
    }

    public static List<String> packages() {
        synchronized (LOCK) {
            return new ArrayList<String>(GRANTS.keySet());
        }
    }

    public static Map<String, Integer> snapshot() {
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(GRANTS));
        }
    }
}
