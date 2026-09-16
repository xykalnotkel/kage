package dev.kage.server;

import java.lang.reflect.Method;

/**
 * The server runs through app_process, which normally already has unrestricted access to hidden
 * APIs. A few ROMs still apply restrictions, so this performs the classic VMRuntime exemption
 * trick. Failure is never fatal - the server simply degrades to the public API surface.
 */
public final class HiddenApiBypass {

    private HiddenApiBypass() { }

    public static void install() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            ServerLog.d("HiddenApiBypass", "VMRuntime exemptions installed");
        } catch (Throwable t) {
            ServerLog.d("HiddenApiBypass", "not needed or unavailable: " + t);
        }
    }
}
