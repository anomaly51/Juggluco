package tk.glucodata;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.service.notification.StatusBarNotification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/** Posts versioned, non-full-screen forecast notifications. */
final class PredictiveAlertNotifier {
    static final String EXTRA_OPEN_FORECAST =
            "tk.glucodata.extra.OPEN_PREDICTIVE_FORECAST";
    static final String LOW_CHANNEL_ID = "predictive_glucose_low_v1";
    static final String HIGH_CHANNEL_ID = "predictive_glucose_high_v1";
    private static final int LOW_NOTIFICATION_ID = 8_201;
    private static final int HIGH_NOTIFICATION_ID = 8_202;
    private static final int TEST_NOTIFICATION_ID = 8_203;
    private static volatile long lowActiveUntilMs;
    private static volatile long highActiveUntilMs;

    private PredictiveAlertNotifier() {}

    /**
     * Notification timeout is a platform guarantee only from Android 8.0.
     * Older releases therefore stay fail-closed instead of leaving a stale
     * time-sensitive warning visible indefinitely.
     */
    static boolean supportsExpiringAlerts() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        if (manager == null) return;

        NotificationChannel low = new NotificationChannel(LOW_CHANNEL_ID,
                context.getString(R.string.predictive_alert_channel_low),
                NotificationManager.IMPORTANCE_HIGH);
        low.setDescription(context.getString(
                R.string.predictive_alert_channel_low_description));
        low.enableVibration(true);
        low.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(low);

        NotificationChannel high = new NotificationChannel(HIGH_CHANNEL_ID,
                context.getString(R.string.predictive_alert_channel_high),
                NotificationManager.IMPORTANCE_DEFAULT);
        high.setDescription(context.getString(
                R.string.predictive_alert_channel_high_description));
        high.enableVibration(true);
        high.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(high);
    }

    static boolean canPost(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // NotificationManagerCompat also detects an app-level notification
        // block on API 21-23, where NotificationManager did not expose it.
        return NotificationManagerCompat.from(context)
                .areNotificationsEnabled();
    }

    static boolean channelsEnabled(Context context) {
        return channelsEnabled(context, true, true);
    }

    static boolean channelsEnabled(Context context, boolean requireLow,
            boolean requireHigh) {
        return (!requireLow || channelEnabled(context, LOW_CHANNEL_ID))
                && (!requireHigh || channelEnabled(context, HIGH_CHANNEL_ID));
    }

    static boolean channelEnabled(Context context, String channelId) {
        if (!supportsExpiringAlerts() || !canPost(context)) return false;
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean show(Context context,
            ForecastRiskEvaluator.Decision decision) {
        if (context == null || decision == null || !decision.shouldNotify()) {
            return false;
        }
        ensureChannels(context);
        boolean low = decision.direction == ForecastRiskEvaluator.Direction.LOW;
        String channel = low ? LOW_CHANNEL_ID : HIGH_CHANNEL_ID;
        if (!channelEnabled(context, channel)) return false;
        int notificationId = low ? LOW_NOTIFICATION_ID : HIGH_NOTIFICATION_ID;
        String title = context.getString(low
                        ? R.string.predictive_alert_low_title
                        : R.string.predictive_alert_high_title,
                decision.leadMinutes);
        String body = notificationBody(context, decision);
        long postedAtMs = System.currentTimeMillis();
        long timeoutMs = notificationTimeoutMs(decision, postedAtMs);

        Notification notification = baseBuilder(context, channel, title, body,
                low)
                .setWhen(decision.anchorMs)
                // Cooldown/episode policy already suppresses duplicates. A
                // legitimate repeat after that interval must make sound again.
                .setOnlyAlertOnce(false)
                .setTimeoutAfter(timeoutMs)
                .build();
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        manager.notify(notificationId, notification);
        setActiveUntil(decision.direction, postedAtMs + timeoutMs);
        return true;
    }

    static long notificationTimeoutMs(ForecastRiskEvaluator.Decision decision,
            long postedAtMs) {
        if (decision == null) return 0L;
        long remainingMs = decision.crossingAtMs - postedAtMs;
        return Math.max(15L * 60_000L,
                Math.min(90L * 60_000L, remainingMs + 30L * 60_000L));
    }

    static long activeUntilMs(ForecastRiskEvaluator.Direction direction) {
        if (direction == ForecastRiskEvaluator.Direction.LOW) {
            return lowActiveUntilMs;
        }
        if (direction == ForecastRiskEvaluator.Direction.HIGH) {
            return highActiveUntilMs;
        }
        return 0L;
    }

    /**
     * A process-local post receipt is not enough after the user or platform has
     * removed the notification. The native handoff is retained only while the
     * exact notification id is still active and its timeout has not elapsed.
     */
    static boolean notificationActive(Context context,
            ForecastRiskEvaluator.Direction direction, long nowMs) {
        long activeUntilMs = activeUntilMs(direction);
        if (context == null || direction == null || activeUntilMs <= nowMs
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        if (manager == null) return false;
        int expectedId = direction == ForecastRiskEvaluator.Direction.LOW
                ? LOW_NOTIFICATION_ID : HIGH_NOTIFICATION_ID;
        try {
            for (StatusBarNotification notification
                    : manager.getActiveNotifications()) {
                if (notification != null && notification.getId() == expectedId) {
                    return true;
                }
            }
        } catch (RuntimeException error) {
            if (Log.doLog) Log.e("PredictiveAlerts",
                    "active notification unavailable: "
                            + error.getClass().getSimpleName());
        }
        return false;
    }

    private static void setActiveUntil(
            ForecastRiskEvaluator.Direction direction, long expiresAtMs) {
        if (direction == ForecastRiskEvaluator.Direction.LOW) {
            lowActiveUntilMs = Math.max(0L, expiresAtMs);
        } else if (direction == ForecastRiskEvaluator.Direction.HIGH) {
            highActiveUntilMs = Math.max(0L, expiresAtMs);
        }
    }

    static boolean showTest(Context context) {
        return showTest(context, true, false);
    }

    static boolean showTest(Context context, boolean lowEnabled,
            boolean highEnabled) {
        if (context == null) return false;
        ensureChannels(context);
        String channelId = testChannelId(context, lowEnabled, highEnabled);
        if (!channelEnabled(context, channelId)) return false;
        boolean low = LOW_CHANNEL_ID.equals(channelId);
        Notification notification = baseBuilder(context, channelId,
                context.getString(R.string.predictive_alert_test_title),
                context.getString(R.string.predictive_alert_test_body), low)
                .setOnlyAlertOnce(false)
                .build();
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        manager.notify(TEST_NOTIFICATION_ID, notification);
        return true;
    }

    static void cancel(Context context, ForecastRiskEvaluator.Direction direction) {
        if (direction == ForecastRiskEvaluator.Direction.LOW) {
            lowActiveUntilMs = 0L;
        } else if (direction == ForecastRiskEvaluator.Direction.HIGH) {
            highActiveUntilMs = 0L;
        }
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (direction == ForecastRiskEvaluator.Direction.LOW) {
            manager.cancel(LOW_NOTIFICATION_ID);
        } else if (direction == ForecastRiskEvaluator.Direction.HIGH) {
            manager.cancel(HIGH_NOTIFICATION_ID);
        }
    }

    static void cancelAll(Context context) {
        lowActiveUntilMs = 0L;
        highActiveUntilMs = 0L;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(LOW_NOTIFICATION_ID);
        manager.cancel(HIGH_NOTIFICATION_ID);
    }

    static void openSystemChannelSettings(Context context) {
        openSystemChannelSettings(context, true, false);
    }

    static void openSystemChannelSettings(Context context, boolean lowEnabled,
            boolean highEnabled) {
        if (context == null) return;
        ensureChannels(context);
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = settingsChannelId(context, lowEnabled,
                    highEnabled);
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE,
                            context.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.getPackageName()));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Selects an enabled, deliverable direction for the test when possible. */
    static String testChannelId(Context context, boolean lowEnabled,
            boolean highEnabled) {
        if (lowEnabled && channelEnabled(context, LOW_CHANNEL_ID)) {
            return LOW_CHANNEL_ID;
        }
        if (highEnabled && channelEnabled(context, HIGH_CHANNEL_ID)) {
            return HIGH_CHANNEL_ID;
        }
        return selectedChannelId(lowEnabled, highEnabled);
    }

    /** Opens the first blocked required channel; otherwise the active one. */
    static String settingsChannelId(Context context, boolean lowEnabled,
            boolean highEnabled) {
        if (lowEnabled && !channelEnabled(context, LOW_CHANNEL_ID)) {
            return LOW_CHANNEL_ID;
        }
        if (highEnabled && !channelEnabled(context, HIGH_CHANNEL_ID)) {
            return HIGH_CHANNEL_ID;
        }
        return selectedChannelId(lowEnabled, highEnabled);
    }

    private static String selectedChannelId(boolean lowEnabled,
            boolean highEnabled) {
        if (highEnabled && !lowEnabled) return HIGH_CHANNEL_ID;
        return LOW_CHANNEL_ID;
    }

    static int bodyResource(ForecastRiskEvaluator.Decision decision) {
        if (decision != null && "possible".equals(decision.evidence)) {
            return decision.direction == ForecastRiskEvaluator.Direction.HIGH
                    ? R.string.predictive_alert_body_possible_high
                    : R.string.predictive_alert_body_possible_low;
        }
        return R.string.predictive_alert_body;
    }

    private static String notificationBody(Context context,
            ForecastRiskEvaluator.Decision decision) {
        String median = glucose(context, decision.predictedMedianMgDl);
        String target = target(context);
        int resource = bodyResource(decision);
        if (resource == R.string.predictive_alert_body) {
            return context.getString(resource, median, target);
        }
        String intervalEdge = glucose(context, decision.intervalEdgeMgDl);
        return context.getString(resource, intervalEdge, median, target);
    }

    private static NotificationCompat.Builder baseBuilder(Context context,
            String channel, String title, String body, boolean low) {
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_OPEN_FORECAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(context,
                low ? LOW_NOTIFICATION_ID : HIGH_NOTIFICATION_ID,
                open, flags);
        return new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(low ? NotificationCompat.PRIORITY_HIGH
                        : NotificationCompat.PRIORITY_DEFAULT)
                .setColor(low ? ClinicalUi.danger(context) : 0xFFF2B84B)
                .addAction(0,
                        context.getString(R.string.predictive_alert_open_forecast),
                        content);
    }

    private static String glucose(Context context, float mgDl) {
        if (Natives.getunit() == 1) {
            return String.format(Applic.usedlocale == null
                            ? Locale.getDefault() : Applic.usedlocale,
                    "%.1f %s", mgDl / 18f,
                    context.getString(R.string.mmolL));
        }
        return String.format(Applic.usedlocale == null
                        ? Locale.getDefault() : Applic.usedlocale,
                "%.0f %s", mgDl, context.getString(R.string.mgdL));
    }

    private static String target(Context context) {
        if (Natives.getunit() == 1) {
            return "4.2–9.0 " + context.getString(R.string.mmolL);
        }
        return "76–162 " + context.getString(R.string.mgdL);
    }
}
