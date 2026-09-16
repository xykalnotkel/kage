package dev.kage.provider;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import dev.kage.common.Protocol;

/**
 * Client side of the server protocol. Every call is a plain Binder transaction, so this class has
 * no dependency on the server jar - only on the shared constants.
 */
public final class KageServiceProxy {

    private final IBinder remote;

    public KageServiceProxy(IBinder remote) {
        this.remote = remote;
    }

    public IBinder asBinder() {
        return remote;
    }

    public boolean ping() {
        try {
            return remote != null && remote.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public int getVersion() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_GET_VERSION, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Bundle getStatus() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_GET_STATUS, data, reply, 0);
            reply.readException();
            return reply.readBundle(KageServiceProxy.class.getClassLoader());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void exit() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_EXIT, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean checkSelfPermission() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_CHECK_SELF_PERMISSION, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Asks the server to grant the permission to a package (a dialog is shown by the manager). */
    public boolean requestPermission(String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(packageName);
            remote.transact(Protocol.TX_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void revokePermission(String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(packageName);
            remote.transact(Protocol.TX_REVOKE_PERMISSION, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public String[] getGrantedPackages() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_GET_GRANTED_PACKAGES, data, reply, 0);
            reply.readException();
            java.util.List<String> list = reply.createStringArrayList();
            return list == null ? new String[0] : list.toArray(new String[0]);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Replaces the grant list the server knows about.
     *
     * @param packages  list of Bundle entries holding "package" and "uid"
     * @param pushBinder also ask the server to (re)send its binder to those packages
     */
    public void syncGrants(int managerUid, java.util.List<Bundle> packages, boolean pushBinder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeInt(managerUid);
            data.writeInt(pushBinder ? 1 : 0);
            data.writeInt(packages == null ? 0 : packages.size());
            if (packages != null) {
                for (Bundle b : packages) {
                    data.writeString(b.getString("package"));
                    data.writeInt(b.getInt("uid"));
                }
            }
            remote.transact(Protocol.TX_SYNC_GRANTS, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int[] listUsers() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_LIST_USERS, data, reply, 0);
            reply.readException();
            int count = reply.readInt();
            int[] users = new int[count];
            for (int i = 0; i < count; i++) users[i] = reply.readInt();
            return users;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Starts a command inside the server and returns the remote process handle. */
    public KageRemoteProcess newProcess(String[] cmd, String[] env, String dir) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeStringArray(cmd);
            data.writeStringArray(env);
            data.writeString(dir);
            remote.transact(Protocol.TX_NEW_PROCESS, data, reply, 0);
            reply.readException();
            IBinder process = reply.readStrongBinder();
            return process == null ? null : new KageRemoteProcess(process);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public byte[] transact(String serviceName, int code, byte[] dataBytes) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(serviceName);
            data.writeInt(code);
            data.writeByteArray(dataBytes);
            remote.transact(Protocol.TX_TRANSACT, data, reply, 0);
            reply.readException();
            return reply.createByteArray();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Bundle resolveTransaction(String interfaceName, String method) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(interfaceName);
            data.writeString(method);
            remote.transact(Protocol.TX_RESOLVE_TRANSACTION, data, reply, 0);
            reply.readException();
            return reply.readBundle(KageServiceProxy.class.getClassLoader());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public String getSystemProperty(String key) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(key);
            remote.transact(Protocol.TX_GET_SYSTEM_PROPERTY, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int setSystemProperty(String key, String value) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(key);
            data.writeString(value);
            remote.transact(Protocol.TX_SET_SYSTEM_PROPERTY, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Tells the server to (re)send its binder to a package, e.g. because the app just started. */
    public boolean pushToPackage(String pkg) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(pkg);
            remote.transact(Protocol.TX_PUSH_TO_PACKAGE, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Bundle getPushState() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            remote.transact(Protocol.TX_GET_BINDER_PUSH_STATE, data, reply, 0);
            reply.readException();
            return reply.readBundle(KageServiceProxy.class.getClassLoader());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int forceStopPackage(String pkg) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.BINDER_DESCRIPTOR);
            data.writeString(pkg);
            remote.transact(Protocol.TX_KILL_PACKAGE_PROCESSES, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
