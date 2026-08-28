package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmDiagnosticsTest {

    @After
    public void clearHook() {
        CriticalAlarmDiagnostics.registerTestHook(null);
    }

    @Test
    public void stableChannelIdsAreSeparatedByActualPredictiveAndDirection() {
        assertEquals("critical_actual_low_v2",
                CriticalAlarmDiagnostics.ACTUAL_LOW_CHANNEL_ID);
        assertEquals("critical_actual_high_v2",
                CriticalAlarmDiagnostics.ACTUAL_HIGH_CHANNEL_ID);
        assertEquals("critical_predictive_low_v2",
                CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID);
        assertEquals("critical_predictive_high_v2",
                CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID);
        assertEquals("critical_signal_loss_v2",
                CriticalAlarmDiagnostics.SIGNAL_LOSS_CHANNEL_ID);
    }

    @Test
    public void missingOrIncompleteChannelsFailClosed() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        String first = "diagnostic_incomplete_first";
        String second = "diagnostic_incomplete_second";
        manager.deleteNotificationChannel(first);
        manager.deleteNotificationChannel(second);

        NotificationChannel channel = new NotificationChannel(first, first,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(Uri.parse("content://tk.glucodata/test-alarm"),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).build());
        manager.createNotificationChannel(channel);

        CriticalAlarmDiagnostics.ChannelReadiness readiness =
                CriticalAlarmDiagnostics.channelReadiness(manager,
                        new String[]{first, second});
        assertEquals(1, readiness.presentCount);
        assertFalse(readiness.ready());
    }

    @Test
    public void channelReadinessRequiresHighImportanceAndAlarmAudioUsage() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        String highAlarm = "diagnostic_high_alarm";
        String defaultNotification = "diagnostic_default_notification";
        manager.deleteNotificationChannel(highAlarm);
        manager.deleteNotificationChannel(defaultNotification);

        NotificationChannel alarm = new NotificationChannel(highAlarm,
                highAlarm, NotificationManager.IMPORTANCE_HIGH);
        alarm.setSound(Uri.parse("content://tk.glucodata/test-alarm-high"),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).build());
        manager.createNotificationChannel(alarm);
        CriticalAlarmDiagnostics.ChannelReadiness ready =
                CriticalAlarmDiagnostics.channelReadiness(manager,
                        new String[]{highAlarm});
        assertTrue(ready.highImportance);
        assertTrue(ready.alarmSound);
        assertTrue(ready.ready());

        NotificationChannel notification = new NotificationChannel(
                defaultNotification, defaultNotification,
                NotificationManager.IMPORTANCE_DEFAULT);
        notification.setSound(
                Uri.parse("content://tk.glucodata/test-notification"),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        manager.createNotificationChannel(notification);
        CriticalAlarmDiagnostics.ChannelReadiness notReady =
                CriticalAlarmDiagnostics.channelReadiness(manager,
                        new String[]{defaultNotification});
        assertFalse(notReady.highImportance);
        assertFalse(notReady.alarmSound);
        assertFalse(notReady.ready());
    }

    @Test
    public void maximumReadinessRequiresEverySystemAndChannelCheck() {
        CriticalAlarmDiagnostics.ChannelReadiness channels =
                new CriticalAlarmDiagnostics.ChannelReadiness(
                        true, true, true, 2);
        CriticalAlarmDiagnostics.Snapshot ready =
                new CriticalAlarmDiagnostics.Snapshot(true, true,
                        5, 10, true, true, true, true,
                        channels, channels, true);
        assertEquals(50, ready.alarmVolumePercent());
        assertTrue(ready.actualConfigured());
        assertTrue(ready.predictiveConfigured());
        assertTrue(ready.signalLossConfigured());
        assertTrue(ready.maximallyConfigured());

        CriticalAlarmDiagnostics.Snapshot silent =
                new CriticalAlarmDiagnostics.Snapshot(true, true,
                        0, 10, true, true, true, true,
                        channels, channels, true);
        assertFalse(silent.alarmVolumeAudible());
        assertFalse(silent.maximallyConfigured());

        CriticalAlarmDiagnostics.ChannelReadiness noDndOverride =
                new CriticalAlarmDiagnostics.ChannelReadiness(
                        true, true, false, 2);
        CriticalAlarmDiagnostics.Snapshot noOverride =
                new CriticalAlarmDiagnostics.Snapshot(true, true,
                        5, 10, true, true, true, true,
                        noDndOverride, channels, true);
        assertFalse(noOverride.actualConfigured());
        assertFalse(noOverride.maximallyConfigured());

        CriticalAlarmDiagnostics.Snapshot noOverlay =
                new CriticalAlarmDiagnostics.Snapshot(true, true,
                        5, 10, true, true, false, true,
                        channels, channels, true);
        assertFalse(noOverlay.overlayAccess);
        assertFalse(noOverlay.maximallyConfigured());
    }

    @Test
    public void testHookIsExplicitAndFailureSafe() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(CriticalAlarmDiagnostics.inspect(context).testAvailable);
        assertFalse(CriticalAlarmDiagnostics.showTest(context, true));

        CriticalAlarmDiagnostics.registerTestHook((ignored, type) -> type
                == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW);
        assertTrue(CriticalAlarmDiagnostics.inspect(context).testAvailable);
        assertTrue(CriticalAlarmDiagnostics.showTest(context, true));
        assertFalse(CriticalAlarmDiagnostics.showTest(context, false));
        assertFalse(CriticalAlarmDiagnostics.showTest(context,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS));

        CriticalAlarmDiagnostics.registerTestHook((ignored, type) -> {
            throw new IllegalStateException("test failure");
        });
        assertFalse(CriticalAlarmDiagnostics.showTest(context, true));
    }
}
