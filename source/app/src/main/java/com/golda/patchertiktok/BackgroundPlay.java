package com.golda.patchertiktok;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The feed keeps playing when TikTok is put away: with the screen turned off, or with another app
 * on the screen.
 *
 * <p>TikTok frees its own player as soon as its screen stops (the video's surface goes, and its
 * player with it), and holding that player back did not keep it alive. So nothing of TikTok's is
 * held back here. Instead, once TikTok is away, the module plays the same video's address with a
 * player of its own, from the position TikTok had reached; without a screen that is its sound.
 * When the video ends and the feed moves on by itself (see {@link AutoScroll}), TikTok's feed is
 * moved to the next video and that one is played. Back in TikTok, the module's player stops and
 * TikTok's own continues from where the sound was.
 *
 * <p>Since the player is the module's own, the media notification shows its real state. A call,
 * another app taking the audio, or headphones coming out pause it. Main thread only.
 */
final class BackgroundPlay {
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String ABILITY = "com.ss.android.ugc.feed.platform.panel.player.IPlayerComponentAbility";
    private static final String ENGINE = "com.ss.ttvideoengine.TTVideoEngine";
    private static final long SAMPLE_MS = 500L;
    private static final long WATCH_MS = 1_000L;
    private static final long PHOTO_MS = 3_000L;
    /** The longest a session keeps the processor awake with the screen off. */
    private static final long LOCK_MS = 3 * 60 * 60 * 1_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean away;
    private static Application app;
    private static Method playerAbility;
    private static Method currentAweme;
    private static WeakReference<Object> panel = new WeakReference<>(null);
    private static PowerManager.WakeLock lock;
    private static AudioManager audio;

    /** In TikTok: where its video was last seen, and when it last moved. */
    private static long seenPosition = -1;
    private static long seenAt;
    private static long movedAt;

    /** The module's own player and what it plays. */
    private static MediaPlayer player;
    private static boolean prepared;
    private static long openedAt;
    private static Object aweme;
    private static String playingId;
    private static List<String> addresses = new ArrayList<>();
    private static int addressIndex;
    private static long startAt;
    /** For a photo post: how long its music plays before the feed moves on. Zero for videos. */
    private static long photoLimit;
    private static boolean userPaused;
    /** Paused for a call or a short loss of the audio; continues by itself. */
    private static boolean interrupted;
    private static int failures;
    private static int skips;
    /** Bumped whenever what is played changes, so delayed steps of an earlier video do nothing. */
    private static int generation;
    private static AudioFocusRequest focus;
    private static BroadcastReceiver noisy;

    /** Back in TikTok: the video and position its player should continue from. */
    private static String pendingId;
    private static long pendingPosition;
    private static long pendingAt;

    private static int traced;

    private BackgroundPlay() { }

    /** True while TikTok is put away and the module plays its feed. */
    static boolean away() {
        return away;
    }

    /** A line for bug reports; capped per session so a night in the background does not flood the log. */
    static void trace(String what) {
        if (traced++ < 200) RuntimeLog.log("bg: " + what);
    }

    static void install(ClassLoader loader) {
        int hooks = 0;
        Class<?> panelType = Hooks.findClass(PANEL, loader);
        if (panelType != null) {
            currentAweme = RenderedAdSkip.currentAweme(panelType);
            if (currentAweme != null) currentAweme.setAccessible(true);
            for (Method method : Reflect.methods(panelType)) {
                if (method.getParameterTypes().length == 0 && ABILITY.equals(method.getReturnType().getName())) {
                    method.setAccessible(true);
                    playerAbility = method;
                }
            }
            for (Method method : Reflect.methods(panelType)) {
                if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
                if (Hooks.tryHook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        rendered(param.thisObject);
                    }
                })) hooks++;
            }
        }
        // While the module plays, TikTok's own players stay silent, so two sounds never mix.
        Class<?> engineType = Hooks.findClass(ENGINE, loader);
        if (engineType != null) {
            for (Method method : Reflect.methods(engineType)) {
                if (!"play".equals(method.getName()) || method.getParameterTypes().length != 0
                        || method.getReturnType() != void.class || Modifier.isAbstract(method.getModifiers())) continue;
                if (Hooks.tryHook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (!away) return;
                        param.setResult(null);
                        trace("TikTok's player kept silent");
                    }
                })) hooks++;
            }
        }
        RuntimeLog.log("background play: hooks=" + hooks + (currentAweme == null ? ", no feed" : ""));
    }

    static void attach(Application application) {
        app = application;
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power != null && app.checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TiktokPatchXposed:background");
            lock.setReferenceCounted(false);
        }
        MediaControls.attach(app, new MediaControls.Player() {
            @Override public void play() { userPlay(); }

            @Override public void pause() { userPause(); }

            @Override public void next() { userNext(); }

            @Override public void stop() { userStop(); }

            @Override public void seekTo(long position) { userSeek(position); }
        });
        Foreground.listenAway(BackgroundPlay::putAway);
        Foreground.listen(BackgroundPlay::back);
    }

    // ---- In TikTok: which feed is shown and whether its video plays

    private static void rendered(Object feedPanel) {
        panel = new WeakReference<>(feedPanel);
        MAIN.removeCallbacks(SAMPLE);
        MAIN.postDelayed(SAMPLE, SAMPLE_MS);
        if (pendingId != null) MAIN.postDelayed(Safe.run("background play", BackgroundPlay::applyPending), 300L);
    }

    /** Notes where TikTok's video is, so that putting TikTok away knows whether it played and where. */
    private static final Runnable SAMPLE = new Runnable() {
        @Override public void run() {
            try {
                Object feedPanel = panel.get();
                if (away || feedPanel == null || !Prefs.on(Prefs.BACKGROUND_PLAY) || !Foreground.shown()) return;
                long position = tikTokPosition(feedPanel);
                long now = SystemClock.uptimeMillis();
                if (position >= 0) {
                    if (position != seenPosition) movedAt = now;
                    seenPosition = position;
                    seenAt = now;
                }
                MAIN.postDelayed(this, SAMPLE_MS);
            } catch (Throwable error) {
                Safe.log("background play", error);
            }
        }
    };

    private static long tikTokPosition(Object feedPanel) {
        Object value = Reflect.call(invoke(playerAbility, feedPanel), "getCurrentPosition");
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    /** Whether TikTok's video was playing when its screen was paused; unknown counts as playing. */
    private static boolean wasPlaying() {
        long paused = Foreground.pausedAt();
        if (seenAt == 0 || paused - seenAt > 3_000L) return true;
        return paused - movedAt < 1_600L;
    }

    // ---- Put away and back

    /** No TikTok screen is shown any more: another app took the screen, or it turned off. */
    private static void putAway() {
        Object feedPanel = panel.get();
        if (away || app == null || !Prefs.on(Prefs.BACKGROUND_PLAY) || feedPanel == null || currentAweme == null) return;
        traced = 0;
        // Only when the feed itself was on the screen, not a chat or a page opened over it.
        Object activity = Reflect.call(feedPanel, "getActivity");
        if (!(activity instanceof android.app.Activity)) activity = Reflect.get(feedPanel, "activity");
        if (activity instanceof android.app.Activity && activity != Foreground.lastShown()) return;
        if (inCall()) return;
        if (!wasPlaying()) {
            trace("not started: the video was paused");
            return;
        }
        away = true;
        userPaused = false;
        interrupted = false;
        failures = 0;
        skips = 0;
        pendingId = null;
        long position = tikTokPosition(feedPanel);
        if (position <= 0) position = seenPosition;
        trace("on, feed=" + feedPanel.getClass().getSimpleName() + " position=" + position
                + " auto-scroll=" + AutoScroll.movesOn(feedPanel));
        holdLock();
        requestFocus();
        watchHeadphones();
        MAIN.removeCallbacks(WATCH);
        MAIN.postDelayed(WATCH, WATCH_MS);
        startCurrent(Math.max(0, position));
    }

    /** A TikTok screen is back: its own player takes over, from where the sound was. */
    private static void back() {
        if (!away) {
            MAIN.removeCallbacks(SAMPLE);
            MAIN.postDelayed(SAMPLE, SAMPLE_MS);
            return;
        }
        String id = playingId;
        long position = position();
        endSession();
        trace("back, at " + position + " of " + id);
        if (id != null && position > 2_000L) {
            pendingId = id;
            pendingPosition = position;
            pendingAt = SystemClock.uptimeMillis();
            // If the video draws no new first frame, continue it anyway.
            MAIN.postDelayed(Safe.run("background play", BackgroundPlay::applyPending), 1_500L);
        }
        MAIN.removeCallbacks(SAMPLE);
        MAIN.postDelayed(SAMPLE, SAMPLE_MS);
    }

    /** Moves TikTok's player to where the module's player stopped, if it shows that video. */
    private static void applyPending() {
        String id = pendingId;
        Object feedPanel = panel.get();
        if (id == null || feedPanel == null || away) return;
        if (SystemClock.uptimeMillis() - pendingAt > 15_000L) {
            pendingId = null;
            return;
        }
        Object shown = invoke(currentAweme, feedPanel);
        if (shown == null || !id.equals(id(shown))) return;
        pendingId = null;
        long duration = ResumePosition.duration(shown);
        if (duration > 0 && pendingPosition < duration - 2_000L) {
            boolean done = ResumePosition.seekTo(feedPanel, pendingPosition, duration);
            trace("TikTok continues at " + pendingPosition + (done ? "" : " (seek unavailable)"));
        }
    }

    private static void endSession() {
        away = false;
        generation++;
        releasePlayer();
        aweme = null;
        playingId = null;
        MAIN.removeCallbacks(WATCH);
        releaseLock();
        abandonFocus();
        unwatchHeadphones();
        MediaControls.hide();
    }

    // ---- The module's player

    /** Plays the video TikTok's feed shows now, from {@code position}. */
    private static void startCurrent(long position) {
        if (!away) return;
        Object feedPanel = panel.get();
        Object shown = feedPanel == null ? null : invoke(currentAweme, feedPanel);
        if (shown == null) {
            trace("no video in the feed");
            idle();
            return;
        }
        boolean auto = AutoScroll.movesOn(feedPanel);
        List<String> found = videoAddresses(shown);
        photoLimit = 0;
        if (found.isEmpty()) {
            // A photo post: its music, for as long as auto-scroll would stay on it.
            if (auto && Prefs.on(Prefs.SKIP_PHOTOS) && skips < 10) {
                skips++;
                trace("photo post skipped");
                advance();
                return;
            }
            DownloadModels.Item music = DownloadModels.audio(shown);
            if (music != null) found.addAll(music.urls);
            int photos = DownloadModels.photoImages(shown).size();
            Object seconds = Reflect.call(Reflect.call(shown, "getMusic"), "getDuration");
            long length = seconds instanceof Number ? ((Number) seconds).longValue() * 1_000L : 0L;
            if (photos > 0) photoLimit = AutoScrollPolicy.photoTime(photos, PHOTO_MS, length);
        }
        if (found.isEmpty()) {
            trace("no address to play for " + id(shown));
            if (auto && skips < 10) {
                skips++;
                advance();
            } else {
                idle();
            }
            return;
        }
        aweme = shown;
        playingId = id(shown);
        addresses = found;
        addressIndex = 0;
        startAt = position;
        open();
    }

    /** Addresses of the video, the smallest file first: without a screen only its sound matters. */
    private static List<String> videoAddresses(Object shown) {
        List<String> out = new ArrayList<>();
        List<DownloadModels.Item> items = DownloadModels.videos(shown);
        for (int i = items.size() - 1; i >= 0 && out.size() < 6; i--) {
            List<String> urls = items.get(i).urls;
            for (int j = 0; j < urls.size() && j < 2; j++) if (!out.contains(urls.get(j))) out.add(urls.get(j));
        }
        return out;
    }

    private static void open() {
        releasePlayer();
        int mine = ++generation;
        MediaPlayer created = new MediaPlayer();
        player = created;
        prepared = false;
        openedAt = SystemClock.uptimeMillis();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0");
            created.setDataSource(app, Uri.parse(addresses.get(addressIndex)), headers);
            created.setOnPreparedListener(ready -> guarded(mine, () -> onPrepared(ready)));
            created.setOnCompletionListener(done -> guarded(mine, BackgroundPlay::ended));
            created.setOnSeekCompleteListener(done -> guarded(mine, BackgroundPlay::publish));
            created.setOnErrorListener((failed, what, extra) -> {
                guarded(mine, () -> failed("player error " + what + "/" + extra));
                return true;
            });
            created.prepareAsync();
            trace("opening " + playingId + " (address " + (addressIndex + 1) + " of " + addresses.size() + ")");
        } catch (Throwable error) {
            failed("could not open: " + error);
            return;
        }
        publish();
    }

    /** Runs a player callback, unless it belongs to a video that is no longer the current one. */
    private static void guarded(int mine, Runnable task) {
        if (mine != generation || !away) return;
        try {
            task.run();
        } catch (Throwable error) {
            Safe.log("background play", error);
        }
    }

    private static void onPrepared(MediaPlayer ready) {
        prepared = true;
        failures = 0;
        skips = 0;
        int length = ready.getDuration();
        if (startAt > 1_000L && (length <= 0 || startAt < length - 1_500L)) ready.seekTo(startAt, MediaPlayer.SEEK_CLOSEST);
        startAt = 0;
        if (!userPaused && !interrupted) ready.start();
        trace("playing " + playingId + ", length " + length);
        publish();
    }

    /** The video played to its end: the feed moves on if it does so by itself, otherwise it loops like in TikTok. */
    private static void ended() {
        Object feedPanel = panel.get();
        if (feedPanel != null && AutoScroll.movesOn(feedPanel)) {
            advance();
        } else if (player != null && prepared) {
            player.seekTo(0);
            player.start();
            publish();
        }
    }

    /** The current address does not play: its next address, then the next video, then give up. */
    private static void failed(String why) {
        trace(why);
        if (addressIndex + 1 < addresses.size()) {
            addressIndex++;
            open();
            return;
        }
        Object feedPanel = panel.get();
        if (++failures < 3 && feedPanel != null && AutoScroll.movesOn(feedPanel)) {
            advance();
        } else {
            idle();
        }
    }

    /** Moves TikTok's feed to its next video and plays that one. */
    private static void advance() {
        Object feedPanel = panel.get();
        String before = feedPanel == null ? null : id(invoke(currentAweme, feedPanel));
        releasePlayer();
        int mine = ++generation;
        if (feedPanel == null || !AutoScroll.advance(feedPanel)) {
            trace("the feed has no next video");
            idle();
            return;
        }
        publish();
        MAIN.postDelayed(() -> guarded(mine, () -> follow(before, mine, 0)), 400L);
    }

    /** Waits until the feed shows another video than {@code before}, then plays it. */
    private static void follow(String before, int mine, int attempt) {
        Object feedPanel = panel.get();
        String now = feedPanel == null ? null : id(invoke(currentAweme, feedPanel));
        if (now != null && !now.equals(before)) {
            trace("moved on to " + now);
            startCurrent(0);
        } else if (attempt < 8) {
            MAIN.postDelayed(() -> guarded(mine, () -> follow(before, mine, attempt + 1)), 500L);
        } else {
            trace("the feed did not move on");
            idle();
        }
    }

    /** Nothing to play right now: shown as paused; play or next in the notification tries again. */
    private static void idle() {
        releasePlayer();
        generation++;
        userPaused = true;
        releaseLock();
        publish();
    }

    private static void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        prepared = false;
        if (old == null) return;
        try {
            old.reset();
            old.release();
        } catch (Throwable ignored) {
            // Already gone.
        }
    }

    private static long position() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    private static boolean playingNow() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Throwable error) {
            return false;
        }
    }

    /** Brings the notification and the media session up to date with the player. */
    private static void publish() {
        if (!away) return;
        int state;
        if (userPaused || interrupted) state = MediaControls.PAUSED;
        else if (player == null || !prepared) state = MediaControls.BUFFERING;
        else state = playingNow() ? MediaControls.PLAYING : MediaControls.PAUSED;
        long length = 0;
        try {
            if (player != null && prepared) length = player.getDuration();
        } catch (Throwable ignored) { }
        if (length <= 0 && aweme != null) length = ResumePosition.duration(aweme);
        Object feedPanel = panel.get();
        Object shown = aweme != null ? aweme : feedPanel == null ? null : invoke(currentAweme, feedPanel);
        MediaControls.show(shown, state, position(), length);
    }

    // ---- The notification's buttons

    private static void userPause() {
        if (!away) return;
        userPaused = true;
        if (playingNow()) player.pause();
        releaseLock();
        publish();
    }

    private static void userPlay() {
        if (!away) return;
        userPaused = false;
        interrupted = false;
        holdLock();
        requestFocus();
        if (player == null) {
            startCurrent(0);
            return;
        }
        if (prepared) player.start();
        publish();
    }

    private static void userNext() {
        if (!away) return;
        userPaused = false;
        interrupted = false;
        holdLock();
        advance();
    }

    private static void userSeek(long position) {
        if (!away || player == null || !prepared) return;
        player.seekTo(Math.max(0, position), MediaPlayer.SEEK_CLOSEST);
    }

    /** The stop button: the sound ends and the controls go away, until TikTok is put away again. */
    private static void userStop() {
        if (!away) return;
        endSession();
        trace("stopped from the controls");
    }

    // ---- Calls, other apps' audio, headphones

    /** Once a second while away: calls, the stay of a photo post, and an address that never starts. */
    private static final Runnable WATCH = new Runnable() {
        @Override public void run() {
            try {
                step();
            } catch (Throwable error) {
                Safe.log("background play", error);
            }
        }

        private void step() {
            if (!away) return;
            if (!Prefs.on(Prefs.BACKGROUND_PLAY)) {
                endSession();
                return;
            }
            if (inCall()) {
                interrupt();
            } else if (player != null && !prepared && SystemClock.uptimeMillis() - openedAt > 20_000L) {
                failed("no answer from the address");
            } else if (photoLimit > 0 && playingNow() && position() >= photoLimit) {
                Object feedPanel = panel.get();
                if (feedPanel != null && AutoScroll.movesOn(feedPanel)) advance();
            }
            MAIN.postDelayed(this, WATCH_MS);
        }
    };

    private static void interrupt() {
        if (interrupted) return;
        interrupted = true;
        if (playingNow()) player.pause();
        publish();
    }

    private static void resumeAfterInterrupt() {
        if (!interrupted || inCall()) return;
        interrupted = false;
        if (!userPaused && player != null && prepared) player.start();
        publish();
    }

    private static final AudioManager.OnAudioFocusChangeListener FOCUS = change -> {
        try {
            if (!away) return;
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                // Another app plays now.
                userPause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                interrupt();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                resumeAfterInterrupt();
            }
        } catch (Throwable error) {
            Safe.log("background play focus", error);
        }
    };

    private static void requestFocus() {
        if (audio == null) return;
        try {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setOnAudioFocusChangeListener(FOCUS, MAIN)
                        .build();
            }
            int result = audio.requestAudioFocus(focus);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) trace("audio focus not granted (" + result + "), playing anyway");
        } catch (Throwable error) {
            Safe.log("background play focus", error);
        }
    }

    private static void abandonFocus() {
        if (audio == null || focus == null) return;
        try {
            audio.abandonAudioFocusRequest(focus);
        } catch (Throwable ignored) { }
    }

    /** Headphones pulled out or Bluetooth gone: pause instead of playing through the speaker. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void watchHeadphones() {
        if (noisy != null) return;
        noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    userPause();
                } catch (Throwable error) {
                    Safe.log("background play", error);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(noisy, filter);
        } catch (Throwable error) {
            noisy = null;
            Safe.log("background play", error);
        }
    }

    private static void unwatchHeadphones() {
        if (noisy == null) return;
        try {
            app.unregisterReceiver(noisy);
        } catch (Throwable ignored) { }
        noisy = null;
    }

    private static boolean inCall() {
        if (audio == null) return false;
        int mode = audio.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
    }

    private static void holdLock() {
        if (lock == null) return;
        try {
            lock.acquire(LOCK_MS);
        } catch (Throwable error) {
            Safe.log("background play wake lock", error);
        }
    }

    private static void releaseLock() {
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (Throwable ignored) { }
        }
    }

    private static String id(Object shown) {
        Object value = shown == null ? null : Reflect.get(shown, "aid");
        return value == null ? null : String.valueOf(value);
    }

    private static Object invoke(Method method, Object target) {
        if (method == null || target == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable error) {
            return null;
        }
    }
}
