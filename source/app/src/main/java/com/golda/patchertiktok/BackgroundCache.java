package com.golda.patchertiktok;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the videos background play is about to play into TikTok's cache folder, so they play
 * from a file: streaming them with the screen off can stall for many seconds. Two downloads run at
 * once; finished files are announced on the main thread. Everything is deleted when the session ends.
 */
final class BackgroundCache {
    /** Called on the main thread when the video {@code id} is on the phone. */
    interface Listener {
        void ready(String id);
    }

    private static final int MAX_FILES = 6;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "TiktokPatchXposed-cache");
        thread.setDaemon(true);
        return thread;
    });
    /** Finished files, oldest first. */
    private static final Map<String, File> READY = new LinkedHashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static volatile int session;

    private BackgroundCache() { }

    /** The video's file, if it is downloaded already. */
    static synchronized File file(String id) {
        File file = id == null ? null : READY.get(id);
        return file != null && file.isFile() ? file : null;
    }

    static synchronized boolean known(String id) {
        return id != null && (READY.containsKey(id) || LOADING.contains(id));
    }

    /** Starts downloading the video {@code id} from the first of {@code urls} that answers. */
    static void fetch(Context context, String id, List<String> urls, Listener listener) {
        if (context == null || id == null || urls.isEmpty()) return;
        synchronized (BackgroundCache.class) {
            if (READY.containsKey(id) || LOADING.contains(id)) return;
            LOADING.add(id);
        }
        int mine = session;
        List<String> copy = new ArrayList<>(urls);
        File folder = new File(context.getCacheDir(), "tiktokpatchxposed-background");
        WORKERS.execute(() -> {
            File done = null;
            String why = "no address";
            long started = System.currentTimeMillis();
            try {
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("no cache folder");
                for (String url : copy) {
                    if (mine != session) return;
                    File part = new File(folder, safe(id) + ".part");
                    try {
                        why = download(url, part);
                        if (why == null) {
                            File target = new File(folder, safe(id) + ".mp4");
                            if (target.exists()) target.delete();
                            if (part.renameTo(target)) {
                                done = target;
                                break;
                            }
                            why = "could not keep the file";
                        }
                    } catch (Throwable error) {
                        why = error.getClass().getSimpleName() + ": " + error.getMessage();
                    }
                    part.delete();
                }
            } catch (Throwable error) {
                why = String.valueOf(error);
            }
            File file = done;
            String reason = why;
            long took = System.currentTimeMillis() - started;
            MAIN.post(() -> {
                try {
                    synchronized (BackgroundCache.class) {
                        LOADING.remove(id);
                        if (mine != session) {
                            if (file != null) file.delete();
                            return;
                        }
                        if (file != null) {
                            READY.put(id, file);
                            prune();
                        }
                    }
                    if (file == null) {
                        BackgroundPlay.trace("download of " + id + " failed: " + reason);
                    } else {
                        BackgroundPlay.trace("downloaded " + id + " (" + file.length() / 1024 + " KB in " + took + " ms)");
                        listener.ready(id);
                    }
                } catch (Throwable error) {
                    Safe.log("background cache", error);
                }
            });
        });
    }

    /** Null when the file arrived whole, otherwise why not. */
    private static String download(String address, File part) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            int code = connection.getResponseCode();
            if (code / 100 != 2) return "HTTP " + code;
            long length = connection.getContentLengthLong();
            if (length > MAX_BYTES) return "too large (" + length / 1024 + " KB)";
            long written = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (written > MAX_BYTES) return "too large";
                }
            }
            if (written == 0 || length > 0 && written != length) return "cut off at " + written + " of " + length;
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static void prune() {
        while (READY.size() > MAX_FILES) {
            String oldest = READY.keySet().iterator().next();
            File file = READY.remove(oldest);
            if (file != null) file.delete();
        }
    }

    /** The session ended: downloads still running are dropped and every file is deleted. */
    static synchronized void clear() {
        session++;
        for (File file : READY.values()) file.delete();
        READY.clear();
        LOADING.clear();
    }

    private static String safe(String id) {
        return id.replaceAll("[^0-9A-Za-z_-]", "_");
    }
}
