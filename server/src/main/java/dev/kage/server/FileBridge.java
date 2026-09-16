package dev.kage.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.kage.common.Crypto;
import dev.kage.common.Json;
import dev.kage.common.Protocol;

/**
 * Transport #2: a request/response directory.
 *
 * The manager app cannot reach the server through Binder on every device/ROM, but it can always
 * share a directory with it (its own external files dir). Requests are signed with a token that
 * only the manager and the server know (it is passed on the command line), so a third party app
 * that happens to read the directory cannot inject commands.
 *
 * Layout (inside the shared dir):
 *   req/&lt;id&gt;.json    request  {id, cmd, payload, sig}
 *   res/&lt;id&gt;.json    response {id, ok, payload}
 *   res/&lt;id&gt;.done    finished {exit, elapsed}
 *   out/&lt;id&gt;.log     streamed stdout
 *   out/&lt;id&gt;.err     streamed stderr
 *   status.json       heartbeat written every second
 */
public final class FileBridge {

    private static final String TAG = "FileBridge";

    private final String token;
    private final KageBinderService service;
    private final ClientPusher pusher;
    private final Map<Integer, java.lang.Process> tracked = new LinkedHashMap<Integer, java.lang.Process>();

    private volatile boolean running = true;

    public FileBridge(String token, KageBinderService service, ClientPusher pusher) {
        this.token = token;
        this.service = service;
        this.pusher = pusher;
    }

    public boolean enabled() {
        return token != null && !token.isEmpty() && ServerState.get().sharedDir != null;
    }

    public void start() {
        if (!enabled()) {
            ServerLog.w(TAG, "file transport disabled (no token / no shared dir)");
            return;
        }
        File req = ServerState.get().requestsDir();
        req.mkdirs();
        ServerState.get().responsesDir().mkdirs();
        ServerState.get().outputDir().mkdirs();

        Thread requestThread = new Thread("file-bridge") {
            @Override
            public void run() {
                while (running) {
                    try {
                        poll();
                    } catch (Throwable t) {
                        ServerLog.d(TAG, "poll failed: " + t);
                    }
                    pause(200);
                }
            }
        };
        requestThread.setDaemon(true);
        requestThread.start();

        Thread heartbeat = new Thread("heartbeat") {
            @Override
            public void run() {
                while (running) {
                    try {
                        writeStatus();
                    } catch (Throwable ignored) {
                    }
                    pause(1000);
                }
            }
        };
        heartbeat.setDaemon(true);
        heartbeat.start();

        ServerLog.i(TAG, "file transport ready at " + ServerState.get().sharedDir);
    }

    public void stop() {
        running = false;
        writeStatus();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // -------------------------------------------------------------- incoming

    private void poll() {
        File[] files = ServerState.get().requestsDir().listFiles();
        if (files == null || files.length == 0) return;
        List<File> pending = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".json")) pending.add(f);
        }
        if (pending.isEmpty()) return;
        java.util.Collections.sort(pending, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(idOf(a.getName()), idOf(b.getName()));
            }
        });
        for (File f : pending) {
            handle(f);
        }
    }

    private static long idOf(String name) {
        try {
            return Long.parseLong(name.substring(0, name.lastIndexOf('.')));
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void handle(File file) {
        long id = idOf(file.getName());
        String body = read(file);
        file.delete();
        if (body == null) return;

        Map<String, Object> request = Json.parseObject(body);
        String cmd = Json.str(request, "cmd", "");
        String payload = Json.str(request, "payload", "{}");
        String sig = Json.str(request, "sig", "");

        if (!Crypto.verify(token, id + ":" + cmd + ":" + payload, sig)) {
            ServerLog.w(TAG, "rejected request #" + id + " (" + cmd + "): bad signature");
            respond(id, false, Json.obj("error", "bad signature"));
            return;
        }

        try {
            dispatch(id, cmd, Json.parseObject(payload));
        } catch (Throwable t) {
            ServerLog.e(TAG, t);
            respond(id, false, Json.obj("error", String.valueOf(t)));
        }
    }

    private void dispatch(final long id, String cmd, Map<String, Object> args) throws Exception {
        if (Protocol.CMD_PING.equals(cmd)) {
            respond(id, true, Json.obj("pong", true, "uid", android.os.Process.myUid()));
            return;
        }
        if (Protocol.CMD_STATUS.equals(cmd)) {
            respond(id, true, statusJson());
            return;
        }
        if (Protocol.CMD_EXEC.equals(cmd)) {
            exec(id, args, true);
            return;
        }
        if (Protocol.CMD_EXEC_WAIT.equals(cmd)) {
            exec(id, args, false);
            return;
        }
        if (Protocol.CMD_KILL.equals(cmd)) {
            if (Json.bool(args, "all", false)) {
                ShellExecutor.killAll();
                respond(id, true, Json.obj("killed", "all"));
            } else {
                int target = Json.integer(args, "id", -1);
                ShellExecutor.kill(target);
                respond(id, true, Json.obj("killed", target));
            }
            return;
        }
        if (Protocol.CMD_GRANTS.equals(cmd)) {
            respond(id, true, grantsJson());
            return;
        }
        if (Protocol.CMD_SYNC_GRANTS.equals(cmd)) {
            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
            for (Object o : Json.asList(args.get("packages"))) {
                Map<String, Object> m = Json.asObject(o);
                Map<String, Object> entry = Json.obj();
                entry.put("package", Json.str(m, "package"));
                entry.put("uid", Json.integer(m, "uid", -1));
                entries.add(entry);
            }
            int managerUid = Json.integer(args, "managerUid", -1);
            if (managerUid > 0) PermissionStore.setManagerUid(managerUid);
            PermissionStore.sync(entries, managerUid, android.os.Process.myUid());
            boolean push = Json.bool(args, "push", false);
            if (push) {
                for (String pkg : PermissionStore.packages()) pusher.pushNow(pkg);
            }
            respond(id, true, grantsJson());
            return;
        }
        if (Protocol.CMD_TRANSACT.equals(cmd)) {
            String serviceName = Json.str(args, "service", "");
            int code = Json.integer(args, "code", -1);
            byte[] in = Crypto.unhex(Json.str(args, "data", ""));
            byte[] out = PrivilegedTransact.transact(serviceName, code, in);
            respond(id, true, Json.obj("data", out == null ? "" : Crypto.hex(out)));
            return;
        }
        if (Protocol.CMD_RESOLVE.equals(cmd)) {
            String iface = Json.str(args, "interface", "");
            String method = Json.str(args, "method", "");
            android.os.Bundle b = PrivilegedTransact.resolve(iface, method);
            Map<String, Object> out = Json.obj();
            out.put("ok", b.getBoolean("ok", false));
            out.put("descriptor", b.getString("descriptor", iface));
            out.put("code", b.getInt("code", -1));
            out.put("error", b.getString("error"));
            respond(id, true, out);
            return;
        }
        if (Protocol.CMD_PUSH.equals(cmd)) {
            String pkg = Json.str(args, "package", "");
            boolean ok = !pkg.isEmpty() && pusher.pushNow(pkg);
            respond(id, true, Json.obj("pushed", ok, "package", pkg));
            return;
        }
        if (Protocol.CMD_SHUTDOWN.equals(cmd)) {
            respond(id, true, Json.obj("bye", true));
            KageServer.stop("shutdown requested by manager");
            return;
        }
        respond(id, false, Json.obj("error", "unknown command: " + cmd));
    }

    /** exec: output is streamed to out/&lt;id&gt;.log and out/&lt;id&gt;.err. */
    private void exec(final long id, Map<String, Object> args, final boolean stream) throws Exception {
        final String command = Json.str(args, "cmd", "");
        String dir = Json.str(args, "dir", null);
        List<String> envList = new ArrayList<String>();
        for (Object o : Json.asList(args.get("env"))) envList.add(String.valueOf(o));
        String[] env = envList.isEmpty() ? null : envList.toArray(new String[0]);
        final long started = System.currentTimeMillis();

        final int processId = ShellExecutor.nextId();
        final java.lang.Process process = ShellExecutor.spawn(command, env, dir, processId);
        tracked.put(processId, process);

        if (!stream) {
            final File output = new File(ServerState.get().outputDir(), id + ".log");
            final File error = new File(ServerState.get().outputDir(), id + ".err");
            pumpToFile(process.getInputStream(), output);
            pumpToFile(process.getErrorStream(), error);
            // run synchronously, the caller waits for the answer
            process.waitFor();
            ShellExecutor.forget(processId);
            tracked.remove(processId);
            Map<String, Object> out = Json.obj();
            out.put("exit", process.exitValue());
            out.put("stdout", readFully(output));
            out.put("stderr", readFully(error));
            out.put("elapsed", System.currentTimeMillis() - started);
            respond(id, true, out);
            return;
        }

        final File log = new File(ServerState.get().outputDir(), id + ".log");
        final File err = new File(ServerState.get().outputDir(), id + ".err");
        final File done = new File(ServerState.get().responsesDir(), id + ".done");
        pumpToFile(process.getInputStream(), log);
        pumpToFile(process.getErrorStream(), err);

        respond(id, true, Json.obj("running", true, "pid", processId, "log", log.getName(), "err", err.getName()));

        Thread watcher = new Thread("exec-" + id) {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = process.waitFor();
                } catch (Throwable ignored) {
                }
                ShellExecutor.forget(processId);
                tracked.remove(processId);
                Map<String, Object> out = Json.obj();
                out.put("exit", code);
                out.put("id", processId);
                out.put("elapsed", System.currentTimeMillis() - started);
                writeDone(done, out);
            }
        };
        watcher.setDaemon(true);
        watcher.start();
        ServerLog.i(TAG, "exec #" + id + " pid=" + processId + ": " + command);
    }

    private void pumpToFile(final java.io.InputStream in, final File file) {
        Thread t = new Thread("pump-" + file.getName()) {
            @Override
            public void run() {
                FileOutputStream out = null;
                try {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    out = new FileOutputStream(file, false);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (out != null) try { out.close(); } catch (IOException ignored) { }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static String readFully(File f) {
        if (f == null || !f.exists()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
            int read = in.read(buf);
            return read > 0 ? new String(buf, 0, read, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static String read(File f) {
        return readFully(f);
    }

    // -------------------------------------------------------------- outgoing

    private void respond(long id, boolean ok, Map<String, Object> payload) {
        Map<String, Object> response = Json.obj();
        response.put("id", id);
        response.put("ok", ok);
        response.put("payload", Json.write(payload));
        writeAtomic(new File(ServerState.get().responsesDir(), id + ".json"), Json.write(response));
    }

    private void writeDone(File file, Map<String, Object> payload) {
        Map<String, Object> response = Json.obj();
        response.put("ok", true);
        response.put("payload", Json.write(payload));
        writeAtomic(file, Json.write(response));
    }

    private void writeAtomic(File file, String body) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (file.exists()) file.delete();
            if (!tmp.renameTo(file)) {
                ServerLog.w(TAG, "rename failed for " + file);
            }
        } catch (Throwable t) {
            ServerLog.d(TAG, "write " + file + " failed: " + t);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (tmp.exists() && !file.exists()) tmp.delete();
        }
    }

    private void writeStatus() {
        if (!enabled()) return;
        writeAtomic(ServerState.get().statusFile(), Json.write(statusJson()));
    }

    private Map<String, Object> statusJson() {
        ServerState state = ServerState.get();
        Map<String, Object> s = Json.obj();
        s.put("version", Protocol.VERSION);
        s.put("uid", state.uid);
        s.put("pid", state.pid);
        s.put("mode", state.mode());
        s.put("root", state.isRoot());
        s.put("sdk", state.sdk);
        s.put("release", state.release);
        s.put("abi", state.abi);
        s.put("startedAt", state.startedAt);
        s.put("uptime", System.currentTimeMillis() - state.startedAt);
        s.put("hasContext", SystemContextProvider.get() != null);
        s.put("ts", System.currentTimeMillis());
        s.put("packages", PermissionStore.packages());
        s.put("running", ShellExecutor.running());
        Map<String, Object> push = Json.obj();
        push.put("viaContext", pusher.viaContextCount);
        push.put("viaHidden", pusher.viaHiddenCount);
        push.put("failures", pusher.failureCount);
        push.put("lastError", pusher.lastError);
        s.put("push", push);
        s.put("debug", state.debug);
        return s;
    }

    private Map<String, Object> grantsJson() {
        Map<String, Object> out = Json.obj();
        out.put("managerUid", PermissionStore.managerUid());
        List<Object> list = new ArrayList<Object>();
        for (Map.Entry<String, Integer> e : PermissionStore.snapshot().entrySet()) {
            list.add(Json.obj("package", e.getKey(), "uid", e.getValue()));
        }
        out.put("packages", list);
        return out;
    }

    public List<Integer> tracked() {
        return new ArrayList<Integer>(tracked.keySet());
    }
}
