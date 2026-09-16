package dev.kage.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logger: stdout (visible through the starter shell / logcat) plus a rotating file. */
public final class ServerLog {

    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 512 * 1024L;
    private static SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static File file;

    private ServerLog() { }

    public static void init(File logFile) {
        synchronized (LOCK) {
            file = logFile;
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        }
        i("Kage", "log started at " + new Date());
    }

    public static void d(String tag, String msg) {
        if ("debug".equals(ServerState.get().logLevel)) write("D", tag, msg);
    }

    public static void i(String tag, String msg) { write("I", tag, msg); }

    public static void w(String tag, String msg) { write("W", tag, msg); }

    public static void e(String tag, String msg) { write("E", tag, msg); }

    public static void e(String tag, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write("E", tag, sw.toString());
    }

    private static void write(String level, String tag, String msg) {
        String line = String.format(Locale.US, "%s %s/%s: %s",
                fmt.format(new Date()), level, tag, msg);
        // stdout first - this is what the user sees in the terminal they started us from
        System.out.println(line);
        synchronized (LOCK) {
            if (file == null) return;
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getParentFile(), file.getName() + ".1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file, true);
                out.write((line + "\n").getBytes("UTF-8"));
            } catch (IOException ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
            }
        }
    }
}
