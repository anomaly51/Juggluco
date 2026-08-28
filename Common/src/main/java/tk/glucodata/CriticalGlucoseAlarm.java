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
import android.os.PowerManager;
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
 * loop. For the active episode it may temporarily raise only the alarm stream
 * to that alert type's configured floor, then restore it. It never changes the
 * ringer mode or the user's Do Not Disturb filter.</p>
 */
final class CriticalGlucoseAlarm {
    static final String SOURCE_ACTUAL = "actual";
    static final String SOURCE_PREDICTIVE = "predictive";
    static final String SOURCE_SIGNAL_LOSS = "signal_loss";
    static final String SOURCE_TEST = "test";
    static final String DIRECTION_LOW = "low";
    static final String DIRECTION_HIGH = "high";
    static final String DIRECTION_SIGNAL = "signal";

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
    private static final String KEY_SOUND_TONE_ID = "sound_tone_id";
    private static final String KEY_ALERT_TYPE = "alert_type";
    private static final String KEY_MINIMUM_VOLUME_PERCENT =
            "minimum_volume_percent";
    private static final String KEY_DISPLAY_PAYLOAD = "display_payload";

    private static final int ACTUAL_LOW_ID = 8_401;
    private static final int ACTUAL_HIGH_ID = 8_402;
    private static final int PREDICTIVE_LOW_ID = 8_403;
    private static final int PREDICTIVE_HIGH_ID = 8_404;
    private static final int TEST_ID = 8_405;
    private static final int SIGNAL_LOSS_ID = 8_406;
    private static final int[] CRITICAL_NOTIFICATION_IDS = {
            ACTUAL_LOW_ID, ACTUAL_HIGH_ID, PREDICTIVE_LOW_ID,
            PREDICTIVE_HIGH_ID, TEST_ID, SIGNAL_LOSS_ID
    };
    // Some Android/OEM notification services complete an INSISTENT post after
    // the app's first cancel IPC. Retry after the acknowledgement Activity has
    // had time to finish, while guarding against a newer critical episode.
    private static final long[] CRITICAL_CANCEL_RETRY_DELAYS_MS = {
            150L, 750L, 2_500L, 10_000L, 30_000L, 60_000L
    };
    private static final long[] VOLUME_RESTORE_RETRY_DELAYS_MS = {
            150L, 400L, 800L, 1_150L, 1_800L, 3_000L, 5_000L
    };
    private static final long ACTUAL_MAX_LIFETIME_MS = 24L * 60L * 60_000L;
    private static final long SIGNAL_LOSS_MAX_LIFETIME_MS =
            24L * 60L * 60_000L;
    private static final long TEST_LIFETIME_MS = 2L * 60_000L;
    private static final String SELECTED_CHANNEL_VERSION = "v4";
    private static final long[] LOW_VIBRATION =
            {0L, 900L, 350L, 900L, 350L, 1_600L};
    private static final long[] HIGH_VIBRATION =
            {0L, 450L, 250L, 450L, 250L, 900L};
    private static final long[] SIGNAL_LOSS_VIBRATION =
            {0L, 650L, 300L, 650L, 300L, 1_200L};
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
                boolean bypass = dndBypassAvailable(manager);
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
                createChannel(app, manager,
                        CriticalAlarmDiagnostics.SIGNAL_LOSS_CHANNEL_ID,
                        R.string.critical_alarm_channel_signal_loss,
                        R.string.critical_alarm_channel_signal_loss_description,
                        R.raw.siren, SIGNAL_LOSS_VIBRATION, bypass);
                // A channel's sound is immutable after creation. Give every
                // explicit built-in selection its own stable channel so the
                // first OS-owned sound and any app-owned fallback always use
                // the same resource without deleting user-customized channels.
                createSelectedChannel(app, manager,
                        CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW,
                        bypass);
                createSelectedChannel(app, manager,
                        CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH,
                        bypass);
                createSelectedChannel(app, manager,
                        CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW,
                        bypass);
                createSelectedChannel(app, manager,
                        CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH,
                        bypass);
                createSelectedChannel(app, manager,
                        CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS,
                        bypass);
            }
        }
        CriticalAlarmDiagnostics.registerTestHook(
                (testContext, alertType) -> showTest(testContext, alertType));
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

    private static void createSelectedChannel(Context context,
            NotificationManager manager,
            CriticalAlarmSoundCatalog.AlertType alertType,
            boolean bypassDnd) {
        String toneId = CriticalAlarmSoundCatalog.selectedToneId(context,
                alertType);
        int soundRes = CriticalAlarmSoundCatalog.selectedSoundRes(context,
                alertType);
        createSelectedChannel(context, manager, alertType, toneId, soundRes,
                bypassDnd);
    }

    private static void createSelectedChannel(Context context,
            NotificationManager manager,
            CriticalAlarmSoundCatalog.AlertType alertType, String toneId,
            int soundRes, boolean bypassDnd) {
        boolean low = alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                || alertType
                == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        boolean signalLoss = alertType
                == CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS;
        boolean actual = alertType
                == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                || alertType
                == CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH;
        int nameRes = signalLoss
                ? R.string.critical_alarm_channel_signal_loss
                : actual
                ? low ? R.string.critical_alarm_channel_actual_low
                        : R.string.critical_alarm_channel_actual_high
                : low ? R.string.critical_alarm_channel_predictive_low
                        : R.string.critical_alarm_channel_predictive_high;
        int descriptionRes = signalLoss
                ? R.string.critical_alarm_channel_signal_loss_description
                : actual
                ? low ? R.string.critical_alarm_channel_actual_low_description
                        : R.string.critical_alarm_channel_actual_high_description
                : low ? R.string.critical_alarm_channel_predictive_low_description
                        : R.string.critical_alarm_channel_predictive_high_description;
        String baseName = context.getString(nameRes);
        CharSequence toneLabel = CriticalAlarmSoundCatalog.label(context,
                toneId);
        NotificationChannel channel = new NotificationChannel(
                selectedChannelId(alertType, toneId, bypassDnd),
                toneLabel.length() == 0 ? baseName
                        : baseName + " · " + toneLabel,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(descriptionRes));
        channel.setSound(stableResourceUri(context, soundRes),
                ALARM_ATTRIBUTES);
        channel.enableVibration(true);
        channel.setVibrationPattern(signalLoss ? SIGNAL_LOSS_VIBRATION
                : low ? LOW_VIBRATION : HIGH_VIBRATION);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setBypassDnd(bypassDnd);
        manager.createNotificationChannel(channel);
    }

    /** Starts or refreshes an independently detected native actual alarm. */
    static synchronized boolean showActual(Context context, int kind,
            float glucoseValue, String message, boolean trigger) {
        return showActual(context, kind, glucoseValue, message, trigger,
                CriticalDisplayPayload.EMPTY);
    }

    /** Starts or refreshes an actual alarm with bounded graph context. */
    static synchronized boolean showActual(Context context, int kind,
            float glucoseValue, String message, boolean trigger,
            CriticalDisplayPayload displayPayload) {
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
        incoming.displayPayload = displayPayload == null
                ? CriticalDisplayPayload.EMPTY : displayPayload;
        // Alarm ownership and expiry are presentation lifecycle state. A
        // malformed, stale or differently-scaled chart timestamp must never
        // make an actionable alarm expire before it is shown.
        long presentedAtMs = System.currentTimeMillis();
        incoming.anchorMs = presentedAtMs;
        incoming.expiresAtMs = incoming.anchorMs + ACTUAL_MAX_LIFETIME_MS;
        configureDelivery(app, incoming, low
                ? CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                : CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH);
        boolean replacesNonActual = current != null
                && !SOURCE_ACTUAL.equals(current.source)
                && incoming.priority >= current.priority;
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
        return showPredictive(context, low, title, body, glucose, anchorMs,
                crossingAtMs, timeoutMs, CriticalDisplayPayload.EMPTY);
    }

    static synchronized boolean showPredictive(Context context, boolean low,
            String title, String body, String glucose, long anchorMs,
            long crossingAtMs, long timeoutMs,
            CriticalDisplayPayload displayPayload) {
        if (context == null || Applic.isWearable || timeoutMs <= 0L) {
            return false;
        }
        Context app = context.getApplicationContext();
        ensureChannels(app);
        String channel = selectedChannelId(app,
                CriticalAlarmSoundCatalog.alertType(SOURCE_PREDICTIVE,
                        low ? DIRECTION_LOW : DIRECTION_HIGH));
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
        configureDelivery(app, incoming, low
                ? CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW
                : CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH);
        incoming.displayPayload = displayPayload == null
                ? CriticalDisplayPayload.EMPTY : displayPayload;
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
            if (consumedByActual && current.direction.equals(
                    incoming.direction) && incoming.displayPayload != null
                    && !incoming.displayPayload.isEmpty()
                    && (current.displayPayload == null
                    || current.displayPayload.isEmpty()
                    || incoming.displayPayload.readingAtMs
                    >= current.displayPayload.readingAtMs)) {
                // Retain severe episode/audio priority while still showing
                // the newest measured value and chart. Reposting the
                // insistent notification here could restart channel audio, so
                // update the private session and an already-visible surface.
                current.value = incoming.value;
                current.body = incoming.body;
                current.displayPayload = incoming.displayPayload;
                save(app, current);
                active = current;
                notifySurfaces(app, current);
            }
            return consumedByActual;
        }
        if (transition == CriticalAlarmEpisodePolicy.Transition.UPDATE_WITHOUT_RESTART) {
            incoming.token = current.token;
            incoming.snoozeUntilMs = current.snoozeUntilMs;
            if ((incoming.displayPayload == null
                    || incoming.displayPayload.isEmpty())
                    && current.displayPayload != null
                    && !current.displayPayload.isEmpty()) {
                incoming.displayPayload = current.displayPayload;
            }
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
            notifySurfaces(app, incoming);
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
            notifySurfaces(app, incoming);
            scheduleExpiry(app, incoming);
            return true;
        }
        // Returning false hands actual alarms back to Notify's mature legacy
        // surface. Never claim ownership when the notification/channel that
        // carries the Stop sound action cannot be posted.
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

    /** Presents signal loss through the same acknowledge-until-stopped path. */
    static synchronized boolean showLossSignal(Context context,
            long lastReadingAtMs, String message) {
        if (context == null || Applic.isWearable) return false;
        Context app = context.getApplicationContext();
        ensureChannels(app);
        CriticalAlarmSoundCatalog.AlertType alertType =
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS;
        String channel = selectedChannelId(app, alertType);
        if (!canPost(app) || !channelEnabled(app, channel)) return false;

        long now = System.currentTimeMillis();
        Session current = current(app, now);
        // A live measured-glucose emergency is more specific than a missing
        // reading. Treat loss detection as delivered without replacing it or
        // starting the legacy loss ringtone alongside the current alarm.
        if (current != null && current.actual()) {
            return hasControlSurface(app, current);
        }

        Session incoming = new Session();
        incoming.source = SOURCE_SIGNAL_LOSS;
        incoming.direction = DIRECTION_SIGNAL;
        // Signal loss outranks a forecast, while any current glucose alarm can
        // still replace it as soon as a new reading arrives.
        incoming.priority = CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL;
        incoming.title = app.getString(R.string.critical_alarm_signal_loss_title);
        incoming.body = message == null || message.trim().isEmpty()
                ? app.getString(R.string.critical_alarm_signal_loss_body)
                : message;
        incoming.value = app.getString(
                R.string.critical_alarm_signal_loss_value);
        incoming.anchorMs = now;
        incoming.expiresAtMs = now + SIGNAL_LOSS_MAX_LIFETIME_MS;
        incoming.displayPayload = CriticalDisplayPayload.EMPTY;
        configureDelivery(app, incoming, alertType);
        boolean presented = acceptAndPresent(app, current, incoming, true);
        if (presented && active != null
                && SOURCE_SIGNAL_LOSS.equals(active.source)) {
            // Reuse the real-local-data capture path. It never fabricates a
            // reading or starts network work, and leaves a clear empty chart
            // when there is no usable history.
            CriticalDisplayPayload.enrichTestAsync(app, active.token,
                    Math.max(now, lastReadingAtMs));
        }
        return presented;
    }

    static synchronized void resolveSignalLoss(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Session session = current(app, System.currentTimeMillis());
        if (session != null && SOURCE_SIGNAL_LOSS.equals(session.source)) {
            stopPresentation(app, session, true);
        }
    }

    static synchronized boolean showTest(Context context, boolean low) {
        return showTest(context, low
                ? CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW
                : CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH);
    }

    static synchronized boolean showTest(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        if (context == null || Applic.isWearable) return false;
        if (alertType == null) return false;
        Context app = context.getApplicationContext();
        ensureChannels(app);
        String channel = selectedChannelId(app, alertType);
        if (!canPost(app) || !channelEnabled(app, channel)) return false;
        long now = System.currentTimeMillis();
        Session incoming = new Session();
        incoming.source = SOURCE_TEST;
        incoming.direction = alertType
                == CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS
                ? DIRECTION_SIGNAL
                : alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                || alertType
                == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW
                ? DIRECTION_LOW : DIRECTION_HIGH;
        incoming.priority = 0;
        incoming.title = app.getString(R.string.critical_alarm_test_title);
        incoming.body = app.getString(R.string.critical_alarm_test_body);
        incoming.value = app.getString(R.string.critical_alarm_test_badge);
        incoming.anchorMs = now;
        incoming.expiresAtMs = now + TEST_LIFETIME_MS;
        configureDelivery(app, incoming, alertType);
        boolean presented = acceptAndPresent(app, current(app, now), incoming,
                true);
        if (presented && active != null && SOURCE_TEST.equals(active.source)) {
            // Delivery and acknowledgement must never wait for a native
            // history scan. The visible test is enriched on the bounded
            // capture executor as soon as local data is available.
            CriticalDisplayPayload.enrichTestAsync(app, active.token, now);
        }
        return presented;
    }

    private static void configureDelivery(Context context, Session session,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        session.alertType = alertType;
        session.soundToneId = CriticalAlarmSoundCatalog.selectedToneId(
                context, alertType);
        session.soundRes = CriticalAlarmSoundCatalog.soundRes(
                session.soundToneId);
        session.minimumVolumePercent = CriticalAlertPreferences
                .getMinimumVolumePercent(context, alertType);
    }

    /** Serializes settings previews with the critical-alarm audio owner. */
    static synchronized boolean previewSound(Context context, String toneId) {
        if (context == null || Applic.isWearable) return false;
        Context app = context.getApplicationContext();
        if (current(app, System.currentTimeMillis()) != null) return false;
        return CriticalAlarmSoundCatalog.preview(app, toneId);
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
        notifySurfaces(app, session);
        stopSound(app);
        CriticalAlarmAudioReliability.RestorePlan restorePlan =
                CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(app,
                        session.token);
        neutralizeAndCancelCriticalNotifications(app, -1);
        finishAlarmVolumeRestore(app, session.token, restorePlan);
        scheduleCriticalNotificationCleanup(app, session.token);
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
            notifySurfaces(context, session);
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

    /** Applies optional chart enrichment only to the exact live alarm token. */
    static synchronized boolean updateDisplayPayload(Context context,
            String token, CriticalDisplayPayload displayPayload) {
        if (context == null || displayPayload == null
                || displayPayload.isEmpty()) return false;
        Context app = context.getApplicationContext();
        Session session = session(app, token);
        if (session == null || (session.displayPayload != null
                && !session.displayPayload.isEmpty()
                && displayPayload.readingAtMs
                < session.displayPayload.readingAtMs)) return false;
        session.displayPayload = displayPayload;
        save(app, session);
        active = session;
        notifySurfaces(app, session);
        return true;
    }

    private static boolean postNotification(Context context, Session session) {
        // A settings preview must never compete with a real/test alarm, and
        // the alarm's red Stop action must leave no preview audio behind.
        CriticalAlarmSoundCatalog.stopPreview();
        if (!canPost(context)) return false;
        ensureChannels(context);
        // Snapshot policy access once so the immutable channel we create is
        // exactly the one attached to this notification. If the user grants
        // DND access later, the next post uses the distinct bypass identity.
        boolean bypassDnd = dndBypassAvailable(context);
        String channel = selectedChannelId(session, bypassDnd);
        ensureSessionChannel(context, session, bypassDnd);
        if (!channelEnabled(context, channel)
                || !channelAlarmSoundReady(context, channel)) return false;

        PendingIntent fullScreen = activityIntent(context, session);
        PendingIntent acknowledge = receiverIntent(context, session,
                CriticalGlucoseAlarmReceiver.ACTION_ACK, 1);

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
                .setColor(session.signalLoss()
                        || DIRECTION_LOW.equals(session.direction)
                        ? 0xFFE65B65 : 0xFFF2B84B)
                .addAction(0, context.getString(
                        R.string.critical_alarm_ack_action), acknowledge);
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
        // its USAGE_ALARM sound active until Stop sound/recovery cancels this
        // notification, preserving channel-level DND bypass semantics.
        notification.flags |= Notification.FLAG_NO_CLEAR
                | Notification.FLAG_INSISTENT;
        notification.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        PowerManager.WakeLock deliveryWakeLock =
                CriticalAlarmAudioReliability.acquireDeliveryWakeLock(context);
        try {
            // Silent/vibrate ringer modes do not control STREAM_ALARM. If that
            // stream itself is zero or barely audible, raise only it for this
            // critical episode and restore it on ACK/Snooze/recovery.
            CriticalAlarmAudioReliability.ensureAudibleAlarmVolume(context,
                    session.minimumVolumePercent);
            neutralizeAndCancelCriticalNotifications(context,
                    notificationId(session));
            manager.notify(notificationId(session), notification);
            return true;
        } catch (RuntimeException failure) {
            Log.stack("CriticalAlarm", "notify", failure);
            return false;
        } finally {
            CriticalAlarmAudioReliability.releaseDeliveryWakeLock(
                    deliveryWakeLock);
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

    private static PendingIntent openGraphActivityIntent(Context context,
            Session session) {
        Intent intent = new Intent(context,
                CriticalGlucoseAlarmOpenGraphActivity.class)
                .setAction(CriticalGlucoseAlarmOpenGraphActivity
                        .ACTION_OPEN_GRAPH)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN,
                        session.token)
                .setData(pendingData(context, "open-graph:3",
                        session.token))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, requestCode(session, 3),
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
        stopSound(context);
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
        stopSound(context);
        CriticalAlarmAudioReliability.ensureAudibleAlarmVolume(context,
                session.minimumVolumePercent);
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
                    // same Stop-sound-until-pressed contract on API 21-27
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

    private static synchronized void stopSound(Context context) {
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
        CriticalAlarmSoundCatalog.stopPreview();
        stopSound(context);
        String endedToken = session == null ? "" : session.token;
        CriticalAlarmAudioReliability.RestorePlan restorePlan = clear
                ? CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(
                context, endedToken) : null;
        if (session != null) {
            cancelScheduled(context, session);
        }
        if (clear) {
            active = null;
            clearSaved(context);
            // Cancel every ID owned by this controller, not only the ID
            // derived from the in-memory session. This also stops an orphaned
            // INSISTENT post left by a replacement or an OEM delivery race.
            cancelAllCriticalNotifications(context);
            // Notification/channel audio must be neutralized before restoring
            // the user's saved stream value. Samsung may otherwise reassert
            // the channel's volume asynchronously after our restore.
            finishAlarmVolumeRestore(context, endedToken, restorePlan);
            scheduleCriticalNotificationCleanup(context, endedToken);
            notifySurfaces(context, null);
        } else if (session != null) {
            cancelNotification(context, session);
        }
    }

    private static void cancelNotification(Context context, Session session) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && session != null) {
            manager.cancel(notificationId(session));
        }
    }

    private static void cancelAllCriticalNotifications(Context context) {
        neutralizeAndCancelCriticalNotifications(context, -1);
    }

    /**
     * Replaces each stale controller-owned notification with a non-alerting,
     * non-insistent copy before cancelling its exact tag/id identity. Samsung
     * SystemUI can otherwise retain the already-playing INSISTENT record after
     * a plain cancel IPC even though the application session is gone.
     */
    private static boolean neutralizeAndCancelCriticalNotifications(
            Context context, int keepId) {
        if (context == null) return false;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                for (android.service.notification.StatusBarNotification item
                        : manager.getActiveNotifications()) {
                    if (item == null || item.getId() == keepId
                            || !isCriticalNotificationId(item.getId())) {
                        continue;
                    }
                    try {
                        Notification neutral = neutralizedNotification(
                                item.getNotification());
                        if (neutral != null) {
                            manager.notify(item.getTag(), item.getId(), neutral);
                        }
                    } catch (RuntimeException failure) {
                        Log.stack("CriticalAlarm", "neutralize notification",
                                failure);
                    }
                    try {
                        manager.cancel(item.getTag(), item.getId());
                    } catch (RuntimeException failure) {
                        Log.stack("CriticalAlarm", "cancel tagged notification",
                                failure);
                    }
                }
            } catch (RuntimeException failure) {
                Log.stack("CriticalAlarm", "query active notifications",
                        failure);
            }
        }
        // Also cancel unseen/in-flight untagged posts. The query above supplies
        // exact tags for already-active records; controller posts are normally
        // untagged, so this catches both delivery states without touching any
        // ordinary notification ID.
        for (int id : CRITICAL_NOTIFICATION_IDS) {
            if (id == keepId) continue;
            try {
                manager.cancel(id);
            } catch (RuntimeException failure) {
                Log.stack("CriticalAlarm", "cancel notification", failure);
            }
        }
        return staleCriticalNotificationActive(manager, keepId);
    }

    static Notification neutralizedNotification(Notification original) {
        if (original == null) return null;
        Notification neutral = original.clone();
        neutral.flags &= ~(Notification.FLAG_INSISTENT
                | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_ONGOING_EVENT);
        neutral.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        neutral.defaults = 0;
        neutral.sound = null;
        neutral.vibrate = null;
        neutral.fullScreenIntent = null;
        neutral.contentIntent = null;
        neutral.deleteIntent = null;
        neutral.actions = null;
        return neutral;
    }

    private static boolean isCriticalNotificationId(int candidate) {
        for (int id : CRITICAL_NOTIFICATION_IDS) {
            if (id == candidate) return true;
        }
        return false;
    }

    private static boolean staleCriticalNotificationActive(
            NotificationManager manager, int keepId) {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            for (android.service.notification.StatusBarNotification item
                    : manager.getActiveNotifications()) {
                if (item != null && item.getId() != keepId
                        && isCriticalNotificationId(item.getId())) return true;
            }
        } catch (RuntimeException failure) {
            Log.stack("CriticalAlarm", "verify notification cleanup", failure);
            // A failed verification is inconclusive; the remaining bounded
            // retries still issue both neutralization and exact cancellation.
            return true;
        }
        return false;
    }

    private static void scheduleCriticalNotificationCleanup(Context context,
            String endedToken) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String token = endedToken == null ? "" : endedToken;
        for (long delayMs : CRITICAL_CANCEL_RETRY_DELAYS_MS) {
            MAIN.postDelayed(() -> retryCriticalNotificationCleanup(app, token),
                    delayMs);
        }
    }

    private static synchronized void retryCriticalNotificationCleanup(
            Context context, String endedToken) {
        // Preserve only the notification ID owned by a newer session. Stale
        // critical IDs must still be neutralized when that new episode uses a
        // different ID. A new test reusing TEST_ID is also protected.
        Session live = livePresentationOwner(context, endedToken);
        int keepId = live != null
                ? notificationId(live) : -1;
        if (neutralizeAndCancelCriticalNotifications(context, keepId)
                && Log.doLog) {
            Log.e("CriticalAlarm",
                    "Reserved notification still active after OEM cleanup");
        }
    }

    private static void finishAlarmVolumeRestore(Context context,
            String endedToken,
            CriticalAlarmAudioReliability.RestorePlan restorePlan) {
        if (context == null || restorePlan == null) return;
        Context app = context.getApplicationContext();
        CriticalAlarmAudioReliability.applyPreparedAlarmVolumeRestore(app,
                restorePlan);
        String restoreOwner = restorePlan.ownerToken.isEmpty()
                ? endedToken : restorePlan.ownerToken;
        for (int index = 0; index < VOLUME_RESTORE_RETRY_DELAYS_MS.length;
                index++) {
            final boolean finalAttempt = index
                    == VOLUME_RESTORE_RETRY_DELAYS_MS.length - 1;
            MAIN.postDelayed(() -> retryAlarmVolumeRestore(app, restoreOwner,
                    restorePlan, finalAttempt),
                    VOLUME_RESTORE_RETRY_DELAYS_MS[index]);
        }
    }

    private static synchronized void retryAlarmVolumeRestore(Context context,
            String endedToken,
            CriticalAlarmAudioReliability.RestorePlan restorePlan,
            boolean finalAttempt) {
        // A new critical episode owns STREAM_ALARM now. Its floor must never be
        // overwritten by a delayed restore from the acknowledged session.
        if (livePresentationOwner(context, endedToken) != null) return;
        CriticalAlarmAudioReliability.retryPreparedAlarmVolumeRestore(context,
                restorePlan, finalAttempt);
    }

    private static Session livePresentationOwner(Context context,
            String endedToken) {
        long now = System.currentTimeMillis();
        Session live = active;
        if (!validPersistedSession(live) || live.expiresAtMs <= now) {
            live = null;
        }
        if (live == null) {
            Session persisted = load(context);
            if (validPersistedSession(persisted)
                    && persisted.expiresAtMs > now) live = persisted;
        }
        if (live == null) return null;
        if (!CriticalAlarmEpisodePolicy.actionMatches(live.token,
                endedToken)) return live;
        // The same token deliberately remains persisted while snoozed. During
        // the snooze it owns no notification/audio, but once the deadline has
        // elapsed (or resume reset it to zero) it again protects its channel
        // and volume floor from old bounded cleanup callbacks.
        return live.snoozeUntilMs <= now ? live : null;
    }

    /**
     * Fails closed when persisted ownership cannot be proven. In particular,
     * an INSISTENT notification can outlive the process or an APK update, so
     * clearing preferences alone is not enough to stop an orphan alarm.
     */
    private static void clearInvalidState(Context context, Session stale) {
        stopSound(context);
        String endedToken = stale == null || stale.token == null
                ? "" : stale.token;
        CriticalAlarmAudioReliability.RestorePlan restorePlan =
                CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(
                        context, endedToken);
        cancelAllCriticalNotifications(context);
        finishAlarmVolumeRestore(context, endedToken, restorePlan);
        // The request code is token-derived. When a usable stale token remains
        // we can also remove its exact RESUME/EXPIRE alarms; with no token the
        // receiver's stale-action check is the remaining fail-safe.
        if (stale != null && stale.token != null && !stale.token.isEmpty()) {
            cancelScheduled(context, stale);
        }
        active = null;
        clearSaved(context);
        notifySurfaces(context, null);
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
            notifySurfaces(context, session);
            schedule(context, CriticalGlucoseAlarmReceiver.ACTION_RESUME,
                    session.token, session.snoozeUntilMs);
        } else if (postNotification(context, session)) {
            notifySurfaces(context, session);
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
        session.soundToneId = sessionToneId(session);
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
                .putString(KEY_SOUND_TONE_ID, session.soundToneId)
                .putString(KEY_ALERT_TYPE, session.alertType == null ? ""
                        : session.alertType.stableId())
                .putInt(KEY_MINIMUM_VOLUME_PERCENT,
                        session.minimumVolumePercent)
                .putString(KEY_DISPLAY_PAYLOAD,
                        session.displayPayload == null ? ""
                                : session.displayPayload.toJsonString())
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
            session.alertType = CriticalAlarmSoundCatalog.AlertType
                    .fromStableId(prefs.getString(KEY_ALERT_TYPE, ""));
            if (session.alertType == null) {
                session.alertType = CriticalAlarmSoundCatalog.alertType(
                        session.source, session.direction);
            }
            // Resource-table integers are not stable across APK upgrades.
            // New sessions restore from the catalog id; legacy/corrupt ones
            // resolve the user's current stable per-alert selection instead
            // of trusting a possibly-colliding old integer.
            session.soundToneId = prefs.getString(KEY_SOUND_TONE_ID, "");
            session.soundRes = CriticalAlarmSoundCatalog.soundRes(
                    session.soundToneId);
            if (session.soundRes == 0) {
                session.soundToneId = CriticalAlarmSoundCatalog
                        .selectedToneId(context, session.alertType);
                session.soundRes = CriticalAlarmSoundCatalog.soundRes(
                        session.soundToneId);
                prefs.edit()
                        .putString(KEY_SOUND_TONE_ID, session.soundToneId)
                        .putInt(KEY_SOUND_RES, session.soundRes)
                        .apply();
            }
            session.minimumVolumePercent = prefs.getInt(
                    KEY_MINIMUM_VOLUME_PERCENT,
                    CriticalAlertPreferences.getMinimumVolumePercent(context,
                            session.alertType));
            session.displayPayload = CriticalDisplayPayload.fromJsonString(
                    prefs.getString(KEY_DISPLAY_PAYLOAD, ""));
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
        boolean signalLoss = SOURCE_SIGNAL_LOSS.equals(session.source);
        boolean test = SOURCE_TEST.equals(session.source);
        if (!actual && !predictive && !signalLoss && !test) return false;
        boolean low = DIRECTION_LOW.equals(session.direction);
        boolean high = DIRECTION_HIGH.equals(session.direction);
        boolean signal = DIRECTION_SIGNAL.equals(session.direction);
        if ((!signalLoss && !test && !low && !high)
                || (signalLoss && !signal)
                || (test && !low && !high && !signal)) return false;
        CriticalAlarmSoundCatalog.AlertType expected =
                expectedAlertType(session.source, session.direction,
                        session.alertType);
        if (session.alertType == null || session.alertType != expected
                || !CriticalAlertPreferences.validMinimumVolumePercent(
                        session.minimumVolumePercent)) return false;
        if (CriticalAlarmSoundCatalog.soundRes(session.soundToneId)
                != session.soundRes) return false;
        if (test && !directionMatchesAlertType(session.direction,
                session.alertType)) return false;
        if (actual) {
            if (session.priority != CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL
                    && session.priority
                    != CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE) {
                return false;
            }
            return knownSoundRes(session.soundRes);
        }
        if (signalLoss && session.priority
                != CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL) return false;
        if (predictive && session.priority
                != CriticalAlarmEpisodePolicy.PRIORITY_PREDICTIVE_LIKELY) {
            return false;
        }
        if (test && session.priority != 0) return false;
        return knownSoundRes(session.soundRes);
    }

    private static void clearSaved(Context context) {
        if (context != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static void notifySurfaces(Context context, Session session) {
        CriticalGlucoseAlarmActivity.sessionChanged(
                session == null ? null : session.token);
        CriticalGlucoseAlarmOverlay.sessionChanged(context,
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

    /** Rejects a user-muted/misconfigured channel before claiming ownership. */
    private static boolean channelAlarmSoundReady(Context context,
            String channel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel value = manager == null ? null
                : manager.getNotificationChannel(channel);
        AudioAttributes attributes = value == null ? null
                : value.getAudioAttributes();
        return value != null && value.getSound() != null
                && attributes != null
                && attributes.getUsage() == AudioAttributes.USAGE_ALARM;
    }

    private static boolean hasControlSurface(Context context,
            Session session) {
        if (context == null || session == null) return false;
        if (session.snoozeUntilMs > System.currentTimeMillis()) return true;
        if (!canPost(context)) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return notificationActive(context, notificationId(session));
        }
        // Validate the channel that carries the live notification rather than
        // recomputing it from current policy access. A grant made during an
        // active alarm must not make its still-valid standard channel appear
        // to have lost the acknowledgement surface.
        String channel = activeNotificationChannel(context,
                notificationId(session));
        return channel != null && channelEnabled(context, channel)
                && channelAlarmSoundReady(context, channel);
    }

    private static boolean notificationActive(Context context, int id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return activeNotificationChannel(context, id) != null;
    }

    private static String activeNotificationChannel(Context context, int id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "";
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return null;
        try {
            for (android.service.notification.StatusBarNotification value
                    : manager.getActiveNotifications()) {
                if (value != null && value.getId() == id) {
                    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? value.getNotification().getChannelId() : "";
                }
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static boolean isActualKind(int kind) {
        return kind == 0 || kind == 1 || kind == 5 || kind == 6;
    }

    private static int notificationId(Session session) {
        if (SOURCE_TEST.equals(session.source)) return TEST_ID;
        if (SOURCE_SIGNAL_LOSS.equals(session.source)) return SIGNAL_LOSS_ID;
        boolean low = DIRECTION_LOW.equals(session.direction);
        return SOURCE_ACTUAL.equals(session.source)
                ? low ? ACTUAL_LOW_ID : ACTUAL_HIGH_ID
                : low ? PREDICTIVE_LOW_ID : PREDICTIVE_HIGH_ID;
    }

    static String[] selectedChannelIds(Context context, boolean actual) {
        CriticalAlarmSoundCatalog.AlertType low = actual
                ? CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                : CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        CriticalAlarmSoundCatalog.AlertType high = actual
                ? CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH
                : CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH;
        return new String[]{selectedChannelId(context, low),
                selectedChannelId(context, high)};
    }

    static String[] selectedSignalLossChannelIds(Context context) {
        return new String[]{selectedChannelId(context,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS)};
    }

    static String selectedChannelId(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        return selectedChannelId(alertType,
                CriticalAlarmSoundCatalog.selectedToneId(context, alertType),
                dndBypassAvailable(context));
    }

    private static String selectedChannelId(Session session,
            boolean bypassDnd) {
        CriticalAlarmSoundCatalog.AlertType alertType = alertType(session);
        return selectedChannelId(alertType,
                sessionToneId(session), bypassDnd);
    }

    private static CriticalAlarmSoundCatalog.AlertType alertType(
            Session session) {
        if (session == null) {
            return CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH;
        }
        return expectedAlertType(session.source, session.direction,
                session.alertType);
    }

    private static CriticalAlarmSoundCatalog.AlertType expectedAlertType(
            String source, String direction,
            CriticalAlarmSoundCatalog.AlertType persisted) {
        if (SOURCE_TEST.equals(source) && persisted != null) return persisted;
        return CriticalAlarmSoundCatalog.alertType(source, direction);
    }

    private static boolean directionMatchesAlertType(String direction,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        if (alertType == CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS) {
            return DIRECTION_SIGNAL.equals(direction);
        }
        boolean lowType = alertType
                == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW
                || alertType
                == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        return lowType ? DIRECTION_LOW.equals(direction)
                : DIRECTION_HIGH.equals(direction);
    }

    private static String selectedChannelId(
            CriticalAlarmSoundCatalog.AlertType alertType, String toneId,
            boolean bypassDnd) {
        return "critical_delivery_" + channelTypeKey(alertType) + '_'
                + SELECTED_CHANNEL_VERSION + '_'
                + (bypassDnd ? "bypass" : "standard") + '_'
                + safeToneId(toneId);
    }

    private static String channelTypeKey(
            CriticalAlarmSoundCatalog.AlertType alertType) {
        if (alertType == CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS) {
            return "signal_loss";
        }
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW) {
            return "actual_low";
        }
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH) {
            return "actual_high";
        }
        if (alertType == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW) {
            return "predictive_low";
        }
        return "predictive_high";
    }

    private static String safeToneId(String toneId) {
        if (toneId == null || toneId.isEmpty()) return "siren";
        StringBuilder safe = new StringBuilder(toneId.length());
        for (int index = 0; index < toneId.length(); index++) {
            char value = toneId.charAt(index);
            if ((value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_') {
                safe.append(value);
            }
        }
        return safe.length() == 0 ? "siren" : safe.toString();
    }

    private static String toneIdForSoundRes(int soundRes) {
        for (CriticalAlarmSoundCatalog.Tone tone
                : CriticalAlarmSoundCatalog.tones()) {
            if (tone.soundRes == soundRes) return tone.id;
        }
        return "siren";
    }

    private static String sessionToneId(Session session) {
        if (session == null) return "siren";
        if (CriticalAlarmSoundCatalog.soundRes(session.soundToneId)
                == session.soundRes) return session.soundToneId;
        return toneIdForSoundRes(session.soundRes);
    }

    private static boolean knownSoundRes(int soundRes) {
        for (CriticalAlarmSoundCatalog.Tone tone
                : CriticalAlarmSoundCatalog.tones()) {
            if (tone.soundRes == soundRes) return true;
        }
        return false;
    }

    private static void ensureSessionChannel(Context context,
            Session session, boolean bypassDnd) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context == null || session == null) return;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        CriticalAlarmSoundCatalog.AlertType alertType =
                alertType(session);
        createSelectedChannel(context, manager, alertType,
                sessionToneId(session), session.soundRes,
                bypassDnd);
    }

    private static boolean dndBypassAvailable(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (context == null) return false;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        return dndBypassAvailable(manager);
    }

    private static boolean dndBypassAvailable(
            NotificationManager manager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (manager != null
                && manager.isNotificationPolicyAccessGranted());
    }

    private static Uri resourceUri(Context context, int res) {
        return Uri.parse("android.resource://" + context.getPackageName()
                + '/' + res);
    }

    private static Uri stableResourceUri(Context context, int res) {
        try {
            return new Uri.Builder()
                    .scheme("android.resource")
                    .authority(context.getPackageName())
                    .appendPath(context.getResources().getResourceTypeName(res))
                    .appendPath(context.getResources().getResourceEntryName(res))
                    .build();
        } catch (RuntimeException missingResource) {
            return resourceUri(context, res);
        }
    }

    private static long[] vibration(Session session) {
        if (session.signalLoss()) return SIGNAL_LOSS_VIBRATION;
        return DIRECTION_LOW.equals(session.direction)
                ? LOW_VIBRATION : HIGH_VIBRATION;
    }

    private static long soundDurationMs(int soundRes) {
        return CriticalAlarmSoundCatalog.durationMs(soundRes);
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
        String soundToneId;
        CriticalAlarmSoundCatalog.AlertType alertType;
        int minimumVolumePercent =
                CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT;
        CriticalDisplayPayload displayPayload = CriticalDisplayPayload.EMPTY;

        boolean low() {
            return DIRECTION_LOW.equals(direction);
        }

        boolean actual() {
            return SOURCE_ACTUAL.equals(source);
        }

        boolean test() {
            return SOURCE_TEST.equals(source);
        }

        boolean signalLoss() {
            return alertType == CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS
                    || SOURCE_SIGNAL_LOSS.equals(source);
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
            result.soundToneId = soundToneId;
            result.alertType = alertType;
            result.minimumVolumePercent = minimumVolumePercent;
            result.displayPayload = displayPayload == null
                    ? CriticalDisplayPayload.EMPTY : displayPayload;
            return result;
        }
    }
}
