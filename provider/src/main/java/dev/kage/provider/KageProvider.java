package dev.kage.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import dev.kage.common.BinderContainer;
import dev.kage.common.Protocol;

/**
 * Receives the server binder inside the client app.
 *
 * <pre>
 * &lt;provider
 *     android:name="dev.kage.provider.KageProvider"
 *     android:authorities="${applicationId}.kage"
 *     android:exported="true" /&gt;
 * </pre>
 *
 * Only the server (uid 2000 / 0) and the manager app may push into it; anything else is rejected
 * with the calling uid logged.
 */
public class KageProvider extends ContentProvider {

    private static final String TAG = "KageProvider";

    public static final String ACTION_BINDER = "dev.kage.intent.action.BINDER";
    public static final String EXTRA_BINDER_CONTAINER = "dev.kage.extra.BINDER";

    private static Context staticContext;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        staticContext = context;
        Kage.attach(context);
        Log.d(TAG, "created for " + (context == null ? "?" : context.getPackageName()));
        // Ask the manager for the binder right away: the server push is fast, this only makes
        // the first connection deterministic instead of "up to two seconds".
        requestBinder(context, 3, 700);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        if (Protocol.METHOD_SEND_BINDER.equals(method)) {
            int uid = Binder.getCallingUid();
            if (!isTrusted(uid)) {
                Log.w(TAG, "rejecting binder from untrusted uid " + uid);
                reply.putBoolean("ok", false);
                reply.putString("error", "untrusted sender: " + uid);
                return reply;
            }
            IBinder received = null;
            if (extras != null) {
                extras.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer container = extras.getParcelable(Protocol.EXTRA_BINDER);
                if (container != null) received = container.binder;
            }
            if (received == null) {
                // pre Android 12 the framework drops binders out of sibling process bundles,
                // in that case the caller has to use the manager side push path
                Log.w(TAG, "binder missing in extras");
                reply.putBoolean("ok", false);
                reply.putString("error", "no binder in extras");
                return reply;
            }
            Kage.onBinderReceived(received);
            reply.putBoolean("ok", true);
            return reply;
        }
        if (Protocol.METHOD_GET_BINDER.equals(method)) {
            IBinder binder = Kage.getBinder();
            if (binder != null) {
                reply.putParcelable(Protocol.EXTRA_BINDER, new BinderContainer(binder));
            }
            reply.putBoolean("ok", binder != null);
            return reply;
        }
        if ("checkPermission".equals(method)) {
            // lets the manager/native tools consult this app about its own permission state
            reply.putBoolean("granted", Kage.checkSelfPermission(getContext()));
            return reply;
        }
        if ("requestBinder".equals(method)) {
            // a sibling process of this app asking for the binder
            IBinder binder = Kage.getBinder();
            if (binder != null) reply.putParcelable(Protocol.EXTRA_BINDER, new BinderContainer(binder));
            reply.putBoolean("ok", binder != null);
            return reply;
        }
        return reply;
    }

    private boolean isTrusted(int uid) {
        if (uid == 0 || uid == 2000 || uid == android.os.Process.myUid()) return true;
        return uid == managerUid(getContext());
    }

    private static int managerUidCache = -1;

    static int managerUid(Context context) {
        if (managerUidCache > 0) return managerUidCache;
        if (context == null) return -1;
        try {
            managerUidCache = context.getPackageManager()
                    .getPackageInfo(Protocol.MANAGER_PACKAGE, 0).applicationInfo.uid;
        } catch (Throwable t) {
            managerUidCache = -1;
        }
        return managerUidCache;
    }

    /** Nudges the manager so the server pushes us the binder without waiting for its scan. */
    static void requestBinder(final Context context, final int attempts, final long delayMs) {
        if (context == null) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread("kage-request-binder") {
            @Override
            public void run() {
                for (int i = 0; i < attempts; i++) {
                    if (Kage.pingBinder()) return;
                    try {
                        Bundle extras = new Bundle();
                        extras.putString("package", context.getPackageName());
                        context.getContentResolver().call(
                                Uri.parse("content://" + Protocol.MANAGER_PACKAGE + ".provider"),
                                "requestBinder", context.getPackageName(), extras);
                    } catch (Throwable t2) {
                        Log.d(TAG, "requestBinder failed: " + t2);
                    }
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** Hands the binder to the other processes of this app. */
    static void broadcastToOtherProcesses(Context context) {
        if (context == null) return;
        IBinder binder = Kage.getBinder();
        if (binder == null) return;
        Intent intent = new Intent(ACTION_BINDER);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_BINDER_CONTAINER, new BinderContainer(binder));
        context.sendBroadcast(intent);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
