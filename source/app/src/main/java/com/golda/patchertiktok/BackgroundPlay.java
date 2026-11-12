package com.golda.patchertiktok;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Feed videos keep playing when TikTok is put away: with the screen turned off, or with another
 * app on the screen. TikTok pauses its player when its screen stops; while the switch is on and a
 * feed video was playing, those pauses are skipped, and if one still got through, the player is
 * started again. Auto-scroll keeps moving on meanwhile (see {@link AutoScroll}).
 *
 * <p>The pause can come from the feed panel's lifecycle, the panel's player ability, its player
 * manager or the video engine itself; all of them are guarded, each only while TikTok is away.
 * When auto-scroll moves to the next video, pauses go through for a moment, so the old video
 * stops and only the new one plays. A phone call, or another app taking the audio, ends it.
 */
final class BackgroundPlay {
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String ABILITY = "com.ss.android.ugc.feed.platform.panel.player.IPlayerComponentAbility";
    private static final String ENGINE = "com.ss.ttvideoengine.TTVideoEngine";
    private static final long WATCH_MS = 1_000L;
    /** While a feed moves to the next video, the old one may pause. */
    private static final long MOVE_MS = 2_500L;
    /** The longest a session keeps the processor awake with the screen off. */
    private static final long LOCK_MS = 3 * 60 * 60 * 1_000L;
    private static final String[] RESUME_NAMES = {"resume", "resumePlay", "tryResumePlay", "tryResume", "resumeVideo", "play", "start", "startPlay"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Methods and classes already guarded. */
    private static final Set<Object> HOOKED = Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean away;
    private static volatile long movingUntil;
    /** Whether a feed video was playing when TikTok last paused it, and when. */
    private static volatile boolean playingAtPause = true;
    private static volatile long pausedAt;
    private static Method playerAbility;
    private static Method currentAweme;
    /** Paused from the notification, the lock screen or a headset: TikTok's pauses go through and nothing restarts. */
    private static volatile boolean userPaused;
    /** The video the notification shows. */
    private static String shownId;
    private static WeakReference<Object> panel = new WeakReference<>(null);
    /** The engine that plays the current video. */
    private static WeakReference<Object> engine = new WeakReference<>(null);
    private static PowerManager power;
    private static AudioManager audio;
    private static PowerManager.WakeLock lock;
    private static long lastPosition = -1;
    private static int stalls;
    /** Restart attempts since the video last moved; a few, then it waits for a change. */
    private static int resumes;

    private BackgroundPlay() { }

    private static final java.util.concurrent.atomic.AtomicInteger TRACED = new java.util.concurrent.atomic.AtomicInteger();
    /** Ticks still to trace after TikTok was put away or the feed moved on. */
    private static int traceTicks;

    /** A line about background play for bug reports; capped, so a long night in the background does not flood the log. */
    static void trace(String what) {
        if (TRACED.incrementAndGet() <= 400) RuntimeLog.log("bg: " + what);
    }

    /** True while TikTok is put away and its feed video is kept playing. */
    static boolean away() {
        return away;
    }

    /** A feed is moving on to its next video: let the old one pause. */
    static void moving() {
        movingUntil = SystemClock.uptimeMillis() + MOVE_MS;
        if (away) traceTicks = 10;
        // The old video's engine was held on; now it may go.
        flush();
    }

    /** The surface TikTok gave the engine last, how, and the stand-in used while the screen is gone. */
    private static volatile Object surfaceOwner;
    private static volatile Method surfaceSetter;
    private static volatile android.view.Surface lastSurface;
    private static volatile Object lastHolder;
    private static Object standIn;
    private static Handler drain;

    /** Notes every surface or surface holder TikTok hands an engine; found by parameter type, so names do not matter. */
    private static int watchSurfaces(Class<?> type) {
        if (!HOOKED.add("surfaces:" + type.getName())) return 0;
        int hooks = 0;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : Reflect.methods(current)) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                boolean surface = parameters[0] == android.view.Surface.class;
                boolean holder = parameters[0] == android.view.SurfaceHolder.class;
                if (!surface && !holder || !HOOKED.add(method)) continue;
                if (Hooks.tryHook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        // Our own stand-in going in is not TikTok's choice.
                        if (param.args[0] != null && param.args[0] == currentStandInSurface()) return;
                        if (surface) {
                            if (param.args[0] != null) {
                                surfaceOwner = param.thisObject;
                                surfaceSetter = method;
                                lastSurface = (android.view.Surface) param.args[0];
                            }
                        } else if (param.args[0] != null) {
                            surfaceOwner = param.thisObject;
                            lastHolder = param.args[0];
                        }
                        if (away) trace("engine " + method.getName() + "(" + (param.args[0] == null ? "null" : parameters[0].getSimpleName()) + ")");
                    }
                })) hooks++;
            }
        }
        return hooks;
    }

    private static android.view.Surface currentStandInSurface() {
        Object reader = standIn;
        if (reader == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            return ((android.media.ImageReader) reader).getSurface();
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * A surface nobody sees: the decoder keeps drawing into it, each frame thrown away at once.
     * Android 10 and later; earlier there is no stand-in.
     */
    private static android.view.Surface standIn() {
        if (Build.VERSION.SDK_INT < 29) return null;
        if (standIn == null) {
            if (drain == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("TiktokPatchXposed-frames");
                thread.start();
                drain = new Handler(thread.getLooper());
            }
            android.media.ImageReader reader = android.media.ImageReader.newInstance(720, 1280,
                    android.graphics.ImageFormat.PRIVATE, 4, android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            reader.setOnImageAvailableListener(source -> {
                try {
                    android.media.Image image = source.acquireLatestImage();
                    if (image != null) image.close();
                } catch (Throwable ignored) {
                    // A frame missed; the next one comes.
                }
            }, drain);
            standIn = reader;
        }
        return ((android.media.ImageReader) standIn).getSurface();
    }

    /**
     * A video surface is going away while TikTok is put away. If it is the one the playing engine
     * draws into, the engine moves to the stand-in first and TikTok is not told, so it does not
     * stop its player. Other surfaces (preloaded videos) go as usual.
     */
    private static void installSurfaceGuard() {
        Hooks.Hook guard = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!blocking() || !(param.thisObject instanceof android.view.SurfaceView)) return;
                android.view.SurfaceHolder holder = ((android.view.SurfaceView) param.thisObject).getHolder();
                android.view.Surface surface = holder == null ? null : holder.getSurface();
                boolean ours = surface != null && surface == lastSurface || holder != null && holder == lastHolder;
                if (!ours) return;
                Object owner = surfaceOwner;
                Method setter = surfaceSetter != null ? surfaceSetter : surfaceMethod(owner);
                android.view.Surface replacement = standIn();
                if (owner == null || setter == null || replacement == null) {
                    trace("surface going, no way to move the video (owner=" + (owner != null) + " setter=" + (setter != null) + ")");
                    return;
                }
                try {
                    setter.invoke(owner, replacement);
                    param.setResult(null);
                    trace("video moved to a hidden surface with " + setter.getName());
                } catch (Throwable error) {
                    trace("moving the video failed: " + error);
                }
            }
        };
        int hooks = Hooks.hookAll(android.view.SurfaceView.class, "notifySurfaceDestroyed", guard);
        RuntimeLog.log("background play: surface guards=" + hooks);
    }

    /** A method of {@code owner} that takes a surface, when TikTok used a holder instead. */
    private static Method surfaceMethod(Object owner) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : Reflect.methods(type)) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == android.view.Surface.class && method.getReturnType() == void.class
                        && !Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /** Back on the screen: TikTok sets up its own surface again; the stand-in goes once it no longer draws. */
    private static void dropStandIn() {
        Object reader = standIn;
        if (reader == null) return;
        standIn = null;
        MAIN.postDelayed(Safe.run("background stand-in", () -> ((android.media.ImageReader) reader).close()), 3_000L);
    }

    /** Engine calls that end playback: TikTok frees the player when its screen stops. */
    private static boolean ends(String name) {
        return "stop".equals(name) || "release".equals(name) || "releaseAsync".equals(name) || "releaseSync".equals(name);
    }

    /** Calls held back while away, made once TikTok is back or the feed moves on. */
    private static final java.util.List<Object[]> DEFERRED = new java.util.ArrayList<>();

    private static void defer(Method method, Object target, Object[] args) {
        synchronized (DEFERRED) {
            DEFERRED.add(new Object[]{method, target, args.clone()});
        }
    }

    /** Whether TikTok let go of the engine that plays: then its own player reports nothing useful. */
    private static boolean detached() {
        synchronized (DEFERRED) {
            return !DEFERRED.isEmpty();
        }
    }

    /** Makes the held-back calls now; the hooks let them through since this runs when not blocking. */
    private static void flush() {
        java.util.List<Object[]> calls;
        synchronized (DEFERRED) {
            if (DEFERRED.isEmpty()) return;
            calls = new java.util.ArrayList<>(DEFERRED);
            DEFERRED.clear();
        }
        for (Object[] call : calls) {
            try {
                ((Method) call[0]).invoke(call[1], (Object[]) call[2]);
            } catch (Throwable error) {
                Safe.log("background play release", error);
            }
        }
        trace("made " + calls.size() + " held-back engine call(s)");
    }

    /** Every simple getter of the playing engine with its value, to learn which one is its clock. */
    private static void dumpEngine(String when) {
        Object current = engine.get();
        if (!away || current == null) return;
        StringBuilder out = new StringBuilder("engine " + current.getClass().getName() + " " + when + ":");
        int count = 0;
        for (Class<?> type = current.getClass(); type != null && type != Object.class && count < 60; type = type.getSuperclass()) {
            for (Method method : Reflect.methods(type)) {
                Class<?> result = method.getReturnType();
                if (method.getParameterTypes().length != 0 || Modifier.isStatic(method.getModifiers())
                        || !(result == int.class || result == long.class || result == boolean.class || result == float.class)) continue;
                String name = method.getName();
                if (name.startsWith("set") || name.startsWith("release") || name.startsWith("stop") || name.startsWith("play")
                        || name.startsWith("pause") || name.startsWith("prepare") || name.startsWith("start")) continue;
                try {
                    method.setAccessible(true);
                    out.append(' ').append(name).append('=').append(method.invoke(current));
                } catch (Throwable error) {
                    out.append(' ').append(name).append("=!");
                }
                if (++count >= 60) break;
            }
        }
        trace(out.toString());
    }

    /** Surface events of video views while away: when the picture's surface goes, the player may stop. */
    private static void traceSurfaces() {
        Hooks.Hook surface = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (away) trace(param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName() + caller());
            }
        };
        int hooks = 0;
        for (String name : new String[]{"releaseSurfaceTexture", "destroyHardwareLayer", "onDetachedFromWindowInternal"}) {
            hooks += Hooks.hookAll(android.view.TextureView.class, name, surface);
        }
        for (String name : new String[]{"notifySurfaceDestroyed", "onWindowVisibilityChanged", "onDetachedFromWindow"}) {
            hooks += Hooks.hookAll(android.view.SurfaceView.class, name, surface);
        }
        RuntimeLog.log("background play: surface traces=" + hooks);
    }

    /** The first TikTok frames of the current call, for the log. */
    private static String caller() {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String type = frame.getClassName();
            if (type.startsWith("com.golda.") || type.startsWith("java.") || type.startsWith("dalvik.")
                    || type.startsWith("android.") || type.contains("xposed") || type.contains("lsposed") || type.startsWith("LSP")) continue;
            out.append(shown == 0 ? " from " : " < ").append(type.substring(type.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName());
            if (++shown == 16) break;
        }
        return out.toString();
    }

    /**
     * The position of the video that plays. Once TikTok let go of its player while away, the engine
     * itself is asked; otherwise TikTok's player, through {@code fromPlayer}.
     */
    static Object position(Object fromPlayer) {
        if (!away || !detached()) return fromPlayer;
        Object current = engine.get();
        Object value = Reflect.call(current, "getCurrentPlaybackTime");
        if (!(value instanceof Number)) value = Reflect.call(current, "getCurrentPosition");
        return value instanceof Number ? value : fromPlayer;
    }

    /** Hooks; each reads the switch on every call, so they go in before the settings load. */
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
            hooks += guardLifecycle(panelType);
            for (Method method : Reflect.methods(panelType)) {
                if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
                Hooks.tryHook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        rendered(param.thisObject);
                    }
                });
            }
        }
        Class<?> engineType = Hooks.findClass(ENGINE, loader);
        if (engineType != null) hooks += watchSurfaces(engineType);
        if (engineType != null) {
            for (Method method : Reflect.methods(engineType)) {
                if (method.getReturnType() != void.class || Modifier.isAbstract(method.getModifiers())) continue;
                if ("play".equals(method.getName()) && method.getParameterTypes().length == 0) {
                    if (Hooks.tryHook(method, new Hooks.Hook() {
                        @Override protected void after(Hooks.Call param) {
                            boolean moving = SystemClock.uptimeMillis() < movingUntil;
                            if (away) trace("engine play " + Integer.toHexString(System.identityHashCode(param.thisObject))
                                    + (moving ? " (moving)" : "") + (param.thisObject == engine.get() ? " same" : " other"));
                            if (!away || moving) engine = new WeakReference<>(param.thisObject);
                            watchSurfaces(param.thisObject.getClass());
                        }
                    })) hooks++;
                } else if (ends(method.getName())) {
                    if (Hooks.tryHook(method, new Hooks.Hook() {
                        @Override protected void before(Hooks.Call param) {
                            if (!away || param.thisObject != engine.get()) return;
                            if (blocking()) {
                                // TikTok frees its player once put away; this one keeps playing until TikTok is back.
                                defer(method, param.thisObject, param.args);
                                param.setResult(null);
                                trace("engine " + method.getName() + " deferred" + caller());
                            } else {
                                trace("engine " + method.getName() + caller());
                            }
                        }
                    })) hooks++;
                } else if (method.getName().toLowerCase(Locale.ROOT).startsWith("setsurface") || "setDisplay".equals(method.getName())) {
                    Hooks.tryHook(method, new Hooks.Hook() {
                        @Override protected void before(Hooks.Call param) {
                            if (away && param.thisObject == engine.get()) {
                                trace("engine " + method.getName() + "(" + (param.args.length > 0 && param.args[0] == null ? "null" : "surface") + ")");
                            }
                        }
                    });
                } else if ("pause".equals(method.getName())) {
                    if (Hooks.tryHook(method, new Hooks.Hook() {
                        @Override protected void before(Hooks.Call param) {
                            // Only the engine of the current video; preloaded ones pause as usual.
                            if (param.thisObject != engine.get()) return;
                            pausing();
                            if (blocking()) {
                                param.setResult(null);
                                trace("engine pause skipped");
                            }
                        }
                    })) hooks++;
                }
            }
        }
        RuntimeLog.log("background play: hooks=" + hooks + (engineType == null ? ", no engine" : ""));
    }

    /** Services and the screen listeners, once the Application exists. */
    static void attach(Application app) {
        power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (power != null && app.checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TiktokPatchXposed:background");
            lock.setReferenceCounted(false);
        }
        MediaControls.attach(app, new MediaControls.Player() {
            @Override public void play() { userPlay(); }

            @Override public void pause() { userPause(); }

            @Override public void next() { userNext(); }

            @Override public void stop() { userStop(); }
        });
        Foreground.listenAway(BackgroundPlay::putAway);
        Foreground.listen(BackgroundPlay::back);
        watchFocus();
        try {
            installSurfaceGuard();
        } catch (Throwable error) {
            Safe.log("background surface guard", error);
        }
        try {
            traceSurfaces();
        } catch (Throwable error) {
            Safe.log("background surface trace", error);
        }
    }

    /** Skips the panel's own pause and stop while away; found by name on the panel and its subclasses. */
    private static int guardLifecycle(Class<?> type) {
        int hooks = 0;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : Reflect.methods(current)) {
                if (method.getReturnType() != void.class || method.getParameterTypes().length != 0
                        || Modifier.isAbstract(method.getModifiers()) || !lifecycle(method.getName())) continue;
                if (guard(method)) hooks++;
            }
        }
        return hooks;
    }

    private static boolean lifecycle(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("onpause") || lower.equals("onstop") || lower.equals("onstopped")
                || lower.equals("onfragmentpause") || lower.equals("onfragmentstop") || pause(lower);
    }

    /** A player call that pauses: pause, pausePlay, tryPause... */
    private static boolean pause(String lower) {
        return lower.startsWith("pause") || lower.startsWith("trypause") || lower.equals("onpauseplay");
    }

    private static boolean guard(Method method) {
        if (!HOOKED.add(method)) return false;
        return Hooks.tryHook(method, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                pausing();
                if (blocking()) {
                    param.setResult(null);
                    trace("skipped " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
                }
            }
        });
    }

    /** A feed video started: the panel and its player get their guards on first sight. */
    private static void rendered(Object feedPanel) {
        panel = new WeakReference<>(feedPanel);
        if (!HOOKED.add(feedPanel.getClass())) return;
        int hooks = guardLifecycle(feedPanel.getClass());
        Object ability = invoke(playerAbility, feedPanel);
        if (ability != null && !Proxy.isProxyClass(ability.getClass())) hooks += guardPlayer(ability.getClass());
        Object manager = Reflect.call(feedPanel, "getPlayerManager");
        if (manager != null && !Proxy.isProxyClass(manager.getClass())) hooks += guardPlayer(manager.getClass());
        RuntimeLog.log("background play: " + feedPanel.getClass().getSimpleName() + " guards=" + hooks);
    }

    private static int guardPlayer(Class<?> type) {
        if (!HOOKED.add(type)) return 0;
        int hooks = 0;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : Reflect.methods(current)) {
                if (method.getReturnType() != void.class || Modifier.isAbstract(method.getModifiers())
                        || !pause(method.getName().toLowerCase(Locale.ROOT))) continue;
                if (guard(method)) hooks++;
            }
        }
        return hooks;
    }

    /**
     * TikTok is about to pause. With the screen already off, that is TikTok being put away: the
     * video goes on, if it was playing. Otherwise it is noted whether it played, for {@link #putAway}.
     */
    private static void pausing() {
        if (away || !Prefs.on(Prefs.BACKGROUND_PLAY) || panel.get() == null || inCall()) return;
        boolean playing = playing();
        if (power != null && !power.isInteractive()) {
            if (playing) start();
            return;
        }
        long now = SystemClock.uptimeMillis();
        // The first pause of a burst decides; TikTok pauses the same video several times.
        if (now - pausedAt > 1_000L) playingAtPause = playing;
        pausedAt = now;
    }

    private static boolean blocking() {
        return away && !userPaused && SystemClock.uptimeMillis() >= movingUntil && Prefs.on(Prefs.BACKGROUND_PLAY) && !inCall();
    }

    /** No TikTok screen is shown any more: another app took the screen, or it turned off. */
    private static void putAway() {
        Object feedPanel = panel.get();
        if (away || !Prefs.on(Prefs.BACKGROUND_PLAY) || feedPanel == null || inCall()) return;
        // Only when the feed itself was on the screen, not a chat or a page opened over it.
        Object activity = Reflect.call(feedPanel, "getActivity");
        if (!(activity instanceof android.app.Activity)) activity = Reflect.get(feedPanel, "activity");
        if (activity instanceof android.app.Activity && activity != Foreground.lastShown()) return;
        boolean wasPlaying = SystemClock.uptimeMillis() - pausedAt < 5_000L ? playingAtPause : playing();
        if (!wasPlaying) return;
        start();
        // TikTok already paused in its onPause; start it again once its screen has stopped.
        MAIN.postDelayed(Safe.run("background play", () -> {
            if (away) resume();
        }), 400L);
    }

    private static void start() {
        away = true;
        userPaused = false;
        shownId = null;
        lastPosition = -1;
        stalls = 0;
        resumes = 0;
        holdLock();
        traceTicks = 10;
        MAIN.postDelayed(Safe.run("background trace", () -> dumpEngine("at start")), 300L);
        MAIN.postDelayed(Safe.run("background trace", () -> dumpEngine("3s later")), 3_300L);
        MAIN.removeCallbacks(WATCH);
        MAIN.postDelayed(WATCH, WATCH_MS);
        // A pause can come from the player's own thread; auto-scroll and the notification live on the main one.
        MAIN.post(Safe.run("background auto-scroll", AutoScroll::putAway));
        MAIN.post(Safe.run("media controls", () -> notifyNow(true)));
        RuntimeLog.log("background play: on");
    }

    /** A TikTok screen is back; TikTok resumes on its own. */
    private static void back() {
        if (!away) return;
        stop();
        RuntimeLog.log("background play: back");
    }

    private static void stop() {
        away = false;
        userPaused = false;
        flush();
        dropStandIn();
        MAIN.removeCallbacks(WATCH);
        releaseLock();
        MAIN.post(Safe.run("media controls", MediaControls::hide));
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

    /** The notification follows the current video; {@code force} redraws it even when it has not changed. */
    private static void notifyNow(boolean force) {
        if (!away) return;
        Object feedPanel = panel.get();
        Object aweme = feedPanel == null ? null : invoke(currentAweme, feedPanel);
        Object aid = aweme == null ? null : Reflect.get(aweme, "aid");
        String id = aid == null ? null : String.valueOf(aid);
        if (!force && (id == null || id.equals(shownId))) return;
        shownId = id;
        MediaControls.show(aweme, !userPaused);
    }

    private static void userPause() {
        if (!away) return;
        userPaused = true;
        pausePlayer();
        releaseLock();
        notifyNow(true);
    }

    private static void userPlay() {
        if (!away) return;
        userPaused = false;
        stalls = 0;
        lastPosition = -1;
        holdLock();
        resume();
        notifyNow(true);
    }

    private static void userNext() {
        if (!away) return;
        boolean wasPaused = userPaused;
        userPaused = false;
        if (wasPaused) holdLock();
        AutoScroll.skip();
        MAIN.postDelayed(Safe.run("media controls", () -> notifyNow(true)), 1_600L);
    }

    /** The stop button: the video pauses and the controls go away, until TikTok is opened again. */
    private static void userStop() {
        if (!away) return;
        userPaused = true;
        pausePlayer();
        stop();
    }

    private static final String[] PAUSE_NAMES = {"pause", "pausePlay", "tryPausePlay", "pauseVideo", "tryPause"};

    private static void pausePlayer() {
        Object feedPanel = panel.get();
        if (feedPanel != null && (call(invoke(playerAbility, feedPanel), PAUSE_NAMES)
                || call(Reflect.call(feedPanel, "getPlayerManager"), PAUSE_NAMES))) return;
        Object current = engine.get();
        if (current != null) Reflect.call(current, "pause");
    }

    private static boolean call(Object player, String[] names) {
        if (player == null) return false;
        for (String name : names) {
            Method method = Reflect.method(player.getClass(), name);
            if (method == null || method.getReturnType() != void.class) continue;
            try {
                method.invoke(player);
                return true;
            } catch (Throwable ignored) {
                // The next name.
            }
        }
        return false;
    }

    /** While away: when the video stands still, TikTok paused it some other way; play it again. */
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
            if (!Prefs.on(Prefs.BACKGROUND_PLAY) || inCall()) {
                stop();
                return;
            }
            notifyNow(false);
            if (traceTicks > 0) {
                traceTicks--;
                Object tracedPanel = panel.get();
                Object aweme = tracedPanel == null ? null : invoke(currentAweme, tracedPanel);
                Object current = engine.get();
                trace("tick id=" + (aweme == null ? null : Reflect.get(aweme, "aid"))
                        + " pos=" + (tracedPanel == null ? null : Reflect.call(invoke(playerAbility, tracedPanel), "getCurrentPosition"))
                        + " playing=" + playing() + " engine=" + (current == null ? "none" : Reflect.call(current, "getPlaybackState")
                                + "/" + Reflect.call(current, "getCurrentPlaybackTime")) + (detached() ? " held" : "")
                        + (userPaused ? " userPaused" : ""));
            }
            if (userPaused) {
                MAIN.postDelayed(this, WATCH_MS);
                return;
            }
            Object feedPanel = panel.get();
            Object value = position(feedPanel == null ? null : Reflect.call(invoke(playerAbility, feedPanel), "getCurrentPosition"));
            if (value instanceof Number && SystemClock.uptimeMillis() >= movingUntil) {
                long position = ((Number) value).longValue();
                if (position == lastPosition) {
                    if (++stalls >= 2 && resumes < 5) {
                        resumes++;
                        trace("stalled at " + position + ", resuming");
                        resume();
                        stalls = 0;
                    }
                } else {
                    stalls = 0;
                    resumes = 0;
                }
                lastPosition = position;
            }
            MAIN.postDelayed(this, WATCH_MS);
        }
    };

    /** Starts the current video again: through the panel's player, its manager, then the engine. */
    static void resume() {
        Object feedPanel = panel.get();
        if (feedPanel == null) return;
        Object held = engine.get();
        if (detached() && held != null) {
            Reflect.call(held, "play");
            note("engine.play (held)");
            return;
        }
        if (resume(invoke(playerAbility, feedPanel), "ability")) return;
        if (resume(Reflect.call(feedPanel, "getPlayerManager"), "manager")) return;
        Object current = engine.get();
        if (current != null && Reflect.method(current.getClass(), "play") != null) {
            Reflect.call(current, "play");
            note("engine.play");
        } else {
            trace("resume: no way to start the player");
        }
    }

    private static boolean resume(Object player, String where) {
        if (player == null) return false;
        for (String name : RESUME_NAMES) {
            Method method = Reflect.method(player.getClass(), name);
            if (method == null || method.getReturnType() != void.class) continue;
            try {
                method.invoke(player);
                note(where + "." + name);
                return true;
            } catch (Throwable ignored) {
                // The next name, or the next player.
            }
        }
        return false;
    }

    private static void note(String how) {
        trace("resumed with " + how);
    }

    /** Whether the feed video plays now; when nothing tells, it is taken as playing. */
    private static boolean playing() {
        Object feedPanel = panel.get();
        Object ability = feedPanel == null ? null : invoke(playerAbility, feedPanel);
        Object value = Reflect.call(ability, "isPlaying");
        if (value instanceof Boolean) return (Boolean) value;
        Object current = engine.get();
        Object state = Reflect.call(current, "getPlaybackState");
        // TTVideoEngine: 1 is playing.
        if (state instanceof Number) return ((Number) state).intValue() == 1;
        return true;
    }

    private static boolean inCall() {
        if (audio == null) return false;
        int mode = audio.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
    }

    /**
     * Another app taking the audio (music, a call, navigation for long) ends background play, so
     * TikTok's own pause goes through. The listener is the one TikTok hands to the audio manager.
     */
    private static void watchFocus() {
        Hooks.Hook request = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                Object listener = param.args.length > 0 ? param.args[0] : null;
                if (listener instanceof android.media.AudioFocusRequest) listener = Reflect.get(listener, "mFocusListener");
                if (listener instanceof AudioManager.OnAudioFocusChangeListener) watch(listener.getClass());
            }
        };
        int hooks = Hooks.hookAll(AudioManager.class, "requestAudioFocus", request);
        RuntimeLog.log("background play: focus hooks=" + hooks);
    }

    private static void watch(Class<?> type) {
        if (!HOOKED.add(type)) return;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : Reflect.methods(current)) {
                if (!"onAudioFocusChange".equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) continue;
                Hooks.tryHook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        Object change = param.args.length > 0 ? param.args[0] : null;
                        // A short loss (a notification sound, navigation) passes; a full loss ends it.
                        if (away && change instanceof Integer && (Integer) change == AudioManager.AUDIOFOCUS_LOSS) {
                            stop();
                            RuntimeLog.log("background play: audio taken by another app");
                        }
                    }
                });
                return;
            }
        }
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
