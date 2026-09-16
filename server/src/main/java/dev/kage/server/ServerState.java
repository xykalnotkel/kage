package dev.kage.server;

import android.os.Build;
import android.os.Process;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import dev.kage.common.Protocol;

/** Immutable-ish runtime description of this server process. */
public final class ServerState {

    private static ServerState sInstance;

    public final long startedAt = System.currentTimeMillis();
    public String token;                 // authenticates the file transport
    public File sharedDir;               // manager's external files dir /kage
    public File runtimeDir;              // /data/local/tmp/kage
    public boolean debug;
    public String logLevel = "info";

    public final int uid = Process.myUid();
    public final int pid = Process.myPid();
    public final int sdk = Build.VERSION.SDK_INT;
    public final String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
    public final String release = Build.VERSION.RELEASE;

    private ServerState() { }

    public static synchronized ServerState get() {
        if (sInstance == null) sInstance = new ServerState();
        return sInstance;
    }

    public boolean isRoot() {
        return uid == 0;
    }

    public String mode() {
        if (isRoot()) return "root";
        if (uid == 2000) return "adb";
        return "uid:" + uid;
    }

    /** Parses "--key=value" style arguments. */
    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<String, String>();
        if (args == null) return map;
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("--")) {
                String body = a.substring(2);
                int eq = body.indexOf('=');
                if (eq < 0) {
                    map.put(body, "true");
                } else {
                    map.put(body.substring(0, eq), body.substring(eq + 1));
                }
            } else if (a.startsWith("-")) {
                map.put(a.substring(1), "true");
            }
        }
        return map;
    }

    public void applyArgs(String[] args) {
        Map<String, String> a = parseArgs(args);
        token = a.get("token");
        String shared = a.get("shared");
        if (shared != null) sharedDir = new File(shared);
        String runtime = a.get("runtime");
        runtimeDir = new File(runtime == null ? Protocol.RUNTIME_DIR : runtime);
        debug = a.containsKey("debug");
        String level = a.get("log");
        if (level != null) logLevel = level;
    }

    public File sharedDir() {
        return sharedDir;
    }

    public File requestsDir() {
        return new File(sharedDir, Protocol.DIR_REQUESTS);
    }

    public File responsesDir() {
        return new File(sharedDir, Protocol.DIR_RESPONSES);
    }

    public File outputDir() {
        return new File(sharedDir, Protocol.DIR_OUTPUT);
    }

    public File statusFile() {
        return new File(sharedDir, Protocol.FILE_STATUS);
    }

    public File grantsFile() {
        return new File(sharedDir, Protocol.FILE_GRANTS);
    }

    public File logFile() {
        File dir = runtimeDir != null ? runtimeDir : new File("/data/local/tmp/kage");
        return new File(dir, Protocol.FILE_SERVER_LOG);
    }
}
