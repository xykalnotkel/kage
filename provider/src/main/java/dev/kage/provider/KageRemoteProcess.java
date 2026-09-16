package dev.kage.provider;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.InputStream;
import java.io.OutputStream;

import dev.kage.common.Protocol;

/** Client handle for a command running inside the server. */
public final class KageRemoteProcess {

    private final IBinder remote;

    KageRemoteProcess(IBinder remote) {
        this.remote = remote;
    }

    public InputStream getInputStream() throws RemoteException {
        return stream(Protocol.PTX_GET_INPUT_STREAM);
    }

    public OutputStream getOutputStream() throws RemoteException {
        ParcelFileDescriptor pfd = descriptor(Protocol.PTX_GET_OUTPUT_STREAM);
        return pfd == null ? null : new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
    }

    public InputStream getErrorStream() throws RemoteException {
        return stream(Protocol.PTX_GET_ERROR_STREAM);
    }

    private InputStream stream(int code) throws RemoteException {
        ParcelFileDescriptor pfd = descriptor(code);
        return pfd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    private ParcelFileDescriptor descriptor(int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.PROCESS_DESCRIPTOR);
            remote.transact(code, data, reply, 0);
            reply.readException();
            return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean isAlive() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.PROCESS_DESCRIPTOR);
            remote.transact(Protocol.PTX_IS_ALIVE, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** @return exit code, or Integer.MIN_VALUE when the process is still running */
    public int exitValue() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.PROCESS_DESCRIPTOR);
            remote.transact(Protocol.PTX_EXIT_VALUE, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * The server must never block a Binder thread, so waiting is done by polling here.
     */
    public int waitFor() throws RemoteException, InterruptedException {
        while (true) {
            int value = exitValue();
            if (value != Integer.MIN_VALUE) return value;
            Thread.sleep(50);
        }
    }

    public void destroy() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.PROCESS_DESCRIPTOR);
            remote.transact(Protocol.PTX_DESTROY, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
