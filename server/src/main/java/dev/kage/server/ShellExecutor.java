package dev.kage.server;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs commands with the privileges of the server process: uid 2000 when started through
 * "adb shell", uid 0 when started through "su".
 */
public final class ShellExecutor {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, Process> RUNNING = new ConcurrentHashMap<Integer, Process>();
    private static final String SHELL = "/system/bin/sh";

    private ShellExecutor() { }

    public static int nextId() {
        return NEXT_ID.getAndIncrement();
    }

    /** Starts a command. cmd is handed to "/system/bin/sh -c" unless it already is a shell. */
    public static Process spawn(String command, String dir) throws Exception {
        return spawn(command, null, dir, null);
    }

    public static Process spawn(String command, String[] env, String dir, Integer id) throws Exception {
        return spawn(new String[]{SHELL, "-c", command}, env, dir, id);
    }

    public static Process spawn(String[] argv, String[] env, String dir, Integer id) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (env != null && env.length > 0) {
            Map<String, String> environment = pb.environment();
            for (String e : env) {
                int eq = e.indexOf('=');
                if (eq > 0) environment.put(e.substring(0, eq), e.substring(eq + 1));
            }
        }
        if (dir != null) {
            File d = new File(dir);
            if (d.isDirectory()) pb.directory(d);
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        if (id != null) RUNNING.put(id, p);
        return p;
    }

    public static void forget(int id) {
        RUNNING.remove(id);
    }

    public static Process get(int id) {
        return RUNNING.get(id);
    }

    public static List<Integer> running() {
        return new ArrayList<Integer>(RUNNING.keySet());
    }

    public static void kill(int id) {
        Process p = RUNNING.remove(id);
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void killAll() {
        for (Integer id : running()) kill(id);
    }

    /** Convenience for short lived commands (grants, props, ...). */
    public static Result run(String command, long timeoutMs) {
        Process p = null;
        try {
            p = spawn(command, null, null, null);
            StreamPump out = new StreamPump(p.getInputStream());
            StreamPump err = new StreamPump(p.getErrorStream());
            out.start();
            err.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException notYet) {
                    Thread.sleep(20);
                }
            }
            if (!done) {
                p.destroy();
                return new Result(-1, out.text(), err.text());
            }
            if (p.getInputStream() != null) p.getInputStream().close();
            out.join(500);
            err.join(500);
            return new Result(p.exitValue(), out.text(), err.text());
        } catch (Throwable t) {
            ServerLog.d("ShellExecutor", "run failed: " + t);
            return new Result(-1, "", String.valueOf(t));
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    public static final class Result {
        public final int code;
        public final String stdout;
        public final String stderr;

        Result(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public String out() {
            return stdout == null ? "" : stdout.trim();
        }
    }

    /** Drains a stream into a string on its own thread. */
    public static final class StreamPump extends Thread {
        private final java.io.InputStream in;
        private final StringBuilder sb = new StringBuilder();

        public StreamPump(java.io.InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (sb) {
                        sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        public String text() {
            synchronized (sb) {
                return sb.toString();
            }
        }
    }
}
