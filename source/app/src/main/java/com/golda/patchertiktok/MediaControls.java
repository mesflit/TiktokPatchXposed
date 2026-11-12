package com.golda.patchertiktok;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.golda.patchertiktok.I18n.S;

/**
 * While background play runs: a media notification and a media session, so the video can be
 * paused, played, skipped and stopped from the notification, the lock screen and headset buttons.
 * Everything here runs on the main thread.
 */
final class MediaControls {
    private static final String CHANNEL = "tiktokpatchxposed_background";
    private static final int ID = 0x7470_0b9;
    private static final String ACTION = "com.golda.patchertiktok.MEDIA";
    private static final String COMMAND = "command";
    private static final int TOGGLE = 1;
    private static final int NEXT = 2;
    private static final int STOP = 3;

    /** What the buttons do; supplied by {@link BackgroundPlay}. */
    interface Player {
        void play();

        void pause();

        void next();

        void stop();

        void seekTo(long position);
    }

    static final int PAUSED = 0;
    static final int PLAYING = 1;
    static final int BUFFERING = 2;

    private static Application app;
    private static Player player;
    private static MediaSession session;
    private static boolean registered;
    private static boolean shown;
    private static boolean playing;
    private static String title = "TikTok";
    private static String author = "";

    private MediaControls() { }

    static void attach(Application application, Player controls) {
        app = application;
        player = controls;
    }

    /**
     * Shows the controls, or brings them up to date, for the video {@code aweme}: whether it plays,
     * where it is and how long it is, so the system draws the progress itself from there.
     */
    static void show(Object aweme, int state, long position, long duration) {
        if (app == null || !Prefs.on(Prefs.MEDIA_CONTROLS)) return;
        if (aweme != null) {
            String desc = text(Reflect.call(aweme, "getDesc"));
            String name = text(Reflect.call(Reflect.call(aweme, "getAuthor"), "getNickname"));
            title = desc.isEmpty() ? (name.isEmpty() ? "TikTok" : name) : desc;
            author = desc.isEmpty() ? "TikTok" : name;
        }
        // While a video loads, the button still offers pause.
        playing = state != PAUSED;
        if (!registered) register();
        if (session == null) session = createSession();
        if (session != null) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, author);
            if (duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            session.setMetadata(metadata.build());
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_STOP;
            if (duration > 0 && state != BUFFERING) actions |= PlaybackState.ACTION_SEEK_TO;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    // Newer Android draws the controls from the session; stop is its extra button.
                    .addCustomAction(new PlaybackState.CustomAction.Builder("stop", I18n.get(S.MEDIA_STOP),
                            android.R.drawable.ic_menu_close_clear_cancel).build())
                    .setState(state == PLAYING ? PlaybackState.STATE_PLAYING
                                    : state == BUFFERING ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED,
                            Math.max(0, position), state == PLAYING ? 1f : 0f)
                    .build());
            session.setActive(true);
        }
        post();
    }

    static void hide() {
        if (app == null) return;
        if (shown) {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(ID);
            shown = false;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }

    // Runs inside TikTok, which holds its own POST_NOTIFICATIONS permission.
    @SuppressLint({"NotificationPermission", "MissingPermission"})
    private static void post() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, I18n.get(S.MEDIA_CHANNEL),
                    NotificationManager.IMPORTANCE_LOW));
            Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            if (session != null) style.setMediaSession(session.getSessionToken());
            Notification.Builder builder = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText(author)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setOngoing(playing)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .setStyle(style)
                    .setDeleteIntent(command(STOP))
                    .addAction(action(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                            I18n.get(playing ? S.MEDIA_PAUSE : S.MEDIA_PLAY), TOGGLE))
                    .addAction(action(android.R.drawable.ic_media_next, I18n.get(S.MEDIA_NEXT), NEXT))
                    .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, I18n.get(S.MEDIA_STOP), STOP));
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch != null) {
                builder.setContentIntent(PendingIntent.getActivity(app, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            manager.notify(ID, builder.build());
            shown = true;
        } catch (Throwable error) {
            // Notifications may be off for TikTok; playback goes on without them.
            Safe.log("media notification", error);
        }
    }

    private static Notification.Action action(int icon, String label, int command) {
        return new Notification.Action.Builder(Icon.createWithResource("android", icon), label, command(command)).build();
    }

    private static PendingIntent command(int command) {
        Intent intent = new Intent(ACTION).setPackage(app.getPackageName()).putExtra(COMMAND, command);
        return PendingIntent.getBroadcast(app, command, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void register() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    run(intent.getIntExtra(COMMAND, 0));
                } catch (Throwable error) {
                    Safe.log("media controls", error);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
        registered = true;
    }

    private static void run(int command) {
        if (player == null) return;
        if (command == TOGGLE) {
            if (playing) player.pause();
            else player.play();
        } else if (command == NEXT) {
            player.next();
        } else if (command == STOP) {
            player.stop();
        }
    }

    private static MediaSession createSession() {
        try {
            MediaSession created = new MediaSession(app, "TiktokPatchXposed");
            created.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { safe(TOGGLE, false); }

                @Override public void onPause() { safe(TOGGLE, true); }

                @Override public void onSkipToNext() { safe(NEXT, false); }

                @Override public void onStop() { safe(STOP, false); }

                @Override public void onCustomAction(String action, android.os.Bundle extras) {
                    if ("stop".equals(action)) safe(STOP, false);
                }

                @Override public void onSeekTo(long position) {
                    try {
                        if (player != null) player.seekTo(position);
                    } catch (Throwable error) {
                        Safe.log("media controls", error);
                    }
                }
            }, new Handler(Looper.getMainLooper()));
            return created;
        } catch (Throwable error) {
            Safe.log("media session", error);
            return null;
        }
    }

    /** A session button: play and pause are told apart, the notification's toggle is not. */
    private static void safe(int command, boolean pause) {
        try {
            if (player == null) return;
            if (command == TOGGLE) {
                if (pause) player.pause();
                else player.play();
            } else {
                run(command);
            }
        } catch (Throwable error) {
            Safe.log("media controls", error);
        }
    }

    private static String text(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace('\n', ' ').trim();
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
