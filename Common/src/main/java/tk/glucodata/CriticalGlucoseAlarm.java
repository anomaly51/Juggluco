package tk.glucodata;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.UUID;

/**
 * Session-aware owner of urgent glucose presentation and sound.
 *
 * <p>Android does not offer an unrevokable medical-alarm entitlement. This
 * class therefore combines an alarm-usage notification channel, a
 * full-screen intent where the OS permits it, and an application-owned alarm
 * loop. It never changes global volume or the user's Do Not Disturb mode.</p>
 */
final class CriticalGlucoseAlarm {
    static final String SOURCE_ACTUAL = "actual";
    static final String SOURCE_PREDICTIVE = "predictive";
    static final String SOURCE_TEST = "test";
    static final String DIRECTION_LOW = "low";
    static final String DIRECTION_HIGH = "high";

    private static final String PREFS = "critical_glucose_alarm_v1";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_DIRECTION = "direction";
    private static final String KEY_PRIORITY = "priority";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BODY = "body";
    private static final String KEY_VALUE = "value";
    private static final String KEY_ANCHOR = "anchor_ms";
    private static final String KEY_EXPIRES = "expires_at_ms";
    private static final String KEY_SNOOZE = "snooze_until_ms";
    private static final String KEY_SOUND_RES = "sound_res";

    private static final int ACTUAL_LOW_ID = 8_401;
    private static final int ACTUAL_HIGH_ID = 8_402;
    private static final int PREDICTIVE_LOW_ID = 8_403;
    private static final int PREDICTIVE_HIGH_ID = 8_404;
    private static final int TEST_ID = 8_405;
    private static final int[] CRITICAL_NOTIFICATION_IDS = {
            ACTUAL_LOW_ID, ACTUAL_HIGH_ID, PREDICTIVE_LOW_ID,
            PREDICTIVE_HIGH_ID, TEST_ID
    };
    private static final long ACTUAL_MAX_LIFETIME_MS = 24L * 60L * 60_000L;
    private static final long TEST_LIFETIME_MS = 2L * 60_000L;
    private static final long[] LOW_VIBRATION =
            {0L, 900L, 350L, 900L, 350L, 1_600L};
    private static final long[] HIGH_VIBRATION =
            {0L, 450L, 250L, 450L, 250L, 900L};
    private static final AudioAttributes ALARM_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Session active;
    private static Ringtone ringtone;
    private static Vibrator vibrator;
    private static AudioManager audioManager;
    private static AudioFocusRequest audioFocusRequest;
    private static Runnable delayedLoop;
    private static boolean initialized;

    private CriticalGlucoseAlarm() {}

    static synchronized void ensureChannels(Context context) {
        if (context == null || Applic.isWearable) return;
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                boolean bypass = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || manager.isNotificationPolicyAccessGranted();
                createChannel(app, manager,
                        CriticalAlarmDiagnostics.ACTUAL_LOW_CHANNEL_ID,
                        R.string.critical_alarm_channel_actual_low,
                        R.string.critical_alarm_channel_actual_low_description,
                        R.raw.verylow, LOW_VIBRATION, bypass);
                createChannel(app, manager,
                        CriticalAlarmDiagnostics.ACTUAL_HIGH_CHANNEL_ID,
                        R.string.critical_alarm_channel_actual_high,
                        R.string.critical_alarm_channel_actual_high_description,
                        R.raw.veryhigh, HIGH_VIBRATION, bypass);
                createChannel(app, manager,
                        CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID,
                        R.string.critical_alarm_channel_predictive_low,
                        R.string.critical_alarm_channel_predictive_low_description,
                        R.raw.lowsoon, LOW_VIBRATION, bypass);
                createChannel(app, manager,
                        CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID,
                        R.string.critical_alarm_channel_predictive_high,
                        R.string.critical_alarm_channel_predictive_high_description,
                        R.raw.highsoon, HIGH_VIBRATION, bypass);
            }
        }
        CriticalAlarmDiagnostics.registerTestHook(CriticalGlucoseAlarm::showTest);
        if (!initialized) {
            initialized = true;
            restoreIfNeeded(app);
        }
    }

    private static void createChannel(Context context,
            NotificationManager manager, String id, int nameRes,
            int descriptionRes, int soundRes, long[] pattern,
            boolean bypassDnd) {
        NotificationChannel channel = new NotificationChannel(id,
                context.getString(nameRes), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(descriptionRes));
        channel.setSound(resourceUri(context, soundRes), ALARM_ATTRIBUTES);
        channel.enableVibration(true);
        channel.setVibrationPattern(pattern);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setBypassDnd(bypassDnd);
        manager.createNotificationChannel(channel);
    }

    /** Starts or refreshes an independently detected native actual alarm. */
    static synchronized boolean showActual(Context context, int kind,
            float glucoseValue, String message, boolean trigger) {
        if (context == null || Applic.isWearable || !isActualKind(kind)) {
            return false;
        }
        Context app = context.getApplicationContext();
        ensureChannels(app);
        boolean low = kind == 0 || kind == 5;
        boolean severe = kind == 5 || kind == 6;
        Session current = current(app, System.currentTimeMillis());
        if (!trigger && current == null) return false;

        Session incoming = new Session();
        incoming.source = SOURCE_ACTUAL;
        incoming.direction = low ? DIRECTION_LOW : DIRECTION_HIGH;
        incoming.priority = severe
                ? CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE
                : CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL;
        incoming.title = app.getString(low
                ? severe ? R.string.critical_alarm_actual_very_low_title
                        : R.string.critical_alarm_actual_low_title
                : severe ? R.string.critical_alarm_actual_very_high_title
                        : R.string.critical_alarm_actual_high_title);
        incoming.body = message == null ? "" : message;
        // Keep this delivery boundary independent from Notify's process-wide
        // audio initialization so recovery/fallback decisions remain usable
        // during cold start and in API-level tests.
        incoming.value = glucoseText(app, glucoseValue);
        incoming.anchorMs = System.currentTimeMillis();
        incoming.expiresAtMs = incoming.anchorMs + ACTUAL_MAX_LIFETIME_MS;
        incoming.soundRes = low ? R.raw.verylow : R.raw.veryhigh;
        boolean replacesNonActual = current != null
                && !SOURCE_ACTUAL.equals(current.source)
                && incoming.priority > current.priority;
        boolean reversesActualDirection = current != null
                && SOURCE_ACTUAL.equals(current.source)
                && !incoming.direction.equals(current.direction);
        return acceptAndPresent(app, current, incoming,
                trigger || replacesNonActual || reversesActualDirection);
    }

    /**
     * Posts an approved likely forecast. Possible/interval-only forecasts stay
     * in the ordinary predictive notifier and never invoke this method.
     */
    static synchronized boolean showPredictive(Context context, boolean low,
            String title, String body, String glucose, long anchorMs,
            long crossingAtMs, long timeoutMs) {
        if (context == null || Applic.isWearable || timeoutMs <= 0L) {
            return false;
        }
        Context app = context.getApplicationContext();
        ensureChannels(app);
        String channel = low
                ? CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID
                : CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID;
        if (!canPost(app) || !channelEnabled(app, channel)) return false;

        long now = System.currentTimeMillis();
        Session incoming = new Session();
        incoming.source = SOURCE_PREDICTIVE;
        incoming.direction = low ? DIRECTION_LOW : DIRECTION_HIGH;
        incoming.priority = CriticalAlarmEpisodePolicy.PRIORITY_PREDICTIVE_LIKELY;
        incoming.title = title == null ? "" : title;
        incoming.body = body == null ? "" : body;
        incoming.value = glucose == null ? "" : glucose;
        incoming.anchorMs = Math.max(0L, anchorMs);
        incoming.expiresAtMs = Math.max(now + 60_000L,
                now + timeoutMs);
        if (crossingAtMs > now) {
            incoming.expiresAtMs = Math.min(incoming.expiresAtMs,
                    crossingAtMs + 30L * 60_000L);
        }
        incoming.soundRes = low ? R.raw.lowsoon : R.raw.highsoon;
        return acceptAndPresent(app, current(app, now), incoming, true);
    }

    private static boolean acceptAndPresent(Context app, Session current,
            Session incoming, boolean mayStart) {
        boolean actualDirectionReversal = current != null
                && SOURCE_ACTUAL.equals(current.source)
                && SOURCE_ACTUAL.equals(incoming.source)
                && !incoming.direction.equals(current.direction);
        CriticalAlarmEpisodePolicy.Transition transition =
                actualDirectionReversal
                ? CriticalAlarmEpisodePolicy.Transition.START
                : CriticalAlarmEpisodePolicy.transition(current != null,
                        current == null ? "" : current.direction,
                        current == null ? 0 : current.priority,
                        incoming.direction, incoming.priority);
        if (transition == CriticalAlarmEpisodePolicy.Transition.KEEP_HIGHER_PRIORITY) {
            // A lower actual reading update is intentionally consumed by the
            // already active higher-severity actual session. Returning false
            // here would make Notify fall back to its legacy ringtone and
            // create a second sound owner. A predictive/test event, however,
            // was not delivered and must remain false for delivery proof.
            boolean consumedByActual = SOURCE_ACTUAL.equals(incoming.source)
                    && current != null
                    && SOURCE_ACTUAL.equals(current.source);
            if (consumedByActual && !hasControlSurface(app, current)) {
                stopPresentation(app, current, true);
                return false;
            }
            return consumedByActual;
        }
        if (transition == CriticalAlarmEpisodePolicy.Transition.UPDATE_WITHOUT_RESTART) {
            incoming.token = current.token;
            incoming.snoozeUntilMs = current.snoozeUntilMs;
            save(app, incoming);
            active = incoming;
            if (incoming.snoozeUntilMs <= System.currentTimeMillis()) {
                if (!postNotification(app, incoming)) {
                    // Do not leave an application-owned loop running after
                    // its only ACK surface has been blocked or removed.
                    stopPresentation(app, incoming, true);
                    return false;
                }
            }
            notifyVisibleActivity(incoming);
            // The token is intentionally stable for a same-episode update,
            // so this replaces the old PendingIntent deadline with the newly
            // persisted expiry instead of leaving the session immortal after
            // an early stale EXPIRE delivery.
            scheduleExpiry(app, incoming);
            return true;
        }
        if (!mayStart) return false;
        stopPresentation(app, current, false);
        incoming.token = UUID.randomUUID().toString();
        incoming.snoozeUntilMs = 0L;
        save(app, incoming);
        active = incoming;
        boolean posted = postNotification(app, incoming);
        if (posted) {
            notifyVisibleActivity(incoming);
            scheduleExpiry(app, incoming);
            return true;
        }
        // Returning false hands actual alarms back to Notify's mature legacy
        // surface. Never claim ownership when the notification/channel that
        // carries ACK/Snooze cannot be posted.
        stopPresentation(app, incoming, true);
        return false;
    }

    static synchronized boolean predictiveActive(Context context,
            boolean low, long nowMs) {
        Session session = current(context, nowMs);
        if (session == null || !SOURCE_PREDICTIVE.equals(session.source)
                || low != DIRECTION_LOW.equals(session.direction)
                || session.snoozeUntilMs > nowMs) return false;
        return notificationActive(context, notificationId(session));
    }

    static synchronized long predictiveActiveUntil(boolean low) {
        Session session = active;
        return session != null && SOURCE_PREDICTIVE.equals(session.source)
                && low == DIRECTION_LOW.equals(session.direction)
                ? session.expiresAtMs : 0L;
    }

    static synchronized void cancelPredictive(Context context, boolean low) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Session session = current(app, System.currentTimeMillis());
        if (session != null && SOURCE_PREDICTIVE.equals(session.source)
                && low == DIRECTION_LOW.equals(session.direction)) {
            stopPresentation(app, session, true);
        }
        NotificationManager manager = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(low
                ? PREDICTIVE_LOW_ID : PREDICTIVE_HIGH_ID);
    }

    static synchronized void resolveActual(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Session session = current(app, System.currentTimeMillis());
        if (session != null && SOURCE_ACTUAL.equals(session.source)) {
            stopPresentation(app, session, true);
        }
    }

    static synchronized boolean showTest(Context context, boolean low) {
        if (context == null || Applic.isWearable) return false;
        Context app = context.getApplicationContext();
        ensureChannels(app);
        String channel = low
                ? CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID
                : CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID;
        if (!canPost(app) || !channelEnabled(app, channel)) return false;
        long now = System.currentTimeMillis();
        Session incoming = new Session();
        incoming.source = SOURCE_TEST;
        incoming.direction = low ? DIRECTION_LOW : DIRECTION_HIGH;
        incoming.priority = 0;
        incoming.title = app.getString(R.string.critical_alarm_test_title);
        incoming.body = app.getString(R.string.critical_alarm_test_body);
        incoming.value = app.getString(R.string.critical_alarm_test_badge);
        incoming.anchorMs = now;
        incoming.expiresAtMs = now + TEST_LIFETIME_MS;
        incoming.soundRes = low ? R.raw.lowsoon : R.raw.highsoon;
        return acceptAndPresent(app, current(app, now), incoming, true);
    }

    static synchronized Session session(Context context, String token) {
        Session session = current(context, System.currentTimeMillis());
        return session != null
                && CriticalAlarmEpisodePolicy.actionMatches(session.token, token)
                ? session.copy() : null;
    }

    static synchronized boolean acknowledge(Context context, String token) {
        Session session = session(context, token);
        if (session == null) return false;
        stopPresentation(context.getApplicationContext(), session, true);
        return true;
    }

    static synchronized boolean snooze(Context context, String token,
            long durationMs) {
        Session session = session(context, token);
        if (session == null || durationMs <= 0L) return false;
        Context app = context.getApplicationContext();
        session.snoozeUntilMs = System.currentTimeMillis() + durationMs;
        save(app, session);
        active = session;
        notifyVisibleActivity(session);
        stopSound();
        cancelNotification(app, session);
        if (SOURCE_PREDICTIVE.equals(session.source)) {
            new PredictiveAlertPreferences(app).snoozePrediction(
                    session.direction, session.anchorMs,
                    session.snoozeUntilMs);
        }
        schedule(app, CriticalGlucoseAlarmReceiver.ACTION_RESUME,
                session.token, session.snoozeUntilMs);
        return true;
    }

    static synchronized void resume(Context context, String token) {
        Session session = session(context, token);
        if (session == null) return;
        long now = System.currentTimeMillis();
        if (session.snoozeUntilMs > now) {
            schedule(context, CriticalGlucoseAlarmReceiver.ACTION_RESUME,
                    token, session.snoozeUntilMs);
            return;
        }
        session.snoozeUntilMs = 0L;
        save(context, session);
        active = session;
        if (postNotification(context, session)) {
            notifyVisibleActivity(session);
        } else {
            // A resumed alarm without a notification would have no reachable
            // acknowledgement UI. Drop controller ownership and let the next
            // native trigger use the legacy fail-safe path.
            stopPresentation(context.getApplicationContext(), session, true);
        }
    }

    static synchronized void expire(Context context, String token) {
        Session session = session(context, token);
        if (session != null && session.expiresAtMs <= System.currentTimeMillis()) {
            stopPresentation(context.getApplicationContext(), session, true);
        }
    }

    static Intent openGraphIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .putExtra(PredictiveAlertNotifier.EXTRA_OPEN_FORECAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static synchronized Session currentSession(Context context) {
        Session session = current(context, System.currentTimeMillis());
        return session == null ? null : session.copy();
    }

    private static boolean postNotification(Context context, Session session) {
        if (!canPost(context)) return false;
        ensureChannels(context);
        String channel = channelId(session);
        if (!channelEnabled(context, channel)) return false;

        PendingIntent fullScreen = activityIntent(context, session);
        PendingIntent acknowledge = receiverIntent(context, session,
                CriticalGlucoseAlarmReceiver.ACTION_ACK, 1);
        PendingIntent snooze = receiverIntent(context, session,
                CriticalGlucoseAlarmReceiver.ACTION_SNOOZE, 2);
        PendingIntent open = PendingIntent.getActivity(context,
                requestCode(session, 3), openGraphIntent(context),
                pendingFlags());

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, channel)
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle(session.title)
                .setContentText(session.body)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(session.body))
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(session.anchorMs)
                .setColor(DIRECTION_LOW.equals(session.direction)
                        ? 0xFFE65B65 : 0xFFF2B84B)
                .addAction(0, context.getString(
                        R.string.critical_alarm_ack_action), acknowledge)
                .addAction(0, context.getString(
                        R.string.critical_alarm_snooze_action), snooze)
                .addAction(0, context.getString(
                        R.string.critical_alarm_open_graph_action), open);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(resourceUri(context, session.soundRes),
                    AudioManager.STREAM_ALARM)
                    .setVibrate(vibration(session));
        }
        NotificationCompat.Builder publicBuilder =
                new NotificationCompat.Builder(context, channel)
                        .setSmallIcon(R.drawable.novalue)
                        .setContentTitle(context.getString(
                                R.string.critical_alarm_private_title))
                        .setContentText(context.getString(
                                R.string.critical_alarm_private_body))
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setPublicVersion(publicBuilder.build());
        Notification notification = builder.build();
        // The notification channel is the single audio owner. INSISTENT keeps
        // its USAGE_ALARM sound active until ACK/Snooze/recovery cancels this
        // notification, preserving channel-level DND bypass semantics.
        notification.flags |= Notification.FLAG_NO_CLEAR
                | Notification.FLAG_INSISTENT;
        notification.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        try {
            cancelOtherNotifications(manager, session);
            manager.notify(notificationId(session), notification);
            return true;
        } catch (RuntimeException failure) {
            Log.stack("CriticalAlarm", "notify", failure);
            return false;
        }
    }

    private static PendingIntent activityIntent(Context context,
            Session session) {
        Intent intent = new Intent(context, CriticalGlucoseAlarmActivity.class)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN,
                        session.token)
                .setData(pendingData(context, "full-screen", session.token))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, requestCode(session, 0),
                intent, pendingFlags());
    }

    private static PendingIntent receiverIntent(Context context,
            Session session, String action, int offset) {
        Intent intent = new Intent(context, CriticalGlucoseAlarmReceiver.class)
                .setAction(action)
                .setData(pendingData(context, action + ':' + offset,
                        session.token))
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN,
                        session.token);
        return PendingIntent.getBroadcast(context, requestCode(session, offset),
                intent, pendingFlags());
    }

    private static int requestCode(Session session, int offset) {
        return 50_000 + Math.abs((session.token + ':' + offset).hashCode()
                % 10_000);
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static Uri pendingData(Context context, String identity,
            String token) {
        // PendingIntent identity deliberately excludes extras. A unique opaque
        // data URI prevents FLAG_UPDATE_CURRENT from rewriting an older ACK or
        // FSI when the compact request-code hash collides with a new session.
        return Uri.fromParts("juggluco-critical",
                context.getPackageName() + ':' + identity + ':' + token, null);
    }

    private static void scheduleLoop(Context context, Session session) {
        stopSound();
        long delayMs = soundDurationMs(session.soundRes) + 150L;
        String token = session.token;
        delayedLoop = () -> startLoop(context.getApplicationContext(), token);
        MAIN.postDelayed(delayedLoop, delayMs);
    }

    private static synchronized void startLoop(Context context, String token) {
        Session session = session(context, token);
        if (session == null || session.snoozeUntilMs > System.currentTimeMillis()) {
            return;
        }
        if (!hasControlSurface(context, session)) {
            // Between the channel sound and the delayed loop the user/system
            // may revoke delivery. Do not start audio that has no ACK surface.
            stopPresentation(context.getApplicationContext(), session, true);
            return;
        }
        stopSound();
        try {
            audioManager = (AudioManager)
                    context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = new AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(ALARM_ATTRIBUTES).build();
                    audioManager.requestAudioFocus(audioFocusRequest);
                } else {
                    audioManager.requestAudioFocus(null,
                            AudioManager.STREAM_ALARM,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            }
            ringtone = RingtoneManager.getRingtone(context,
                    resourceUri(context, session.soundRes));
            if (ringtone != null) {
                ringtone.setAudioAttributes(ALARM_ATTRIBUTES);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setLooping(true);
                }
                ringtone.play();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    // Ringtone.setLooping() was added in API 28. Keep the
                    // same acknowledge-until-stopped contract on API 21-27
                    // by replaying only while this exact session token is
                    // still active.
                    String repeatToken = session.token;
                    delayedLoop = () -> startLoop(
                            context.getApplicationContext(), repeatToken);
                    MAIN.postDelayed(delayedLoop,
                            soundDurationMs(session.soundRes) + 150L);
                }
            }
            vibrator = vibrator(context);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = vibration(session);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                            ALARM_ATTRIBUTES);
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Throwable failure) {
            Log.stack("CriticalAlarm", "startLoop", failure);
        }
    }

    private static synchronized void stopSound() {
        if (delayedLoop != null) {
            MAIN.removeCallbacks(delayedLoop);
            delayedLoop = null;
        }
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (Throwable failure) {
                Log.stack("CriticalAlarm", "ringtone.stop", failure);
            }
            ringtone = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Throwable ignored) {}
            vibrator = null;
        }
        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            } catch (Throwable ignored) {}
        }
        audioManager = null;
        audioFocusRequest = null;
    }

    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager)
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private static void stopPresentation(Context context, Session session,
            boolean clear) {
        stopSound();
        if (session != null) {
            cancelNotification(context, session);
            cancelScheduled(context, session);
        }
        if (clear) {
            active = null;
            clearSaved(context);
            notifyVisibleActivity(null);
        }
    }

    private static void cancelNotification(Context context, Session session) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && session != null) {
            manager.cancel(notificationId(session));
        }
    }

    private static void cancelOtherNotifications(NotificationManager manager,
            Session keep) {
        int keepId = notificationId(keep);
        for (int id : CRITICAL_NOTIFICATION_IDS) {
            if (id != keepId) manager.cancel(id);
        }
    }

    private static void cancelAllCriticalNotifications(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        for (int id : CRITICAL_NOTIFICATION_IDS) manager.cancel(id);
    }

    /**
     * Fails closed when persisted ownership cannot be proven. In particular,
     * an INSISTENT notification can outlive the process or an APK update, so
     * clearing preferences alone is not enough to stop an orphan alarm.
     */
    private static void clearInvalidState(Context context, Session stale) {
        stopSound();
        cancelAllCriticalNotifications(context);
        // The request code is token-derived. When a usable stale token remains
        // we can also remove its exact RESUME/EXPIRE alarms; with no token the
        // receiver's stale-action check is the remaining fail-safe.
        if (stale != null && stale.token != null && !stale.token.isEmpty()) {
            cancelScheduled(context, stale);
        }
        active = null;
        clearSaved(context);
        notifyVisibleActivity(null);
    }

    private static void scheduleExpiry(Context context, Session session) {
        schedule(context, CriticalGlucoseAlarmReceiver.ACTION_EXPIRE,
                session.token, session.expiresAtMs);
    }

    private static void schedule(Context context, String action, String token,
            long atMs) {
        if (context == null || token == null || atMs <= 0L) return;
        Intent intent = scheduledIntent(context, action, token);
        int request = 70_000 + Math.abs((token + action).hashCode() % 20_000);
        PendingIntent pending = PendingIntent.getBroadcast(context, request,
                intent, pendingFlags());
        AlarmManager manager = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                            atMs, pending);
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                            atMs, pending);
                }
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, atMs, pending);
            }
        } catch (SecurityException denied) {
            manager.set(AlarmManager.RTC_WAKEUP, atMs, pending);
        }
    }

    private static void cancelScheduled(Context context, Session session) {
        AlarmManager manager = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null || session == null) return;
        for (String action : new String[]{
                CriticalGlucoseAlarmReceiver.ACTION_RESUME,
                CriticalGlucoseAlarmReceiver.ACTION_EXPIRE}) {
            Intent intent = scheduledIntent(context, action, session.token);
            int request = 70_000 + Math.abs(
                    (session.token + action).hashCode() % 20_000);
            PendingIntent pending = PendingIntent.getBroadcast(context,
                    request, intent, pendingFlags() | PendingIntent.FLAG_NO_CREATE);
            if (pending != null) {
                manager.cancel(pending);
                pending.cancel();
            }
        }
    }

    private static Intent scheduledIntent(Context context, String action,
            String token) {
        return new Intent(context, CriticalGlucoseAlarmReceiver.class)
                .setAction(action)
                .setData(pendingData(context, "scheduled:" + action, token))
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN, token);
    }

    private static void restoreIfNeeded(Context context) {
        Session session = load(context);
        long now = System.currentTimeMillis();
        if (!validPersistedSession(session) || session.expiresAtMs <= now) {
            clearInvalidState(context, session);
            return;
        }
        active = session;
        if (session.snoozeUntilMs > now) {
            notifyVisibleActivity(session);
            schedule(context, CriticalGlucoseAlarmReceiver.ACTION_RESUME,
                    session.token, session.snoozeUntilMs);
        } else if (postNotification(context, session)) {
            notifyVisibleActivity(session);
        } else {
            // Process restore is not allowed to resurrect an unacknowledgeable
            // app-owned loop. Native actual alarms retain their legacy path.
            clearInvalidState(context.getApplicationContext(), session);
            return;
        }
        scheduleExpiry(context, session);
    }

    private static Session current(Context context, long nowMs) {
        Session session = active;
        if (session == null && context != null) session = load(context);
        if (session != null && (!validPersistedSession(session)
                || session.expiresAtMs <= nowMs)) {
            if (context != null) clearInvalidState(
                    context.getApplicationContext(), session);
            return null;
        }
        active = session;
        return session;
    }

    private static void save(Context context, Session session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, session.token)
                .putString(KEY_SOURCE, session.source)
                .putString(KEY_DIRECTION, session.direction)
                .putInt(KEY_PRIORITY, session.priority)
                .putString(KEY_TITLE, session.title)
                .putString(KEY_BODY, session.body)
                .putString(KEY_VALUE, session.value)
                .putLong(KEY_ANCHOR, session.anchorMs)
                .putLong(KEY_EXPIRES, session.expiresAtMs)
                .putLong(KEY_SNOOZE, session.snoozeUntilMs)
                .putInt(KEY_SOUND_RES, session.soundRes)
                .commit();
    }

    private static Session load(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        Session session = new Session();
        Object savedToken = prefs.getAll().get(KEY_TOKEN);
        if (savedToken instanceof String) session.token = (String) savedToken;
        if (session.token == null || session.token.isEmpty()) return null;
        try {
            session.source = prefs.getString(KEY_SOURCE, "");
            session.direction = prefs.getString(KEY_DIRECTION, "");
            session.priority = prefs.getInt(KEY_PRIORITY, 0);
            session.title = prefs.getString(KEY_TITLE, "");
            session.body = prefs.getString(KEY_BODY, "");
            session.value = prefs.getString(KEY_VALUE, "");
            session.anchorMs = prefs.getLong(KEY_ANCHOR, 0L);
            session.expiresAtMs = prefs.getLong(KEY_EXPIRES, 0L);
            session.snoozeUntilMs = prefs.getLong(KEY_SNOOZE, 0L);
            session.soundRes = prefs.getInt(KEY_SOUND_RES, R.raw.siren);
        } catch (RuntimeException corruptPreferences) {
            // Preserve the token so clearInvalidState can cancel token-derived
            // alarms, while validation below rejects the partial session.
            Log.stack("CriticalAlarm", "load", corruptPreferences);
        }
        return session;
    }

    private static boolean validPersistedSession(Session session) {
        if (session == null || session.token == null
                || session.token.isEmpty() || session.expiresAtMs <= 0L
                || session.anchorMs < 0L || session.snoozeUntilMs < 0L) {
            return false;
        }
        boolean actual = SOURCE_ACTUAL.equals(session.source);
        boolean predictive = SOURCE_PREDICTIVE.equals(session.source);
        boolean test = SOURCE_TEST.equals(session.source);
        if (!actual && !predictive && !test) return false;
        boolean low = DIRECTION_LOW.equals(session.direction);
        boolean high = DIRECTION_HIGH.equals(session.direction);
        if (!low && !high) return false;
        if (actual) {
            if (session.priority != CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL
                    && session.priority
                    != CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE) {
                return false;
            }
            return session.soundRes == (low ? R.raw.verylow : R.raw.veryhigh);
        }
        if (predictive && session.priority
                != CriticalAlarmEpisodePolicy.PRIORITY_PREDICTIVE_LIKELY) {
            return false;
        }
        if (test && session.priority != 0) return false;
        return session.soundRes == (low ? R.raw.lowsoon : R.raw.highsoon);
    }

    private static void clearSaved(Context context) {
        if (context != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static void notifyVisibleActivity(Session session) {
        CriticalGlucoseAlarmActivity.sessionChanged(
                session == null ? null : session.token);
    }

    private static String glucoseText(Context context, float glucoseValue) {
        Locale locale = Applic.usedlocale == null
                ? Locale.getDefault() : Applic.usedlocale;
        if (Applic.unit == 1) {
            return String.format(locale, "%.1f %s", glucoseValue,
                    context.getString(R.string.mmolL));
        }
        return String.format(locale, "%.0f %s", glucoseValue,
                context.getString(R.string.mgdL));
    }

    private static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return NotificationManagerCompat.from(context)
                .areNotificationsEnabled();
    }

    private static boolean channelEnabled(Context context, String channel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel value = manager == null ? null
                : manager.getNotificationChannel(channel);
        return value != null
                && value.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static boolean hasControlSurface(Context context,
            Session session) {
        if (context == null || session == null) return false;
        if (session.snoozeUntilMs > System.currentTimeMillis()) return true;
        return canPost(context) && channelEnabled(context, channelId(session))
                && notificationActive(context, notificationId(session));
    }

    private static boolean notificationActive(Context context, int id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        try {
            for (android.service.notification.StatusBarNotification value
                    : manager.getActiveNotifications()) {
                if (value != null && value.getId() == id) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private static boolean isActualKind(int kind) {
        return kind == 0 || kind == 1 || kind == 5 || kind == 6;
    }

    private static int notificationId(Session session) {
        if (SOURCE_TEST.equals(session.source)) return TEST_ID;
        boolean low = DIRECTION_LOW.equals(session.direction);
        return SOURCE_ACTUAL.equals(session.source)
                ? low ? ACTUAL_LOW_ID : ACTUAL_HIGH_ID
                : low ? PREDICTIVE_LOW_ID : PREDICTIVE_HIGH_ID;
    }

    private static String channelId(Session session) {
        boolean low = DIRECTION_LOW.equals(session.direction);
        if (SOURCE_ACTUAL.equals(session.source)) {
            return low ? CriticalAlarmDiagnostics.ACTUAL_LOW_CHANNEL_ID
                    : CriticalAlarmDiagnostics.ACTUAL_HIGH_CHANNEL_ID;
        }
        return low ? CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID
                : CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID;
    }

    private static Uri resourceUri(Context context, int res) {
        return Uri.parse("android.resource://" + context.getPackageName()
                + '/' + res);
    }

    private static long[] vibration(Session session) {
        return DIRECTION_LOW.equals(session.direction)
                ? LOW_VIBRATION : HIGH_VIBRATION;
    }

    private static long soundDurationMs(int soundRes) {
        if (soundRes == R.raw.veryhigh) return 5_050L;
        if (soundRes == R.raw.verylow) return 7_900L;
        if (soundRes == R.raw.highsoon) return 7_900L;
        if (soundRes == R.raw.lowsoon) return 9_600L;
        return 8_500L;
    }

    static final class Session {
        String token;
        String source;
        String direction;
        int priority;
        String title;
        String body;
        String value;
        long anchorMs;
        long expiresAtMs;
        long snoozeUntilMs;
        int soundRes;

        boolean low() {
            return DIRECTION_LOW.equals(direction);
        }

        boolean actual() {
            return SOURCE_ACTUAL.equals(source);
        }

        boolean test() {
            return SOURCE_TEST.equals(source);
        }

        Session copy() {
            Session result = new Session();
            result.token = token;
            result.source = source;
            result.direction = direction;
            result.priority = priority;
            result.title = title;
            result.body = body;
            result.value = value;
            result.anchorMs = anchorMs;
            result.expiresAtMs = expiresAtMs;
            result.snoozeUntilMs = snoozeUntilMs;
            result.soundRes = soundRes;
            return result;
        }
    }
}
