package dev.kage.server;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.kage.common.BinderContainer;
import dev.kage.common.Json;
import dev.kage.common.Protocol;

/**
 * Hands the server Binder to client apps.
 *
 * A process that never had a Context (our server) cannot simply call into another app, so two
 * independent mechanisms are attempted:
 *
 *  1. {@link #pushViaContext}  - uses the system Context obtained through ActivityThread#systemMain
 *     and a plain ContentResolver call. Clean, no hidden AIDL glue involved.
 *  2. {@link #pushViaHiddenApi} - the Shizuku style path: resolve the ActivityManager service
 *     through reflection and ask it for the client provider, then call the provider directly.
 *
 * Whichever works first is used for the rest of the session; both are logged and their counters
 * are reported to the manager so a failure on a specific ROM is diagnosable.
 */
public final class ClientPusher {

    private static final String TAG = "ClientPusher";

    private final IBinder binder;
    private final Context context;
    private final int ownUid = android.os.Process.myUid();

    private final Map<String, Integer> pushedPid = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Long> lastAttempt = new ConcurrentHashMap<String, Long>();

    public int viaContextCount;
    public int viaHiddenCount;
    public int failureCount;
    public String lastError;

    private Object activityManager;
    private boolean activityManagerTried;

    private volatile boolean running = true;

    public ClientPusher(IBinder binder, Context context) {
        this.binder = binder;
        this.context = context;
    }

    public void start() {
        Thread t = new Thread("client-pusher") {
            @Override
            public void run() {
                while (running) {
                    try {
                        scan();
                    } catch (Throwable t2) {
                        ServerLog.d(TAG, "scan failed: " + t2);
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    /** Everything we know about the push state, for the manager's diagnostics screen. */
    public Bundle state() {
        Bundle b = new Bundle();
        b.putInt("viaContext", viaContextCount);
        b.putInt("viaHidden", viaHiddenCount);
        b.putInt("failures", failureCount);
        b.putString("lastError", lastError);
        b.putBoolean("contextAvailable", context != null);
        b.putStringArrayList("pushed", new ArrayList<String>(pushedPid.keySet()));
        return b;
    }

    // ------------------------------------------------------------------ scan

    private void scan() {
        List<String> targets = new ArrayList<String>();
        int manager = PermissionStore.managerUid();
        if (manager > 0) targets.add(Protocol.MANAGER_PACKAGE);
        targets.addAll(PermissionStore.packages());

        if (targets.isEmpty()) return;

        Map<String, Integer> grants = new HashMap<String, Integer>(PermissionStore.snapshot());
        if (manager > 0) grants.put(Protocol.MANAGER_PACKAGE, manager);

        Map<Integer, String> uidToPkg = new HashMap<Integer, String>();
        for (Map.Entry<String, Integer> e : grants.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) uidToPkg.put(e.getValue(), e.getKey());
        }

        File proc = new File("/proc");
        File[] children = proc.listFiles();
        if (children == null) return;

        Map<String, Integer> alivePid = new ConcurrentHashMap<String, Integer>();

        for (File dir : children) {
            String name = dir.getName();
            if (!isNumeric(name)) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == android.os.Process.myPid()) continue;

            String cmdline = readFirstArg(dir);
            if (cmdline == null || cmdline.isEmpty()) continue;

            int uid = readUid(dir);
            if (uid < 0) continue;

            String pkg = cmdline;
            int colon = pkg.indexOf(':');
            if (colon > 0) pkg = pkg.substring(0, colon);

            if (!grants.containsKey(pkg)) continue;
            if (uidToPkg.get(uid) == null) continue; // uid mismatch: app was reinstalled
            if (uid == ownUid) continue;

            alivePid.put(pkg, pid);
            Integer already = pushedPid.get(pkg);
            if (already != null && already == pid) continue;

            Long last = lastAttempt.get(pkg);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 1500) continue;
            lastAttempt.put(pkg, now);

            int userId = uid / 100000;
            if (push(pkg, userId)) {
                pushedPid.put(pkg, pid);
            }
        }

        // forget dead processes so that a restart of the app triggers a fresh push
        Set<String> dead = new HashSet<String>();
        for (Map.Entry<String, Integer> e : pushedPid.entrySet()) {
            Integer live = alivePid.get(e.getKey());
            if (live == null || !live.equals(e.getValue())) dead.add(e.getKey());
        }
        for (String pkg : dead) pushedPid.remove(pkg);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String readFirstArg(File procDir) {
        FileInputStream in = null;
        try {
            File cmdline = new File(procDir, "cmdline");
            if (!cmdline.canRead()) return null;
            in = new FileInputStream(cmdline);
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            String s = new String(buf, 0, n, Charset.forName("UTF-8"));
            int nul = s.indexOf('\0');
            return nul >= 0 ? s.substring(0, nul) : s.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static int readUid(File procDir) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File(procDir, "status"));
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return -1;
            String text = new String(buf, 0, n, Charset.forName("UTF-8"));
            for (String line : text.split("\n")) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length > 0) return Integer.parseInt(parts[0]);
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ push

    public boolean push(String pkg, int userId) {
        Bundle extras = new Bundle();
        extras.putParcelable(Protocol.EXTRA_BINDER, new BinderContainer(binder));
        extras.putString(Protocol.EXTRA_TOKEN, ServerState.get().token);

        String authority = pkg + Protocol.AUTHORITY_SUFFIX;

        if (context != null && pushViaContext(authority, extras)) {
            viaContextCount++;
            ServerLog.d(TAG, "pushed to " + pkg + " via ContentResolver");
            return true;
        }
        if (pushViaHiddenApi(pkg, authority, extras)) {
            viaHiddenCount++;
            ServerLog.d(TAG, "pushed to " + pkg + " via ActivityManager");
            return true;
        }
        failureCount++;
        ServerLog.w(TAG, "push to " + pkg + " failed: " + lastError);
        return false;
    }

    private boolean pushViaContext(String authority, Bundle extras) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle reply = resolver.call(Uri.parse("content://" + authority),
                    Protocol.METHOD_SEND_BINDER, null, extras);
            return reply != null && reply.getBoolean("ok", false);
        } catch (Throwable t) {
            lastError = "ContentResolver: " + t;
            return false;
        }
    }

    private boolean pushViaHiddenApi(String pkg, String authority, Bundle extras) {
        try {
            Object am = activityManager();
            if (am == null) return false;

            Object holder = getContentProviderExternal(am, authority, 0);
            if (holder == null) {
                lastError = "getContentProviderExternal returned null";
                return false;
            }
            Object provider = readField(holder, "provider");
            if (provider == null) {
                lastError = "holder has no provider";
                return false;
            }
            IBinder providerBinder = ((IInterface) provider).asBinder();
            if (providerBinder == null || !providerBinder.pingBinder()) {
                lastError = "provider binder is dead";
                return false;
            }
            Object reply = callProvider(provider, pkg, authority, extras);
            if (reply instanceof Bundle) {
                return ((Bundle) reply).getBoolean("ok", false);
            }
            lastError = "unexpected provider reply: " + reply;
            return false;
        } catch (Throwable t) {
            lastError = "ActivityManager: " + t;
            return false;
        }
    }

    private Object activityManager() {
        if (activityManagerTried) return activityManager;
        activityManagerTried = true;
        try {
            Class<?> cls = Class.forName("android.app.ActivityManager");
            Method getService = cls.getDeclaredMethod("getService");
            getService.setAccessible(true);
            activityManager = getService.invoke(null);
            if (activityManager != null) {
                ServerLog.d(TAG, "IActivityManager acquired: " + activityManager.getClass().getName());
            }
        } catch (Throwable t) {
            lastError = "ActivityManager.getService: " + t;
        }
        return activityManager;
    }

    /** getContentProviderExternal(name, userId, token[, tag]) - arity differs over releases. */
    private Object getContentProviderExternal(Object am, String authority, int userId) throws Exception {
        Method best = null;
        for (Method m : am.getClass().getMethods()) {
            if (!"getContentProviderExternal".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == String.class && p[2] == IBinder.class && p[3] == String.class) {
                best = m;
                break;
            }
            if (best == null && p.length == 3 && p[0] == String.class && p[2] == IBinder.class) {
                best = m;
            }
        }
        if (best == null) throw new NoSuchMethodException("getContentProviderExternal");
        Object[] args = best.getParameterTypes().length == 4
                ? new Object[]{authority, userId, null, authority}
                : new Object[]{authority, userId, null};
        return best.invoke(am, args);
    }

    private static Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** IContentProvider#call(...) - parameter lists changed in 29, 30 and 31. */
    private Object callProvider(Object provider, String pkg, String authority, Bundle extras) throws Exception {
        Object attributionSource = buildAttributionSource();
        List<Method> candidates = new ArrayList<Method>();
        for (Method m : provider.getClass().getMethods()) {
            if ("call".equals(m.getName())) candidates.add(m);
        }
        Exception last = null;
        for (Method m : candidates) {
            Class<?>[] p = m.getParameterTypes();
            try {
                Object[] args = new Object[p.length];
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == Bundle.class) {
                        args[i] = extras;
                    } else if (p[i] == String.class) {
                        args[i] = null;
                    } else if (p[i].getName().equals("android.content.AttributionSource")) {
                        if (attributionSource == null) {
                            ok = false;
                            break;
                        }
                        args[i] = attributionSource;
                    } else {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                fillStringArgs(args, p, pkg, authority);
                Object result = m.invoke(provider, args);
                ServerLog.d(TAG, "provider.call via " + describe(p));
                return result;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new NoSuchMethodException("IContentProvider.call");
    }

    private void fillStringArgs(Object[] args, Class<?>[] types, String pkg, String authority) {
        // String parameters are laid out differently per release; the method name and the
        // Bundle/AttributionSource positions tell us which layout we are looking at.
        int stringCount = 0;
        for (Class<?> t : types) {
            if (t == String.class) stringCount++;
        }
        int index = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] != String.class) continue;
            index++;
            if (index == 1) {
                args[i] = pkg;                       // calling package (or null on 31+)
            } else if (index == 2) {
                args[i] = stringCount >= 5 ? null : authority;
            } else if (index == 3) {
                args[i] = stringCount >= 5 ? authority : Protocol.METHOD_SEND_BINDER;
            } else if (index == 4) {
                args[i] = Protocol.METHOD_SEND_BINDER;
            } else {
                args[i] = index == 5 && stringCount == 6 ? null : null;
            }
        }
    }

    private static String describe(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    private Object buildAttributionSource() {
        try {
            Class<?> builderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderCls.getConstructor(int.class).newInstance(ownUid);
            builderCls.getMethod("setPackageName", String.class).invoke(builder, "dev.kage.server");
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called when a client explicitly asks for the binder (e.g. it just started). */
    public boolean pushNow(String pkg) {
        int uid = -1;
        Integer known = PermissionStore.snapshot().get(pkg);
        if (known != null) uid = known;
        if (uid < 0 && Protocol.MANAGER_PACKAGE.equals(pkg)) uid = PermissionStore.managerUid();
        int userId = uid > 0 ? uid / 100000 : 0;
        pushedPid.remove(pkg);
        return push(pkg, userId);
    }
}
