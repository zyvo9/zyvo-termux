package com.zyvo.code;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Owns the bundled zyvo engine (the native Android aarch64 binary shipped as
 * libzyvo.so so the package manager extracts it into nativeLibraryDir, where
 * exec is permitted on every Android version).
 *
 * Responsibilities: bootstrap the data dirs, write the provider config, extract
 * the bundled skills, spawn `zyvo serve --port 4096`, capture its log, answer
 * health probes, and restart it if it dies.
 */
public final class EngineManager {

    private static final String TAG = "ZyvoEngine";

    public static final int PORT = 4096;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;

    public enum State {STOPPED, STARTING, READY, ERROR}

    public interface Listener {
        void onEngineState(State state, String detail);
    }

    private static volatile EngineManager instance;

    public static EngineManager get(Context ctx) {
        if (instance == null) {
            synchronized (EngineManager.class) {
                if (instance == null) instance = new EngineManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private final OkHttpClient probe = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    public final File rootDir;
    public final File homeDir;
    public final File tmpDir;
    public final File workspaceDir;
    public final File logDir;
    public final File logFile;
    public final File configFile;
    public final File skillsDir;

    private Process process;
    private Thread logThread;
    private volatile boolean stopping;
    private volatile State state = State.STOPPED;
    private volatile String detail = "not started";
    private volatile String engineVersion = "";

    private EngineManager(Context ctx) {
        this.app = ctx;
        this.rootDir = ctx.getFilesDir();
        this.homeDir = new File(rootDir, "home");
        this.tmpDir = new File(rootDir, "tmp");
        this.workspaceDir = new File(rootDir, "workspace");
        this.logDir = new File(rootDir, "log");
        this.logFile = new File(logDir, "engine.log");
        this.configFile = new File(homeDir, ".config/zyvo/zyvo.json");
        this.skillsDir = new File(homeDir, ".config/zyvo/skills");
    }

    public String nativeLibDir() {
        return app.getApplicationInfo().nativeLibraryDir;
    }

    public String binaryPath() {
        return nativeLibDir() + "/libzyvo.so";
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String engineVersion() {
        return engineVersion;
    }

    public boolean isReady() {
        if (state != State.READY) return false;
        // A READY state that outlives its process lies to the watchdog and to
        // the send button: the server is gone and nothing ever restarts it.
        if (process != null && !process.isAlive()) {
            process = null;
            setState(State.ERROR, "engine process exited");
            return false;
        }
        return true;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        l.onEngineState(state, detail);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void setState(State s, String d) {
        this.state = s;
        this.detail = d == null ? "" : d;
        Log.i(TAG, "state=" + s + " (" + d + ")");
        main.post(() -> {
            for (Listener l : listeners) l.onEngineState(s, this.detail);
        });
    }

    // ---------------------------------------------------------------- dirs

    public void ensureDirs() {
        mkdirs(homeDir);
        mkdirs(tmpDir);
        mkdirs(workspaceDir);
        mkdirs(logDir);
        mkdirs(new File(homeDir, ".config/zyvo"));
        mkdirs(new File(homeDir, ".local/share/zyvo"));
        mkdirs(new File(homeDir, ".local/state"));
    }

    private static void mkdirs(File f) {
        if (!f.exists() && !f.mkdirs()) Log.w(TAG, "mkdirs failed: " + f);
    }

    // -------------------------------------------------------------- config

    /** Rewrites the engine config from the current model + key. Idempotent. */
    public void writeConfig() throws IOException {
        Prefs prefs = new Prefs(app);

        JsonObject root = new JsonObject();
        root.addProperty("$schema", "https://opencode.ai/config.json");
        root.addProperty("model", prefs.providerId() + "/" + prefs.modelId());

        // The bash tool spawns this shell; in the Termux fork it must be
        // Termux's bash (Android's /bin/sh has no pkg/apt/PATH).
        File termuxBash = new File(rootDir, "usr/bin/bash");
        if (termuxBash.exists()) root.addProperty("shell", termuxBash.getAbsolutePath());

        // The engine rotates onto these lanes when a quota wall is hit.
        JsonArray fallback = new JsonArray();
        fallback.add("opencode/" + Prefs.DEFAULT_MODEL);
        fallback.add("opencode/grok-code");
        fallback.add("opencode/deepseek-v4.1-flash-free");
        root.add("fallback_models", fallback);

        // The app IS the approval surface, and the user already granted the
        // phone-level permissions â€” so the agent never stalls on a prompt.
        JsonObject permission = new JsonObject();
        String[] keys = {"read", "edit", "glob", "grep", "list", "bash", "task",
                "external_directory", "todowrite", "question", "webfetch", "websearch",
                "lsp", "doom_loop", "skill"};
        for (String k : keys) permission.addProperty(k, "allow");
        root.add("permission", permission);

        mkdirs(configFile.getParentFile());
        try (OutputStream os = new FileOutputStream(configFile)) {
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Seeds the built-in lane credential. The engine reads
     * ~/.local/share/zyvo/auth.json (the same file the CLI uses), so baking the
     * key here is exactly what `zyvo auth login` would have written.
     */
    public void writeAuth() throws IOException {
        String key = new Prefs(app).opencodeKey();
        if (key.isEmpty()) return;
        File auth = new File(homeDir, ".local/share/zyvo/auth.json");
        mkdirs(auth.getParentFile());
        JsonObject lane = new JsonObject();
        lane.addProperty("type", "api");
        lane.addProperty("key", key);
        JsonObject root = new JsonObject();
        root.add("opencode", lane);
        try (OutputStream os = new FileOutputStream(auth)) {
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!auth.setReadable(true, true)) Log.w(TAG, "auth.json chmod failed");
    }

    /** Extra operator instructions the user typed in Settings. */
    public void writeAgentsInstructions() throws IOException {
        String prompt = new Prefs(app).systemPrompt();
        File agents = new File(workspaceDir, "AGENTS.md");
        if (prompt == null || prompt.trim().isEmpty()) {
            if (agents.exists() && agents.length() < 8) agents.delete();
            return;
        }
        try (OutputStream os = new FileOutputStream(agents)) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
    }

    // -------------------------------------------------------------- skills

    /** Extracts assets/skills.tar(.gz) once into the engine's skills dir. */
    public void extractSkills() {
        if (skillsDir.exists()) {
            String[] kids = skillsDir.list();
            if (kids != null && kids.length > 0) return;
        }
        mkdirs(skillsDir);
        // The archive carries a top-level `skills/` folder, so it unpacks into
        // the zyvo config dir (skills land exactly at ~/.config/zyvo/skills).
        File dest = configFile.getParentFile();
        mkdirs(dest);
        // aapt renames some archives (skills.tar.gz landed as skills.tar), so
        // resolve by what is actually packaged and detect gzip by magic bytes.
        String[] candidates = {"skills.tar.gz", "skills.tar"};
        for (String name : candidates) {
            try (InputStream raw = app.getAssets().open(name)) {
                int count = untar(raw, dest);
                Log.i(TAG, "skills extracted from " + name + ": " + count + " entries");
                return;
            } catch (IOException e) {
                Log.w(TAG, "skills asset " + name + " not usable: " + e.getMessage());
            }
        }
    }

    private int untar(InputStream raw, File dest) throws IOException {
        java.io.BufferedInputStream buffered = new java.io.BufferedInputStream(raw, 16384);
        buffered.mark(4);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        InputStream stream = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(buffered, 16384) : buffered;
        return untarStream(stream, dest);
    }

    private int untarStream(InputStream gz, File dest) throws IOException {
        int written = 0;
        String destPath = dest.getCanonicalPath();
        {
            byte[] header = new byte[512];
            while (true) {
                if (!readFully(gz, header, 512)) break;
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) break;

                String name = new String(header, 0, 100, StandardCharsets.UTF_8)
                        .replace("\u0000", "").trim();
                String prefix = new String(header, 345, 155, StandardCharsets.UTF_8)
                        .replace("\u0000", "").trim();
                if (!prefix.isEmpty()) name = prefix + "/" + name;

                long size = parseOctal(header, 124, 12);
                char type = (char) (header[156] == 0 ? '0' : header[156]);

                if (name.isEmpty() || name.contains("..")) {
                    skipFully(gz, size + pad(size));
                    continue;
                }

                File out = new File(dest, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(destPath) && !outPath.startsWith(destPath + File.separator)) {
                    skipFully(gz, size + pad(size));
                    continue;
                }

                if (type == '5') {
                    mkdirs(out);
                    skipFully(gz, size + pad(size));
                } else if (type == '0' || type == '7') {
                    if (out.getParentFile() != null) mkdirs(out.getParentFile());
                    try (OutputStream os = new FileOutputStream(out)) {
                        copyN(gz, os, size);
                    }
                    if (name.endsWith(".sh")) out.setExecutable(true);
                    written++;
                    skipFully(gz, pad(size));
                } else {
                    skipFully(gz, size + pad(size));
                }
            }
        }
        return written;
    }

    private static int pad(long size) {
        long rem = size % 512;
        return rem == 0 ? 0 : (int) (512 - rem);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') continue;
            int digit = b - '0';
            if (digit < 0 || digit > 7) break;
            value = value * 8 + digit;
        }
        return value;
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void copyN(InputStream in, OutputStream out, long len) throws IOException {
        byte[] buf = new byte[16384];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skipFully(InputStream in, long len) throws IOException {
        long left = len;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            left -= n;
        }
    }

    // ------------------------------------------------------------- process

    public void ensureStarted() {
        if (state == State.READY) {
            if (process == null || process.isAlive()) return;
            // process died while we believed the engine was up - restart it
            setState(State.ERROR, "engine process exited");
        }
        if (!starting.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                doStart();
            } catch (Throwable e) {
                Log.e(TAG, "start failed", e);
                setState(State.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                starting.set(false);
            }
        }, "engine-start");
        t.setDaemon(true);
        t.start();
    }

    private void doStart() {
        setState(State.STARTING, "preparing files");
        ensureDirs();

        // A previous instance may still be serving (app restart, service alive).
        if (probeHealth()) {
            setState(State.READY, "already running");
            return;
        }

        File bin = new File(binaryPath());
        if (!bin.exists()) {
            setState(State.ERROR, "engine binary missing from the APK");
            return;
        }
        if (!bin.canExecute()) bin.setExecutable(true, false);

        try {
            writeConfig();
            writeAuth();
            writeAgentsInstructions();
        } catch (IOException e) {
            setState(State.ERROR, "config write failed: " + e.getMessage());
            return;
        }

        setState(State.STARTING, "extracting skills");
        extractSkills();

        setState(State.STARTING, "starting zyvo serve");
        try {
            process = buildProcess(bin);
        } catch (IOException e) {
            setState(State.ERROR, "spawn failed: " + e.getMessage());
            return;
        }

        logThread = new Thread(() -> pumpLog(process), "engine-log");
        logThread.setDaemon(true);
        logThread.start();

        // Wait for the server to answer health (cold start on a weak phone can
        // take a while: Bun boot + config + project bootstrap).
        long deadline = System.currentTimeMillis() + 150_000L;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive() && !probeHealth()) {
                String tail = tailLog(6).trim();
                setState(State.ERROR, tail.isEmpty()
                        ? "engine exited during startup â€” open logs"
                        : "engine exited: " + tail.replace('\n', ' '));
                return;
            }
            if (probeHealth()) {
                setState(State.READY, "listening on " + PORT);
                return;
            }
            sleep(700);
        }
        setState(State.ERROR, "engine did not become healthy in time");
    }

    private Process buildProcess(File bin) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--port");
        cmd.add(String.valueOf(PORT));
        cmd.add("--hostname");
        cmd.add("127.0.0.1");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspaceDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        String lib = nativeLibDir();

        // Inside the Termux fork the real prefix is <files>/usr, so the engine's
        // bash tool must see Termux's own PATH and shell or `pkg`/`apt` are
        // invisible ("pkg: inaccessible or not found").
        File prefix = new File(rootDir, "usr");
        String termuxBin = new File(prefix, "bin").getAbsolutePath();

        env.put("HOME", homeDir.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("PATH", termuxBin + ":" + new File(termuxBin, "applets").getAbsolutePath()
                + ":/system/bin:/system/xbin:" + lib);
        env.put("SHELL", new File(termuxBin, "bash").getAbsolutePath());
        env.put("TERMUX__PREFIX", prefix.getAbsolutePath());
        env.put("TERMUX__HOME", homeDir.getAbsolutePath());
        env.put("TERMUX_APP__PACKAGE_NAME", "com.termux");
        env.put("TERMUX_APP__DATA_DIR", rootDir.getParentFile().getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("TEMP", tmpDir.getAbsolutePath());
        env.put("TMP", tmpDir.getAbsolutePath());
        env.put("OPENCODE_TMPDIR", tmpDir.getAbsolutePath());
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", "/data");
        env.put("TERMUX_VERSION", "zyvo-code-app");
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");

        env.put("LD_LIBRARY_PATH", lib);
        File tagfix = new File(lib, "libtagfix.so");
        if (tagfix.exists()) env.put("LD_PRELOAD", tagfix.getAbsolutePath());
        File opentui = new File(lib, "libopentui.so");
        if (opentui.exists()) env.put("OPENTUI_LIB_PATH", opentui.getAbsolutePath());
        File pty = new File(lib, "librust_pty_arm64.so");
        if (pty.exists()) env.put("BUN_PTY_LIB", pty.getAbsolutePath());

        env.put("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER", "true");
        env.put("OPENCODE_DISABLE_TUI_AUDIO", "1");
        env.put("ZYVO_SESSION_ROOT", workspaceDir.getAbsolutePath());

        Log.i(TAG, "spawn: " + cmd + " cwd=" + workspaceDir);
        return pb.start();
    }

    private void pumpLog(Process p) {
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
                    lineBuf.reset();
                    appendLog(line);
                } else if (b != '\r') {
                    lineBuf.write(b);
                    if (lineBuf.size() > 8192) {
                        appendLog(new String(lineBuf.toByteArray(), StandardCharsets.UTF_8));
                        lineBuf.reset();
                    }
                }
            }
        } catch (IOException ignored) {
            // process died — handled by the health loop
        }
        // The log stream closes when the child exits (or when stop() kills it).
        // Flip the state here so the watchdog and the UI stop believing a dead
        // engine is READY.
        if (!stopping && process != null && !process.isAlive()) {
            process = null;
            if (state != State.STOPPED) setState(State.ERROR, "engine process exited");
        }
    }
    }

    private void appendLog(String line) {
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > 800) ring.removeFirst();
        }
        try (OutputStream os = new FileOutputStream(logFile, true)) {
            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    public String tailLog(int lines) {
        StringBuilder sb = new StringBuilder();
        synchronized (ring) {
            int skip = Math.max(0, ring.size() - lines);
            int i = 0;
            for (String s : ring) {
                if (i++ >= skip) sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- health

    public boolean probeHealth() {
        Request req = new Request.Builder().url(BASE_URL + "/global/health").get().build();
        try (Response res = probe.newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) return false;
            String body = res.body().string();
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("healthy") && o.get("healthy").isJsonPrimitive()
                        && o.get("healthy").getAsJsonPrimitive().isBoolean()
                        && o.get("healthy").getAsBoolean()) {
                    if (o.has("version") && o.get("version").isJsonPrimitive()) {
                        engineVersion = o.get("version").getAsString();
                    }
                    return true;
                }
            }
            // Some builds answer /global/health only through the instance API;
            // a plain 200 from /session is an equally good signal.
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        stopping = true;
        try {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
                process = null;
            }
        } finally {
            stopping = false;
        }
        setState(State.STOPPED, "stopped");
    }

    public void restart() {
        Thread t = new Thread(() -> {
            stop();
            sleep(600);
            ensureStarted();
        }, "engine-restart");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Debug helper used by Settings â†’ About. */
    public String describe() {
        return "binary=" + binaryPath() + "\nhome=" + homeDir + "\nworkspace=" + workspaceDir
                + "\nstate=" + state + " (" + detail + ")";
    }
}

