package dev.kage.server;

import android.content.Context;

import java.lang.reflect.Method;

/**
 * A "real" Android Context inside an app_process server gives us PackageManager, ContentResolver,
 * UserManager, ... without hand written hidden AIDL glue. It is obtained through
 * ActivityThread#systemMain(), exactly the same trick the platform command line tools rely on.
 */
public final class SystemContextProvider {

    private static boolean tried;
    private static Context context;

    private SystemContextProvider() { }

    public static synchronized Context get() {
        if (!tried) {
            tried = true;
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method systemMain = activityThread.getDeclaredMethod("systemMain");
                systemMain.setAccessible(true);
                Object thread = systemMain.invoke(null);
                Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                context = (Context) getSystemContext.invoke(thread);
                if (context == null) {
                    ServerLog.w("SystemContext", "systemMain() returned no system context");
                } else {
                    ServerLog.i("SystemContext", "system context ready: " + context.getPackageName());
                }
            } catch (Throwable t) {
                ServerLog.w("SystemContext", "unavailable: " + t);
            }
        }
        return context;
    }
}
