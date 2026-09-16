package dev.kage.server;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.io.File;
import java.util.List;

import dev.kage.common.Json;
import dev.kage.common.Protocol;

/**
 * Entry point of the privileged side, executed through
 *   CLASSPATH=server.dex app_process /system/bin --nice-name=kage_server dev.kage.server.KageServer
 *
 * It runs with the uid of whoever started it, which is exactly what makes it useful:
 * "adb shell" gives uid 2000, "su" gives uid 0.
 */
public final class KageServer {

    private static final String TAG = "KageServer";
    private static KageServer instance;

    private KageBinderService service;
    private ClientPusher pusher;
    private FileBridge bridge;
    private volatile boolean stopping;

    public static void main(String[] args) {
        ServerState state = ServerState.get();
        state.applyArgs(args);

        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        HiddenApiBypass.install();
        ServerLog.init(state.logFile());

        ServerLog.i(TAG, "Kage server " + Protocol.VERSION + " starting");
        ServerLog.i(TAG, "uid=" + state.uid + " (" + state.mode() + ") pid=" + state.pid
                + " sdk=" + state.sdk + " abi=" + state.abi + " release=" + state.release);
        ServerLog.i(TAG, "shared=" + state.sharedDir + " runtime=" + state.runtimeDir
                + " token=" + (state.token == null ? "none" : state.token.substring(0, 4) + "…"));

        try {
            File runtime = state.runtimeDir;
            if (runtime != null && !runtime.exists()) runtime.mkdirs();

            PermissionStore.load();
            resolveManagerUid();

            instance = new KageServer();
            instance.service = new KageBinderService();
            instance.pusher = new ClientPusher(instance.service, SystemContextProvider.get());
            instance.service.setPusher(instance.pusher);

            instance.pusher.start();
            instance.bridge = new FileBridge(state.token, instance.service, instance.pusher);
            instance.bridge.start();

            instance.startGrantRefresh();

            ServerLog.i(TAG, "server ready");
        } catch (Throwable t) {
            ServerLog.e(TAG, t);
            System.exit(1);
        }

        Looper.loop();
    }

    public static void stop(String reason) {
        ServerLog.i(TAG, "stopping: " + reason);
        if (instance != null) instance.shutdown();
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
        System.exit(0);
    }

    private void shutdown() {
        stopping = true;
        try {
            if (pusher != null) pusher.stop();
            if (bridge != null) bridge.stop();
        } catch (Throwable ignored) { }
        ShellExecutor.killAll();
        // mark the server as stopped for the manager UI
        try {
            File status = ServerState.get().statusFile();
            if (status != null && status.getParentFile() != null && status.getParentFile().exists()) {
                status.renameTo(new File(status.getParentFile(), "status.stopped.json"));
            }
        } catch (Throwable ignored) { }
    }

    /**
     * The manager package is identified once so that "is this the manager?" checks and the
     * binder push do not depend on the manager being alive.
     */
    private static void resolveManagerUid() {
        int uid = packageUid();
        if (uid > 0) {
            PermissionStore.setManagerUid(uid);
            ServerLog.i(TAG, "manager uid = " + uid);
        } else {
            ServerLog.w(TAG, "manager package not found yet; waiting for the app to introduce itself");
        }
    }

    private static int packageUid() {
        Context ctx = SystemContextProvider.get();
        if (ctx != null) {
            try {
                return ctx.getPackageManager().getPackageInfo(Protocol.MANAGER_PACKAGE, 0).applicationInfo.uid;
            } catch (Throwable ignored) {
            }
        }
        ShellExecutor.Result r = ShellExecutor.run("pm list packages -U " + Protocol.MANAGER_PACKAGE, 8000);
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

    /** Keeps the grant list in sync with what the package manager actually reports. */
    private void startGrantRefresh() {
        Thread t = new Thread("grant-refresh") {
            @Override
            public void run() {
                while (!stopping) {
                    try {
                        refresh();
                    } catch (Throwable t2) {
                        ServerLog.d(TAG, "grant refresh failed: " + t2);
                    }
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private void refresh() {
        Context ctx = SystemContextProvider.get();
        if (ctx == null) return;
        PackageManager pm = ctx.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS;
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(flags);
        } catch (Throwable t) {
            ServerLog.d(TAG, "getInstalledPackages failed: " + t);
            return;
        }
        boolean changed = false;
        for (PackageInfo info : packages) {
            if (info.requestedPermissions == null) continue;
            boolean wants = false;
            for (String p : info.requestedPermissions) {
                if (Protocol.PERMISSION.equals(p)) {
                    wants = true;
                    break;
                }
            }
            if (!wants) continue;
            ApplicationInfo app = info.applicationInfo;
            if (app == null) continue;
            boolean granted;
            try {
                granted = pm.checkPermission(Protocol.PERMISSION, info.packageName) == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) {
                continue;
            }
            if (granted && !PermissionStore.isGrantedPackage(info.packageName)) {
                PermissionStore.grant(info.packageName, app.uid);
                ServerLog.i(TAG, "granted via package manager: " + info.packageName);
                changed = true;
            } else if (!granted && PermissionStore.isGrantedPackage(info.packageName)
                    && !Protocol.MANAGER_PACKAGE.equals(info.packageName)) {
                PermissionStore.revoke(info.packageName);
                changed = true;
            }
        }
        if (changed) ServerLog.i(TAG, "grants updated: " + PermissionStore.packages());
    }

    public KageBinderService service() {
        return service;
    }
}
