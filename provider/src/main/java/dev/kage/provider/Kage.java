package dev.kage.provider;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.kage.common.Protocol;

/**
 * Public API of the Kage client library.
 *
 * Typical usage in a third party app:
 *
 * <pre>
 *   // AndroidManifest.xml
 *   &lt;uses-permission android:name="dev.kage.permission.API" /&gt;
 *   &lt;provider
 *       android:name="dev.kage.provider.KageProvider"
 *       android:authorities="${applicationId}.kage"
 *       android:exported="true" /&gt;
 *
 *   // code
 *   Kage.addBinderReceivedListener(() -> { ... });
 *   if (Kage.checkSelfPermission(this)) {
 *       KageRemoteProcess p = Kage.newProcess(new String[]{"id"});
 *       ...
 *   } else {
 *       Kage.requestPermission(this);
 *   }
 * </pre>
 */
public final class Kage {

    private static final String TAG = "Kage";

    public static final int PERMISSION_REQUEST_CODE = 0x4b414745;

    private static final Object LOCK = new Object();
    private static IBinder binder;
    private static Context appContext;
    private static Handler handler;
    private static boolean multiProcess;

    private static final List<BinderReceivedListener> RECEIVED = new CopyOnWriteArrayList<BinderReceivedListener>();
    private static final List<BinderDeadListener> DEAD = new CopyOnWriteArrayList<BinderDeadListener>();

    private Kage() { }

    public interface BinderReceivedListener {
        void onBinderReceived();
    }

    public interface BinderDeadListener {
        void onBinderDead();
    }

    // ------------------------------------------------------------ lifecycle

    static void attach(Context context) {
        appContext = context.getApplicationContext();
        if (handler == null) handler = new Handler(Looper.getMainLooper());
    }

    public static Context context() {
        return appContext;
    }

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "server binder died");
            synchronized (LOCK) {
                binder = null;
            }
            post(new Runnable() {
                @Override
                public void run() {
                    for (BinderDeadListener l : DEAD) {
                        try {
                            l.onBinderDead();
                        } catch (Throwable t) {
                            Log.w(TAG, "listener failed", t);
                        }
                    }
                }
            });
            // ask the manager for a fresh binder, the server may have been restarted
            KageProvider.requestBinder(appContext, 3, 800);
        }
    };

    static void onBinderReceived(IBinder received) {
        if (received == null) return;
        synchronized (LOCK) {
            if (binder == received) return;
            if (binder != null) {
                try {
                    binder.unlinkToDeath(DEATH_RECIPIENT, 0);
                } catch (Throwable ignored) { }
            }
            binder = received;
            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable t) {
                Log.w(TAG, "linkToDeath failed: " + t);
            }
        }
        post(new Runnable() {
            @Override
            public void run() {
                for (BinderReceivedListener l : RECEIVED) {
                    try {
                        l.onBinderReceived();
                    } catch (Throwable t) {
                        Log.w(TAG, "listener failed", t);
                    }
                }
            }
        });
        if (multiProcess) {
            KageProvider.broadcastToOtherProcesses(appContext);
        }
    }

    private static void post(Runnable r) {
        if (handler == null) return;
        if (Looper.myLooper() == handler.getLooper()) r.run();
        else handler.post(r);
    }

    // ------------------------------------------------------------- accessors

    public static IBinder getBinder() {
        synchronized (LOCK) {
            return binder;
        }
    }

    public static boolean pingBinder() {
        IBinder b = getBinder();
        try {
            return b != null && b.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isBinderAlive() {
        return pingBinder();
    }

    public static KageServiceProxy service() {
        IBinder b = getBinder();
        return b == null ? null : new KageServiceProxy(b);
    }

    public static void addBinderReceivedListener(BinderReceivedListener listener) {
        RECEIVED.add(listener);
    }

    public static void removeBinderReceivedListener(BinderReceivedListener listener) {
        RECEIVED.remove(listener);
    }

    public static void addBinderDeadListener(BinderDeadListener listener) {
        DEAD.add(listener);
    }

    public static void removeBinderDeadListener(BinderDeadListener listener) {
        DEAD.remove(listener);
    }

    /** Enables handing the binder to the other processes of this app (Android 12+ recommended). */
    public static void enableMultiProcessSupport(boolean enable) {
        multiProcess = enable;
    }

    // ----------------------------------------------------------- permissions

    /** Local check - works without a live binder. */
    public static boolean checkSelfPermission(Context context) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) return false;
        try {
            return ctx.getPackageManager()
                    .checkPermission(Protocol.PERMISSION, ctx.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Authoritative check, falls back to the local one when the server is not reachable. */
    public static boolean checkRemotePermission(Context context) {
        KageServiceProxy proxy = service();
        if (proxy != null && proxy.ping()) {
            try {
                return proxy.checkSelfPermission();
            } catch (Throwable ignored) {
            }
        }
        return checkSelfPermission(context);
    }

    /**
     * Opens the manager's permission dialog and reports the answer back through
     * {@link Activity#onActivityResult(int, int, Intent)} (result code RESULT_OK when granted).
     * Use this overload from an Activity when you want to know the outcome immediately.
     */
    public static void requestPermission(Activity activity, int requestCode) {
        activity.startActivityForResult(buildPermissionIntent(activity), requestCode);
    }

    /** Opens the manager's permission dialog for this app. */
    public static void requestPermission(Context context) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) throw new IllegalStateException("no context available");
        Intent intent = buildPermissionIntent(ctx);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private static Intent buildPermissionIntent(Context ctx) {
        Intent intent = new Intent();
        intent.setAction(Protocol.MANAGER_PACKAGE + ".intent.action.REQUEST_PERMISSION");
        intent.setComponent(new ComponentName(Protocol.MANAGER_PACKAGE, Protocol.MANAGER_PACKAGE + ".ui.RequestPermissionActivity"));
        intent.putExtra("package", ctx.getPackageName());
        intent.putExtra("label", appLabel(ctx));
        return intent;
    }

    /**
     * Asks the manager to make the server send its binder to this app right now, instead of
     * waiting for the server's next scan (which can take a couple of seconds).
     */
    public static void requestBinder(Context context) {
        KageProvider.requestBinderNow(context != null ? context : appContext);
    }
        Context ctx = context != null ? context : appContext;
        if (ctx == null) throw new IllegalStateException("no context available");
        Intent intent = new Intent();
        intent.setAction(Protocol.MANAGER_PACKAGE + ".intent.action.REQUEST_PERMISSION");
        intent.setComponent(new ComponentName(Protocol.MANAGER_PACKAGE, Protocol.MANAGER_PACKAGE + ".ui.RequestPermissionActivity"));
        intent.putExtra("package", ctx.getPackageName());
        intent.putExtra("label", appLabel(ctx));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private static String appLabel(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(ctx.getPackageName(), 0)));
        } catch (Throwable t) {
            return ctx.getPackageName();
        }
    }

    // ------------------------------------------------------------ operations

    public static KageRemoteProcess newProcess(String[] cmd) throws RemoteException {
        return newProcess(cmd, null, null);
    }

    public static KageRemoteProcess newProcess(String[] cmd, String[] env, String dir) throws RemoteException {
        KageServiceProxy proxy = service();
        if (proxy == null) throw new IllegalStateException("no binder, call requestPermission() first");
        return proxy.newProcess(cmd, env, dir);
    }

    public static Bundle getServerStatus() throws RemoteException {
        KageServiceProxy proxy = service();
        return proxy == null ? null : proxy.getStatus();
    }

    /** Runs a system service transaction with the server's uid. */
    public static byte[] transact(String serviceName, int transactionCode, byte[] data) throws RemoteException {
        KageServiceProxy proxy = service();
        if (proxy == null) throw new IllegalStateException("no binder");
        return proxy.transact(serviceName, transactionCode, data);
    }

    /** Resolves descriptor + transaction code of a system service method (server side reflection). */
    public static Bundle resolveTransaction(String interfaceName, String method) throws RemoteException {
        KageServiceProxy proxy = service();
        if (proxy == null) throw new IllegalStateException("no binder");
        return proxy.resolveTransaction(interfaceName, method);
    }
}
