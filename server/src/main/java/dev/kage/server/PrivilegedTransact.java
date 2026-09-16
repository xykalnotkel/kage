package dev.kage.server;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Lets the manager use system services with the server's identity (uid 2000 / 0).
 *
 * The client marshals the arguments into a Parcel and ships the bytes here, we replay the
 * transaction against the real service and return the reply bytes. Only Parcel content without
 * binder objects or file descriptors can be marshalled; that covers the vast majority of
 * system API calls (activity tasks, package manager queries, settings, appops, ...).
 */
public final class PrivilegedTransact {

    private PrivilegedTransact() { }

    public static IBinder service(String name) {
        try {
            Class<?> cls = Class.forName("android.os.ServiceManager");
            Method getService = cls.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            return (IBinder) getService.invoke(null, name);
        } catch (Throwable t) {
            ServerLog.w("PrivilegedTransact", "getService(" + name + ") failed: " + t);
            return null;
        }
    }

    public static byte[] transact(String serviceName, int code, byte[] data) throws Exception {
        IBinder binder = service(serviceName);
        if (binder == null) throw new IllegalStateException("service not found: " + serviceName);
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        try {
            if (data != null && data.length > 0) {
                in.unmarshall(data, 0, data.length);
                in.setDataPosition(0);
            }
            binder.transact(code, in, out, 0);
            out.setDataPosition(0);
            return out.marshall();
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    /** Resolves the interface descriptor and the transaction code of a system service method. */
    public static Bundle resolve(String interfaceName, String methodName) {
        Bundle result = new Bundle();
        try {
            Class<?> iface = Class.forName(interfaceName);
            try {
                Field descriptor = iface.getField("DESCRIPTOR");
                result.putString("descriptor", (String) descriptor.get(null));
            } catch (NoSuchFieldException ignored) {
                result.putString("descriptor", interfaceName);
            }
            Class<?> stub = Class.forName(interfaceName + "$Stub");
            Field field = null;
            try {
                field = stub.getField("TRANSACTION_" + methodName);
            } catch (NoSuchFieldException first) {
                // overloaded AIDL methods get a _0 / _1 suffix
                for (int i = 0; i < 8 && field == null; i++) {
                    try {
                        field = stub.getField("TRANSACTION_" + methodName + "_" + i);
                        result.putInt("overload", i);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            if (field == null) {
                result.putBoolean("ok", false);
                result.putString("error", "no transaction for " + methodName + " in " + interfaceName);
                return result;
            }
            result.putBoolean("ok", true);
            result.putInt("code", field.getInt(null));
        } catch (Throwable t) {
            result.putBoolean("ok", false);
            result.putString("error", String.valueOf(t));
        }
        return result;
    }
}
