package tk.glucodata;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Read-only Android readiness checks for urgent glucose alarms.
 *
 * <p>This class deliberately does not create notification channels or play an
 * alarm. The alarm runtime owns those side effects and may register a test hook
 * once it is ready. Keeping diagnostics read-only prevents a settings screen
 * from reporting success after creating a channel that the delivery runtime
 * does not actually use.</p>
 */
final class CriticalAlarmDiagnostics {
    static final String ACTUAL_LOW_CHANNEL_ID = "critical_actual_low_v2";
    static final String ACTUAL_HIGH_CHANNEL_ID = "critical_actual_high_v2";
    static final String PREDICTIVE_LOW_CHANNEL_ID =
            "critical_predictive_low_v2";
    static final String PREDICTIVE_HIGH_CHANNEL_ID =
            "critical_predictive_high_v2";
    static final String SIGNAL_LOSS_CHANNEL_ID = "critical_signal_loss_v2";

    interface TestHook {
        boolean show(Context context,
                CriticalAlarmSoundCatalog.AlertType alertType);
    }

    private static volatile TestHook testHook;

    private CriticalAlarmDiagnostics() {}

    static Snapshot inspect(Context context) {
        if (context == null) return Snapshot.unavailable();
        Context app = context.getApplicationContext();
        NotificationManager notifications = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        AudioManager audio = (AudioManager)
                app.getSystemService(Context.AUDIO_SERVICE);
        AlarmManager alarms = (AlarmManager)
                app.getSystemService(Context.ALARM_SERVICE);

        boolean postPermission = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean notificationsEnabled = NotificationManagerCompat.from(app)
                .areNotificationsEnabled();
        int alarmVolume = audio == null ? -1
                : audio.getStreamVolume(AudioManager.STREAM_ALARM);
        int maxAlarmVolume = audio == null ? -1
                : audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        boolean policyAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (notifications != null
                && notifications.isNotificationPolicyAccessGranted());
        boolean fullScreenAccess = fullScreenAccess(app, notifications);
        boolean overlayAccess = overlayAccess(app);
        boolean exactAlarmAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (alarms != null && alarms.canScheduleExactAlarms());

        // Read the channels that match the currently selected built-in tones.
        // The legacy v2 constants remain in use for ordinary predictive HUNs;
        // critical delivery has versioned per-tone channels because Android
        // deliberately makes a channel's sound immutable after creation.
        ChannelReadiness actual = channelReadiness(notifications,
                CriticalGlucoseAlarm.selectedChannelIds(app, true));
        ChannelReadiness predictive = channelReadiness(notifications,
                CriticalGlucoseAlarm.selectedChannelIds(app, false));
        ChannelReadiness signalLoss = channelReadiness(notifications,
                CriticalGlucoseAlarm.selectedSignalLossChannelIds(app));
        return new Snapshot(postPermission, notificationsEnabled,
                alarmVolume, maxAlarmVolume, policyAccess, fullScreenAccess,
                overlayAccess, exactAlarmAccess, actual, predictive,
                signalLoss, testHook != null);
    }

    private static boolean fullScreenAccess(Context context,
            @Nullable NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= 34) {
            return manager != null && manager.canUseFullScreenIntent();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.USE_FULL_SCREEN_INTENT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether Android allows the urgent surface above another unlocked app. */
    static boolean overlayAccess(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.SYSTEM_ALERT_WINDOW)
                == PackageManager.PERMISSION_GRANTED;
    }

    static ChannelReadiness channelReadiness(
            @Nullable NotificationManager manager, String[] channelIds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return new ChannelReadiness(true, true, true, 0);
        }
        if (manager == null || channelIds == null || channelIds.length == 0) {
            return ChannelReadiness.missing();
        }
        boolean highImportance = true;
        boolean alarmSound = true;
        boolean bypassDnd = true;
        int present = 0;
        for (String channelId : channelIds) {
            NotificationChannel channel = manager.getNotificationChannel(channelId);
            if (channel == null) {
                highImportance = false;
                alarmSound = false;
                bypassDnd = false;
                continue;
            }
            present++;
            highImportance &= channel.getImportance()
                    >= NotificationManager.IMPORTANCE_HIGH;
            AudioAttributes attributes = channel.getAudioAttributes();
            alarmSound &= channel.getSound() != null
                    && attributes != null
                    && attributes.getUsage() == AudioAttributes.USAGE_ALARM;
            bypassDnd &= channel.canBypassDnd();
        }
        return new ChannelReadiness(present == channelIds.length
                && highImportance, present == channelIds.length && alarmSound,
                present == channelIds.length && bypassDnd, present);
    }

    static void registerTestHook(@Nullable TestHook hook) {
        testHook = hook;
    }

    static boolean showTest(Context context, boolean low) {
        return showTest(context, low
                ? CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW
                : CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH);
    }

    static boolean showTest(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        TestHook hook = testHook;
        if (context == null || alertType == null || hook == null) return false;
        try {
            return hook.show(context, alertType);
        } catch (RuntimeException failure) {
            if (Log.doLog) Log.e("CriticalAlarmDiagnostics",
                    "Critical test failed: "
                            + failure.getClass().getSimpleName());
            return false;
        }
    }

    static void openNotificationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        startSettings(context, intent);
    }

    static void openAlarmSoundSettings(Context context) {
        startSettings(context, new Intent(Settings.ACTION_SOUND_SETTINGS));
    }

    static void openDndSettings(Context context) {
        startSettings(context,
                new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
    }

    static void openFullScreenSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + context.getPackageName()));
            startSettings(context, intent);
            return;
        }
        openAppDetails(context);
    }

    static void openOverlaySettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            startSettings(context, intent);
            return;
        }
        openAppDetails(context);
    }

    static void openExactAlarmSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + context.getPackageName()));
            startSettings(context, intent);
            return;
        }
        openAppDetails(context);
    }

    private static void openAppDetails(Context context) {
        startSettings(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())));
    }

    private static void startSettings(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
        } catch (RuntimeException unavailable) {
            Intent fallback = new Intent(Settings.ACTION_SETTINGS);
            if (!(context instanceof Activity)) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try {
                context.startActivity(fallback);
            } catch (RuntimeException ignored) {
                if (Log.doLog) Log.e("CriticalAlarmDiagnostics",
                        "No Android settings activity is available");
            }
        }
    }

    static final class ChannelReadiness {
        final boolean highImportance;
        final boolean alarmSound;
        final boolean bypassDnd;
        final int presentCount;

        ChannelReadiness(boolean highImportance, boolean alarmSound,
                boolean bypassDnd, int presentCount) {
            this.highImportance = highImportance;
            this.alarmSound = alarmSound;
            this.bypassDnd = bypassDnd;
            this.presentCount = presentCount;
        }

        static ChannelReadiness missing() {
            return new ChannelReadiness(false, false, false, 0);
        }

        boolean ready() {
            return highImportance && alarmSound;
        }
    }

    static final class Snapshot {
        final boolean postPermission;
        final boolean notificationsEnabled;
        final int alarmVolume;
        final int maxAlarmVolume;
        final boolean dndPolicyAccess;
        final boolean fullScreenAccess;
        final boolean overlayAccess;
        final boolean exactAlarmAccess;
        final ChannelReadiness actualChannels;
        final ChannelReadiness predictiveChannels;
        final ChannelReadiness signalLossChannels;
        final boolean testAvailable;

        Snapshot(boolean postPermission, boolean notificationsEnabled,
                int alarmVolume, int maxAlarmVolume, boolean dndPolicyAccess,
                boolean fullScreenAccess, boolean overlayAccess,
                boolean exactAlarmAccess,
                ChannelReadiness actualChannels,
                ChannelReadiness predictiveChannels,
                ChannelReadiness signalLossChannels,
                boolean testAvailable) {
            this.postPermission = postPermission;
            this.notificationsEnabled = notificationsEnabled;
            this.alarmVolume = alarmVolume;
            this.maxAlarmVolume = maxAlarmVolume;
            this.dndPolicyAccess = dndPolicyAccess;
            this.fullScreenAccess = fullScreenAccess;
            this.overlayAccess = overlayAccess;
            this.exactAlarmAccess = exactAlarmAccess;
            this.actualChannels = actualChannels;
            this.predictiveChannels = predictiveChannels;
            this.signalLossChannels = signalLossChannels;
            this.testAvailable = testAvailable;
        }

        Snapshot(boolean postPermission, boolean notificationsEnabled,
                int alarmVolume, int maxAlarmVolume, boolean dndPolicyAccess,
                boolean fullScreenAccess, boolean overlayAccess,
                boolean exactAlarmAccess,
                ChannelReadiness actualChannels,
                ChannelReadiness predictiveChannels, boolean testAvailable) {
            this(postPermission, notificationsEnabled, alarmVolume,
                    maxAlarmVolume, dndPolicyAccess, fullScreenAccess,
                    overlayAccess, exactAlarmAccess, actualChannels,
                    predictiveChannels, actualChannels, testAvailable);
        }

        static Snapshot unavailable() {
            return new Snapshot(false, false, -1, -1, false, false, false,
                    false, ChannelReadiness.missing(),
                    ChannelReadiness.missing(), ChannelReadiness.missing(),
                    false);
        }

        boolean notificationAccess() {
            return postPermission && notificationsEnabled;
        }

        boolean alarmVolumeAudible() {
            return alarmVolume > 0;
        }

        int alarmVolumePercent() {
            if (alarmVolume < 0 || maxAlarmVolume <= 0) return -1;
            return Math.round(alarmVolume * 100f / maxAlarmVolume);
        }

        boolean actualConfigured() {
            return commonConfigured() && actualChannels.ready()
                    && actualChannels.bypassDnd;
        }

        boolean predictiveConfigured() {
            return commonConfigured() && predictiveChannels.ready()
                    && predictiveChannels.bypassDnd;
        }

        boolean signalLossConfigured() {
            return commonConfigured() && signalLossChannels.ready()
                    && signalLossChannels.bypassDnd;
        }

        boolean maximallyConfigured() {
            return actualConfigured() && predictiveConfigured()
                    && signalLossConfigured();
        }

        private boolean commonConfigured() {
            return notificationAccess() && alarmVolumeAudible()
                    && dndPolicyAccess && fullScreenAccess && overlayAccess
                    && exactAlarmAccess;
        }
    }
}
