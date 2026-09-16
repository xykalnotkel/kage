package dev.kage.server;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.InputStream;
import java.io.OutputStream;

import dev.kage.common.Protocol;

/**
 * A command running inside the server, exposed to the client as a Binder.
 *
 * stdout/stderr are handed out as pipe file descriptors so the client can read them live
 * (a plain byte stream cannot travel through Binder). stdin works the same way in reverse.
 *
 * Binder threads must never block, so waitFor() is implemented client side by polling
 * isAlive()/exitValue() - both return immediately here.
 */
public final class RemoteProcessImpl extends Binder {

    private final Process process;
    private final int id;
    private final ParcelFileDescriptor stdoutRead;
    private final ParcelFileDescriptor stderrRead;
    private final ParcelFileDescriptor stdinWrite;

    private volatile boolean finished;
    private volatile int exitCode = -1;

    public RemoteProcessImpl(int id, Process process) throws Exception {
        this.id = id;
        this.process = process;

        ParcelFileDescriptor[] stdout = ParcelFileDescriptor.createPipe();
        this.stdoutRead = stdout[0];
        pump("stdout-" + id, process.getInputStream(), stdout[1]);

        ParcelFileDescriptor[] stderr = ParcelFileDescriptor.createPipe();
        this.stderrRead = stderr[0];
        pump("stderr-" + id, process.getErrorStream(), stderr[1]);

        ParcelFileDescriptor[] stdin = ParcelFileDescriptor.createPipe();
        this.stdinWrite = stdin[0];
        pumpInput("stdin-" + id, stdin[1], process.getOutputStream());

        watcher();
    }

    public int id() {
        return id;
    }

    private void pump(final String name, final InputStream in, final ParcelFileDescriptor writeEnd) {
        Thread t = new Thread(name) {
            @Override
            public void run() {
                OutputStream out = null;
                try {
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                    // process died / client closed its end
                } finally {
                    if (out != null) try { out.close(); } catch (Throwable ignored) { }
                    try { in.close(); } catch (Throwable ignored) { }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private void pumpInput(final String name, final ParcelFileDescriptor readEnd, final OutputStream out) {
        Thread t = new Thread(name) {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) { }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private void watcher() {
        Thread t = new Thread("watch-" + id) {
            @Override
            public void run() {
                try {
                    exitCode = process.waitFor();
                } catch (Throwable ignored) {
                } finally {
                    finished = true;
                    ShellExecutor.forget(id);
                    closeQuietly(stdoutRead);
                    closeQuietly(stderrRead);
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try { pfd.close(); } catch (Throwable ignored) { }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(Protocol.PROCESS_DESCRIPTOR);
            return true;
        }
        data.enforceInterface(Protocol.PROCESS_DESCRIPTOR);
        switch (code) {
            case Protocol.PTX_GET_INPUT_STREAM:
                reply.writeNoException();
                stdoutRead.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            case Protocol.PTX_GET_OUTPUT_STREAM:
                reply.writeNoException();
                stdinWrite.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            case Protocol.PTX_GET_ERROR_STREAM:
                reply.writeNoException();
                stderrRead.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            case Protocol.PTX_WAIT_FOR:
            case Protocol.PTX_EXIT_VALUE: {
                int value;
                if (finished) {
                    value = exitCode;
                } else {
                    try {
                        value = process.exitValue();
                        exitCode = value;
                        finished = true;
                    } catch (IllegalThreadStateException notYet) {
                        value = Integer.MIN_VALUE; // still running
                    }
                }
                reply.writeNoException();
                reply.writeInt(value);
                return true;
            }
            case Protocol.PTX_IS_ALIVE: {
                boolean alive;
                if (finished) {
                    alive = false;
                } else {
                    try {
                        process.exitValue();
                        alive = false;
                    } catch (IllegalThreadStateException stillRunning) {
                        alive = true;
                    }
                }
                reply.writeNoException();
                reply.writeInt(alive ? 1 : 0);
                return true;
            }
            case Protocol.PTX_DESTROY:
                try {
                    process.destroy();
                } catch (Throwable ignored) { }
                ShellExecutor.forget(id);
                reply.writeNoException();
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    public IBinder asBinder() {
        return this;
    }
}
