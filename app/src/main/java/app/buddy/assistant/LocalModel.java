package app.buddy.assistant;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A model from the phone's own storage (Settings → API keys → Model on this phone). The user picks a .gguf file, Buddy
 * copies it into its own storage and lists it as a service, "On this phone", that OpenCode can use like any other.
 * llama.cpp's server (its official Android build, see native/llama.sh) runs the model as a child process of Buddy: on
 * this phone's own address only, behind a key only Buddy knows, started when a chat first needs it and stopped again
 * after a while without one (the model takes a few GB of memory while it runs).
 */
final class LocalModel {
    static final String ID = "phone";
    private static final int PORT = 18734;
    /**
     * The phone tools' instructions alone take about 7k tokens (README, Local models), and a chat has to fit with
     * them. Buddy tells the agent this size, so a long chat is shortened before it overruns the server.
     */
    private static final int CONTEXT = 24576;
    private static final long IDLE_MS = 10 * 60_000;

    interface Progress {
        void update(long done, long total);
    }

    private static Process proc;
    private static int users;
    private static long idleSince;
    private static Thread watcher;
    /** What the copy is doing now (null: none), for the screen. */
    private static volatile String importing;

    private LocalModel() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "models");
    }

    /** The chosen model's file, or null. */
    static File file(Context c) {
        Providers.P p = Providers.find(ID);
        if (p == null || p.models.length() == 0) return null;
        File f = new File(dir(c), p.models.optJSONObject(0).optString("id") + ".gguf");
        return f.isFile() ? f : null;
    }

    static boolean ready(Context c) {
        return file(c) != null;
    }

    /** "Name · 2.4 GB" for the screen. */
    static String describe(Context c) {
        File f = file(c);
        Providers.P p = Providers.find(ID);
        if (f == null || p == null) return "";
        return p.models.optJSONObject(0).optString("name") + " · " + String.format(Locale.US, "%.1f GB", f.length() / 1e9);
    }

    static String importing() {
        return importing;
    }

    /** Called on the main thread when the user has picked a file, so the screen can't start a second copy. */
    static void starting() {
        importing = "Starting…";
    }

    // ---- choosing the file ----

    /** Copies the picked file into Buddy's storage and lists it, replacing an earlier one. Off the main thread. */
    static String importModel(Context c, Uri from, Progress progress) throws Exception {
        try {
            ContentResolver cr = c.getContentResolver();
            String name = displayName(cr, from);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) throw new IOException("That isn't a .gguf model file.");
            if (name.toLowerCase(Locale.ROOT).matches(".*-\\d{5}-of-\\d{5}\\.gguf")) {
                throw new IOException("This model is split into several files. Pick a model that is a single .gguf file.");
            }
            long size = size(cr, from);
            File dir = dir(c);
            dir.mkdirs();
            if (size > 0 && dir.getUsableSpace() < size + (256L << 20)) {
                throw new IOException("There isn't enough free space on the phone for this model.");
            }
            String id = idFor(name);
            File part = new File(dir, id + ".part"), dest = new File(dir, id + ".gguf");
            importing = "Copying…";
            try (InputStream in = cr.openInputStream(from); OutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IOException("Buddy can't read that file.");
                byte[] buf = new byte[1 << 20];
                int n = in.read(buf);
                while (n >= 0 && n < 4) { // the first read gave less than the 4 bytes that say what the file is
                    int more = in.read(buf, n, buf.length - n);
                    if (more < 0) break;
                    n += more;
                }
                if (n < 4 || buf[0] != 'G' || buf[1] != 'G' || buf[2] != 'U' || buf[3] != 'F') {
                    throw new IOException("That isn't a GGUF model file.");
                }
                long done = 0, shown = 0;
                while (n > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - shown >= (16 << 20)) {
                        shown = done;
                        importing = String.format(Locale.US, "Copying… %.1f of %.1f GB", done / 1e9, Math.max(size, done) / 1e9);
                        if (progress != null) progress.update(done, size);
                    }
                    n = in.read(buf);
                }
            } catch (Exception e) {
                part.delete();
                throw e;
            }
            stop(); // a server still running holds the earlier model
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!f.equals(part)) f.delete(); // one model at a time
            if (!part.renameTo(dest)) throw new IOException("Buddy couldn't save the model.");
            register(c, id, name.substring(0, name.length() - 5));
            return name;
        } finally {
            importing = null;
        }
    }

    /** Lists the model as a service (key and all), and makes it OpenCode's default when that has none yet. */
    private static void register(Context c, String id, String label) throws Exception {
        Providers.P p = Providers.find(ID);
        if (p == null) p = new Providers.P(ID);
        p.name = "On this phone";
        p.anthropic = "";
        p.openai = "http://127.0.0.1:" + PORT + "/v1";
        p.models = new JSONArray().put(new JSONObject().put("id", id).put("name", label).put("window", CONTEXT)
                .put("efforts", new JSONArray()));
        p.model = id;
        p.shown.clear();
        p.off = false;
        String key = Secrets.get(c, Providers.secretName(ID));
        if (key == null || key.isEmpty()) { // only Buddy knows it: the server asks for it, other apps on the phone can't use it
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format("%02x", x));
            key = s.toString();
        }
        Providers.save(c, p, key);
        if (Prefs.agentDefault(c, "opencode").isEmpty()) Prefs.setAgentDefault(c, "opencode", Providers.ref(p, id));
    }

    /** Stops the server and deletes the model's files (the service itself goes in Providers.remove). */
    static void discard(Context c) {
        stop();
        File[] all = dir(c).listFiles();
        if (all != null) for (File f : all) f.delete();
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor q = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) return q.getString(0);
        } catch (Exception ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "model.gguf" : s.substring(s.lastIndexOf('/') + 1);
    }

    private static long size(ContentResolver cr, Uri u) {
        try (Cursor q = cr.query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** A file name as a model id: letters, digits and . _ - only (it goes into chat settings and command lines). */
    private static String idFor(String fileName) {
        String s = fileName.substring(0, fileName.length() - 5).replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "model" : s;
    }

    // ---- the server ----

    /**
     * A request for the model is coming: starts the server if it isn't running and waits until it can answer. Every
     * call is paired with release(); the server isn't stopped for being idle while a request is in flight.
     */
    static void acquire(Context c) throws Exception {
        synchronized (LocalModel.class) {
            users++;
        }
        try {
            start(c);
        } catch (Exception e) {
            release();
            throw e;
        }
    }

    static synchronized void release() {
        if (users > 0) users--;
        idleSince = System.currentTimeMillis();
    }

    private static void start(Context c) throws Exception {
        File model = file(c);
        if (model == null) {
            throw new IOException("No model is chosen. Pick a .gguf file in Buddy's Settings, under API keys, in Model on this phone.");
        }
        String key = Secrets.get(c, Providers.secretName(ID));
        Process mine;
        synchronized (LocalModel.class) {
            if (proc == null || !proc.isAlive()) {
                killStale(c); // one left running by an earlier run of Buddy would hold the port
                proc = launch(c, model, key == null ? "" : key);
            }
            mine = proc;
            if (watcher == null) {
                watcher = new Thread(LocalModel::watch, "model-idle");
                watcher.setDaemon(true);
                watcher.start();
            }
        }
        long end = System.currentTimeMillis() + 10 * 60_000; // a big model from the phone's storage takes a while
        while (System.currentTimeMillis() < end) {
            if (!mine.isAlive()) throw new IOException("The model stopped while loading. " + logTail(c));
            if (healthy()) return;
            Thread.sleep(500);
        }
        throw new IOException("The model took too long to load. " + logTail(c));
    }

    private static Process launch(Context c, File model, String key) throws Exception {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File exe = new File(lib, "libllama-server.so");
        if (!exe.isFile()) throw new IOException("This build of Buddy doesn't include the model engine.");
        File log = new File(c.getFilesDir(), "llama-server.log");
        if (log.length() > (1 << 20)) log.delete();
        List<String> cmd = new ArrayList<>(Arrays.asList(exe.getPath(),
                "-m", model.getPath(), "-c", String.valueOf(CONTEXT), "-np", "1", "--jinja", // --jinja: tool calls need the model's own template
                "--host", "127.0.0.1", "--port", String.valueOf(PORT), "-t", String.valueOf(threads()),
                "-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0")); // half the memory for the context, same answers for practical purposes
        if (!key.isEmpty()) cmd.addAll(Arrays.asList("--api-key", key));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(c.getFilesDir());
        pb.environment().put("LD_LIBRARY_PATH", lib); // the engine's libraries are next to it
        pb.environment().put("HOME", c.getFilesDir().getPath());
        pb.environment().put("TMPDIR", c.getCacheDir().getPath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        return pb.start();
    }

    /** The performance cores' share: more threads than that slow a phone down (its small cores hold the rest back). */
    private static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(6, (cores + 1) / 2));
    }

    private static boolean healthy() {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            try {
                con.setConnectTimeout(1_000);
                con.setReadTimeout(2_000);
                return con.getResponseCode() == 200; // 503 while the model loads
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Stops the server after a while without a request. */
    private static void watch() {
        while (true) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (LocalModel.class) {
                if (proc != null && proc.isAlive() && users == 0 && System.currentTimeMillis() - idleSince > IDLE_MS) {
                    proc.destroyForcibly();
                    proc = null;
                }
            }
        }
    }

    static synchronized void stop() {
        if (proc != null) {
            proc.destroyForcibly();
            proc = null;
        }
        Context c = BuddyApp.context();
        if (c != null) killStale(c);
    }

    /** A server of ours left running by an earlier run of Buddy: nothing else on the phone is called libllama-server. */
    private static void killStale(Context c) {
        String exe = new File(c.getApplicationInfo().nativeLibraryDir, "libllama-server.so").getPath();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File f : procs) {
            if (!f.getName().matches("\\d+")) continue;
            try (FileInputStream in = new FileInputStream(new File(f, "cmdline"))) {
                byte[] b = new byte[exe.length() + 1];
                int n = in.read(b);
                if (n >= exe.length() && new String(b, 0, exe.length()).equals(exe)) {
                    android.os.Process.killProcess(Integer.parseInt(f.getName()));
                }
            } catch (Exception ignored) { // not ours to read, or gone already
            }
        }
    }

    /** The end of the server's own log, for the message when it didn't start. */
    private static String logTail(Context c) {
        File log = new File(c.getFilesDir(), "llama-server.log");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(log, "r")) {
            byte[] b = new byte[(int) Math.min(1200, r.length())];
            r.seek(r.length() - b.length);
            r.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
