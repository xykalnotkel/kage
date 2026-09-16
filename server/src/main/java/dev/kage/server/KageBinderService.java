package dev.kage.server;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.kage.common.Crypto;
import dev.kage.common.Json;
import dev.kage.common.Protocol;

/**
 * The Binder clients talk to. Every method is guarded: the manager app is always allowed,
 * every other app needs {@link Protocol#PERMISSION} granted.
 */
public final class KageBinderService extends Binder {

    private ClientPusher pusher;

    public KageBinderService() {
    }

    public void setPusher(ClientPusher pusher) {
        this.pusher = pusher;
    }

    private static void requirePermission(String what) {
        int uid = Binder.getCallingUid();
        if (PermissionStore.isManager(uid)) return;
        if (PermissionStore.isGranted(uid)) return;
        throw new SecurityException("uid " + uid + " is not allowed to call " + what);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(Protocol.BINDER_DESCRIPTOR);
            return true;
        }
        try {
            data.enforceInterface(Protocol.BINDER_DESCRIPTOR);
        } catch (SecurityException e) {
            return super.onTransact(code, data, reply, flags);
        }

        switch (code) {
            case Protocol.TX_GET_VERSION:
                reply.writeNoException();
                reply.writeInt(Protocol.VERSION);
                return true;

            case Protocol.TX_GET_STATUS:
                reply.writeNoException();
                status().writeToParcel(reply, 0);
                return true;

            case Protocol.TX_EXIT:
                requirePermission("exit");
                reply.writeNoException();
                KageServer.stop("requested by manager");
                return true;

            case Protocol.TX_CHECK_SELF_PERMISSION: {
                int uid = Binder.getCallingUid();
                reply.writeNoException();
                reply.writeInt(PermissionStore.isManager(uid) || PermissionStore.isGranted(uid) ? 1 : 0);
                return true;
            }

            case Protocol.TX_REQUEST_PERMISSION: {
                String pkg = data.readString();
                int uid = pkg != null ? packageUid(pkg) : Binder.getCallingUid();
                reply.writeNoException();
                if (pkg == null || uid < 0) {
                    reply.writeInt(0);
                } else {
                    PermissionStore.grant(pkg, uid);
                    // a freshly granted client should get the binder without waiting
                    pusher.pushNow(pkg);
                    reply.writeInt(1);
                }
                return true;
            }

            case Protocol.TX_REVOKE_PERMISSION: {
                requirePermission("revoke");
                String pkg = data.readString();
                reply.writeNoException();
                if (pkg != null) PermissionStore.revoke(pkg);
                reply.writeInt(1);
                return true;
            }

            case Protocol.TX_GET_GRANTED_PACKAGES: {
                requirePermission("grants");
                reply.writeNoException();
                List<String> pkgs = PermissionStore.packages();
                reply.writeStringList(pkgs);
                return true;
            }

            case Protocol.TX_SYNC_GRANTS: {
                requirePermission("syncGrants");
                int managerUid = data.readInt();
                boolean push = data.readInt() == 1;
                List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
                int count = data.readInt();
                for (int i = 0; i < count; i++) {
                    Map<String, Object> m = Json.obj();
                    m.put("package", data.readString());
                    m.put("uid", data.readInt());
                    entries.add(m);
                }
                if (managerUid > 0) PermissionStore.setManagerUid(managerUid);
                PermissionStore.sync(entries, managerUid, android.os.Process.myUid());
                if (push) {
                    for (Map<String, Object> m : entries) {
                        String pkg = Json.str(m, "package");
                        if (pkg != null) pusher.pushNow(pkg);
                    }
                    pusher.pushNow(Protocol.MANAGER_PACKAGE);
                }
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }

            case Protocol.TX_LIST_USERS: {
                requirePermission("listUsers");
                List<Integer> users = users();
                reply.writeNoException();
                reply.writeInt(users.size());
                for (Integer u : users) reply.writeInt(u);
                return true;
            }

            case Protocol.TX_NEW_PROCESS: {
                requirePermission("newProcess");
                String[] cmd = data.createStringArray();
                String[] env = data.createStringArray();
                String dir = data.readString();
                reply.writeNoException();
                try {
                    int id = ShellExecutor.nextId();
                    java.lang.Process process = ShellExecutor.spawn(cmd, env, dir, id);
                    RemoteProcessImpl remote = new RemoteProcessImpl(id, process);
                    ServerLog.i("KageBinderService", "spawned #" + id + ": " + join(cmd));
                    reply.writeStrongBinder(remote);
                } catch (Throwable t) {
                    ServerLog.e("KageBinderService", t);
                    reply.writeStrongBinder(null);
                }
                return true;
            }

            case Protocol.TX_TRANSACT: {
                requirePermission("transact");
                String service = data.readString();
                int transaction = data.readInt();
                byte[] payload = data.createByteArray();
                reply.writeNoException();
                try {
                    byte[] result = PrivilegedTransact.transact(service, transaction, payload);
                    reply.writeByteArray(result);
                } catch (Throwable t) {
                    ServerLog.w("KageBinderService", "transact " + service + " failed: " + t);
                    reply.writeByteArray(null);
                }
                return true;
            }

            case Protocol.TX_RESOLVE_TRANSACTION: {
                requirePermission("resolve");
                String iface = data.readString();
                String method = data.readString();
                Bundle bundle = PrivilegedTransact.resolve(iface, method);
                reply.writeNoException();
                bundle.writeToParcel(reply, 0);
                return true;
            }

            case Protocol.TX_GET_SYSTEM_PROPERTY: {
                requirePermission("getprop");
                String key = data.readString();
                reply.writeNoException();
                reply.writeString(getProp(key));
                return true;
            }

            case Protocol.TX_SET_SYSTEM_PROPERTY: {
                requirePermission("setprop");
                String key = data.readString();
                String value = data.readString();
                ShellExecutor.Result r = ShellExecutor.run("setprop " + quote(key) + " " + quote(value), 8000);
                reply.writeNoException();
                reply.writeInt(r.code);
                return true;
            }

            case Protocol.TX_GET_BINDER_PUSH_STATE:
                reply.writeNoException();
                pusher.state().writeToParcel(reply, 0);
                return true;

            case Protocol.TX_PUSH_TO_PACKAGE: {
                requirePermission("push");
                String pkg = data.readString();
                reply.writeNoException();
                reply.writeInt(pusher != null && pkg != null && pusher.pushNow(pkg) ? 1 : 0);
                return true;
            }

            case Protocol.TX_KILL_PACKAGE_PROCESSES: {
                requirePermission("kill");
                String pkg = data.readString();
                ShellExecutor.Result r = ShellExecutor.run("am force-stop " + quote(pkg), 10000);
                reply.writeNoException();
                reply.writeInt(r.code);
                return true;
            }

            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    public Bundle status() {
        Bundle b = new Bundle();
        b.putInt("version", Protocol.VERSION);
        b.putInt("uid", android.os.Process.myUid());
        b.putInt("pid", android.os.Process.myPid());
        b.putBoolean("root", android.os.Process.myUid() == 0);
        b.putString("mode", ServerState.get().mode());
        b.putInt("sdk", Build.VERSION.SDK_INT);
        b.putString("release", Build.VERSION.RELEASE);
        b.putString("abi", ServerState.get().abi);
        b.putLong("startedAt", ServerState.get().startedAt);
        b.putLong("uptime", System.currentTimeMillis() - ServerState.get().startedAt);
        b.putBoolean("hasContext", SystemContextProvider.get() != null);
        b.putInt("grants", PermissionStore.packages().size());
        b.putBoolean("debug", ServerState.get().debug);
        return b;
    }

    static String join(String[] cmd) {
        if (cmd == null || cmd.length == 0) return "";
        if (cmd.length == 1) return cmd[0];
        StringBuilder sb = new StringBuilder();
        for (String s : cmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    static String quote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private int packageUid(String pkg) {
        android.content.Context ctx = SystemContextProvider.get();
        if (ctx != null) {
            try {
                return ctx.getPackageManager().getPackageInfo(pkg, 0).applicationInfo.uid;
            } catch (Throwable ignored) {
            }
        }
        ShellExecutor.Result r = ShellExecutor.run("pm list packages -U " + quote(pkg), 8000);
        for (String line : r.out().split("\n")) {
            int idx = line.indexOf("uid:");
            if (idx > 0) {
                try {
                    return Integer.parseInt(line.substring(idx + 4).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private List<Integer> users() {
        List<Integer> users = new ArrayList<Integer>();
        android.content.Context ctx = SystemContextProvider.get();
        if (ctx != null) {
            try {
                Object userManager = ctx.getSystemService("user");
                java.lang.reflect.Method getUsers = userManager.getClass().getMethod("getUserHandle", int.class);
                users.add(getUserHandle());
            } catch (Throwable ignored) {
            }
        }
        if (users.isEmpty()) users.add(getUserHandle());
        return users;
    }

    private static int getUserHandle() {
        try {
            java.lang.reflect.Method getMyUserId = Class.forName("android.os.UserHandle")
                    .getDeclaredMethod("getUserId", int.class);
            getMyUserId.setAccessible(true);
            return (Integer) getMyUserId.invoke(null, android.os.Process.myUid());
        } catch (Throwable t) {
            return 0;
        }
    }

    private String getProp(String key) {
        if (key == null) return null;
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = cls.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            ShellExecutor.Result r = ShellExecutor.run("getprop " + quote(key), 5000);
            return r.out();
        }
    }

    public String token() {
        return ServerState.get().token;
    }

    public static File runtimeFile(String name) {
        return new File(ServerState.get().runtimeDir, name);
    }
}
