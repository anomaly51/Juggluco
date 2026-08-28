package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.WindowManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowNotificationManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CriticalGlucoseAlarmTest {
    private static final String CRITICAL_PREFS =
            "critical_glucose_alarm_v1";

    private Application application;
    private NotificationManager notifications;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        assertNotNull(notifications);
        ShadowNotificationManager shadow = shadowOf(notifications);
        shadow.setNotificationsEnabled(true);
        shadow.setNotificationPolicyAccessGranted(true);

        clearRuntimeSession();
        application.getSharedPreferences(CriticalAlarmSoundCatalog.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        application.getSharedPreferences(CriticalAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        notifications.cancelAll();
        deleteCriticalChannels();
        CriticalGlucoseAlarm.ensureChannels(application);
    }

    @After
    public void tearDown() {
        clearRuntimeSession();
        notifications.cancelAll();
        application.getSharedPreferences(
                PredictiveAlertPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences(CriticalAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void versionedChannelsAreHighPrivateAlarmUsageAndBypassWithAccess() {
        for (String id : criticalChannelIds()) {
            NotificationChannel channel = notifications.getNotificationChannel(id);
            assertNotNull(id, channel);
            assertEquals(NotificationManager.IMPORTANCE_HIGH,
                    channel.getImportance());
            assertEquals(Notification.VISIBILITY_PRIVATE,
                    channel.getLockscreenVisibility());
            assertNotNull(channel.getSound());
            assertNotNull(channel.getAudioAttributes());
            assertEquals(AudioAttributes.USAGE_ALARM,
                    channel.getAudioAttributes().getUsage());
            assertTrue("DND bypass missing for " + id, channel.canBypassDnd());
        }
        for (String id : selectedCriticalChannelIds()) {
            NotificationChannel channel = notifications.getNotificationChannel(id);
            assertNotNull(id, channel);
            assertEquals(NotificationManager.IMPORTANCE_HIGH,
                    channel.getImportance());
            assertEquals(Notification.VISIBILITY_PRIVATE,
                    channel.getLockscreenVisibility());
            assertNotNull(channel.getSound());
            assertEquals(AudioAttributes.USAGE_ALARM,
                    channel.getAudioAttributes().getUsage());
            assertTrue("DND bypass missing for " + id, channel.canBypassDnd());
        }
    }

    @Test
    public void selectedSoundUsesMatchingImmutableAlarmChannel() {
        assertTrue(CriticalAlarmSoundCatalog.select(application,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, "siren"));
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0, 65f,
                "Low", true));

        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(session);
        assertEquals(R.raw.siren, session.soundRes);
        assertEquals("siren", session.soundToneId);
        String channelId = selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
        assertTrue(channelId.endsWith("_v4_bypass_siren"));
        NotificationChannel channel =
                notifications.getNotificationChannel(channelId);
        assertNotNull(channel);
        assertTrue(channel.getSound().toString().endsWith("/raw/siren"));
        assertEquals(AudioAttributes.USAGE_ALARM,
                channel.getAudioAttributes().getUsage());
        assertNotNull(activeNotification(channelId));
    }

    @Test
    public void persistedSessionRestoresSoundFromStableToneId()
            throws Exception {
        CriticalAlarmSoundCatalog.AlertType type =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        assertTrue(CriticalAlarmSoundCatalog.select(application, type,
                "calm_chord"));
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        String currentToken = token();
        CriticalGlucoseAlarm.Session started =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(started);
        assertEquals("calm_chord", started.soundToneId);
        assertEquals(R.raw.alert_calm_chord, started.soundRes);

        // Simulate an APK resource-table change: the legacy integer now
        // points at another valid sound. The stable catalog id must win.
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().putInt("sound_res", R.raw.alert_air_horn).commit();
        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        CriticalGlucoseAlarm.Session restored =
                CriticalGlucoseAlarm.session(application, currentToken);
        assertNotNull(restored);
        assertEquals("calm_chord", restored.soundToneId);
        assertEquals(R.raw.alert_calm_chord, restored.soundRes);
        assertTrue(selectedChannel(type).endsWith("_calm_chord"));
    }

    @Test
    public void legacySessionMigratesToCurrentStableSelection()
            throws Exception {
        CriticalAlarmSoundCatalog.AlertType type =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH;
        assertTrue(CriticalAlarmSoundCatalog.select(application, type,
                "crystal_bells"));
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        String currentToken = token();
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().remove("sound_tone_id")
                .putInt("sound_res", R.raw.siren).commit();

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        CriticalGlucoseAlarm.Session restored =
                CriticalGlucoseAlarm.session(application, currentToken);
        assertNotNull(restored);
        assertEquals("crystal_bells", restored.soundToneId);
        assertEquals(R.raw.alert_crystal_bells, restored.soundRes);
        assertEquals("crystal_bells", application.getSharedPreferences(
                CRITICAL_PREFS, Context.MODE_PRIVATE)
                .getString("sound_tone_id", ""));
    }

    @Test
    public void criticalPostAndAcknowledgeStopAnySettingsPreview()
            throws Exception {
        Field previewTimeout = CriticalAlarmSoundCatalog.class
                .getDeclaredField("previewTimeout");
        previewTimeout.setAccessible(true);
        Runnable marker = () -> {};
        previewTimeout.set(null, marker);

        assertTrue(CriticalGlucoseAlarm.showTest(application, true));
        assertNull(previewTimeout.get(null));

        previewTimeout.set(null, marker);
        assertFalse(CriticalGlucoseAlarm.previewSound(application,
                "calm_chord"));
        assertSame(marker, previewTimeout.get(null));
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, token()));
        assertNull(previewTimeout.get(null));
    }

    @Test
    public void dndGrantAfterFirstLaunchSelectsNewBypassChannels() {
        ShadowNotificationManager shadow = shadowOf(notifications);
        CriticalAlarmSoundCatalog.AlertType[] types =
                CriticalAlarmSoundCatalog.AlertType.values();

        shadow.setNotificationPolicyAccessGranted(false);
        String[] standardIds = selectedCriticalChannelIds();
        shadow.setNotificationPolicyAccessGranted(true);
        String[] bypassIds = selectedCriticalChannelIds();
        assertEquals(types.length, standardIds.length);
        assertEquals(types.length, bypassIds.length);
        for (int index = 0; index < types.length; index++) {
            assertNotEquals(standardIds[index], bypassIds[index]);
            notifications.deleteNotificationChannel(standardIds[index]);
            notifications.deleteNotificationChannel(bypassIds[index]);
        }

        shadow.setNotificationPolicyAccessGranted(false);
        CriticalGlucoseAlarm.ensureChannels(application);
        CriticalAlarmDiagnostics.Snapshot beforeGrant =
                CriticalAlarmDiagnostics.inspect(application);
        assertFalse(beforeGrant.dndPolicyAccess);
        assertFalse(beforeGrant.actualChannels.bypassDnd);
        assertFalse(beforeGrant.predictiveChannels.bypassDnd);
        assertFalse(beforeGrant.signalLossChannels.bypassDnd);
        for (int index = 0; index < types.length; index++) {
            NotificationChannel standard = notifications
                    .getNotificationChannel(standardIds[index]);
            assertNotNull(standardIds[index], standard);
            assertFalse(standard.canBypassDnd());
            assertNull(notifications.getNotificationChannel(
                    bypassIds[index]));
        }

        shadow.setNotificationPolicyAccessGranted(true);
        CriticalGlucoseAlarm.ensureChannels(application);
        CriticalAlarmDiagnostics.Snapshot afterGrant =
                CriticalAlarmDiagnostics.inspect(application);
        assertTrue(afterGrant.dndPolicyAccess);
        assertTrue(afterGrant.actualChannels.ready());
        assertTrue(afterGrant.actualChannels.bypassDnd);
        assertTrue(afterGrant.predictiveChannels.ready());
        assertTrue(afterGrant.predictiveChannels.bypassDnd);
        assertTrue(afterGrant.signalLossChannels.ready());
        assertTrue(afterGrant.signalLossChannels.bypassDnd);
        for (int index = 0; index < types.length; index++) {
            NotificationChannel standard = notifications
                    .getNotificationChannel(standardIds[index]);
            NotificationChannel bypass = notifications
                    .getNotificationChannel(bypassIds[index]);
            assertNotNull(standardIds[index], standard);
            assertFalse(standard.canBypassDnd());
            assertNotNull(bypassIds[index], bypass);
            assertTrue(bypass.canBypassDnd());
            assertEquals(standard.getSound(), bypass.getSound());
            assertEquals(AudioAttributes.USAGE_ALARM,
                    bypass.getAudioAttributes().getUsage());
        }
    }

    @Test
    public void criticalEpisodeRaisesSilentAlarmVolumeAndAckRestoresIt() {
        AudioManager audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
            assertTrue(CriticalGlucoseAlarm.showTest(application, true));
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0);

            assertTrue(CriticalGlucoseAlarm.acknowledge(application, token()));
            assertEquals(0,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
        } finally {
            CriticalGlucoseAlarm.acknowledge(application, token());
            CriticalGlucoseAlarm.resolveActual(application);
            CriticalGlucoseAlarm.cancelPredictive(application, true);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        }
    }

    @Test
    public void oldAckVolumeRetriesNeverClobberNewCriticalFloor() {
        AudioManager audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            assertTrue(CriticalGlucoseAlarm.showTest(application,
                    CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
            String endedToken = token();
            assertTrue(CriticalGlucoseAlarm.acknowledge(application,
                    endedToken));
            assertEquals(1,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));

            assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(
                    application,
                    CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH, 100));
            assertTrue(CriticalGlucoseAlarm.showTest(application,
                    CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
            String newerToken = token();
            assertNotEquals(endedToken, newerToken);
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            assertEquals(maximum,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));

            shadowOf(Looper.getMainLooper()).idleFor(35L, TimeUnit.SECONDS);

            assertNotNull(CriticalGlucoseAlarm.session(application, newerToken));
            assertEquals(maximum,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
        } finally {
            String live = token();
            if (!live.isEmpty()) {
                CriticalGlucoseAlarm.acknowledge(application, live);
            }
            shadowOf(Looper.getMainLooper()).idleFor(35L, TimeUnit.SECONDS);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        }
    }

    @Test
    public void ackVolumeRestoreStopsOwningExactFloorAfterFiveSeconds() {
        AudioManager audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            assertTrue(CriticalGlucoseAlarm.showTest(application,
                    CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
            int oldFloor = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            assertTrue(oldFloor > 1);

            assertTrue(CriticalGlucoseAlarm.acknowledge(application, token()));
            assertEquals(1,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
            shadowOf(Looper.getMainLooper()).idleFor(6L, TimeUnit.SECONDS);

            // Once the bounded OEM workaround has finished, an exact-floor
            // value is user-owned too. Later notification-only cleanup must
            // not mistake it for a stale channel-volume reassertion.
            audio.setStreamVolume(AudioManager.STREAM_ALARM, oldFloor, 0);
            shadowOf(Looper.getMainLooper()).idleFor(30L, TimeUnit.SECONDS);

            assertEquals(oldFloor,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
            assertTrue(application.getSharedPreferences(
                    CriticalAlarmAudioReliability.PREFS_NAME,
                    Context.MODE_PRIVATE).getAll().isEmpty());
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        }
    }

    @Test
    public void shortSnoozeResumeSameTokenSurvivesOldCleanupCallbacks() {
        AudioManager audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            assertTrue(CriticalGlucoseAlarm.showTest(application,
                    CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
            String sameToken = token();
            assertTrue(CriticalGlucoseAlarm.snooze(application, sameToken, 1L));
            assertNull(activeNotification(8_405));
            assertEquals(1,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));

            shadowOf(Looper.getMainLooper()).idleFor(2L,
                    TimeUnit.MILLISECONDS);
            CriticalGlucoseAlarm.resume(application, sameToken);
            assertNotNull(CriticalGlucoseAlarm.session(application, sameToken));
            assertNotNull(activeNotification(8_405));
            int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM));
            assertEquals(floor,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));

            shadowOf(Looper.getMainLooper()).idleFor(35L, TimeUnit.SECONDS);

            assertNotNull(CriticalGlucoseAlarm.session(application, sameToken));
            assertNotNull(activeNotification(8_405));
            assertEquals(floor,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
        } finally {
            String live = token();
            if (!live.isEmpty()) {
                CriticalGlucoseAlarm.acknowledge(application, live);
            }
            shadowOf(Looper.getMainLooper()).idleFor(35L, TimeUnit.SECONDS);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        }
    }

    @Test
    public void acknowledgeCancelsEveryCriticalIdButKeepsOrdinaryNotification() {
        CriticalAlarmSoundCatalog.AlertType type =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        String currentToken = token();

        Notification ordinary = new Notification.Builder(application,
                selectedChannel(type))
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle("ordinary app notification")
                .build();
        notifications.notify(42, ordinary);
        for (int id : criticalNotificationIds()) {
            Notification orphan = new Notification.Builder(application,
                    selectedChannel(type))
                    .setSmallIcon(R.drawable.novalue)
                    .setContentTitle("critical orphan " + id)
                    .build();
            orphan.flags |= Notification.FLAG_INSISTENT;
            notifications.notify(id, orphan);
        }

        assertTrue(CriticalGlucoseAlarm.acknowledge(application, currentToken));

        for (int id : criticalNotificationIds()) {
            assertNull("critical notification survived: " + id,
                    activeNotification(id));
        }
        assertNotNull("ordinary notification must be left alone",
                activeNotification(42));
    }

    @Test
    public void oemCleanupCopyCannotInsistRelaunchOrExposeActions() {
        PendingIntent action = PendingIntent.getBroadcast(application, 91_001,
                new Intent(application, CriticalGlucoseAlarmReceiver.class)
                        .setAction(CriticalGlucoseAlarmReceiver.ACTION_ACK),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification original = new Notification.Builder(application,
                selectedChannel(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW))
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle("critical")
                .setContentIntent(action)
                .setDeleteIntent(action)
                .setFullScreenIntent(action, true)
                .addAction(0, "Stop", action)
                .build();
        original.flags |= Notification.FLAG_INSISTENT
                | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_ONGOING_EVENT;
        original.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
        original.sound = android.net.Uri.parse("content://test/alarm");
        original.vibrate = new long[]{0L, 1_000L};
        original.defaults = Notification.DEFAULT_ALL;

        Notification neutral = CriticalGlucoseAlarm
                .neutralizedNotification(original);

        assertNotNull(neutral);
        assertNotEquals(original, neutral);
        assertEquals(0, neutral.flags & Notification.FLAG_INSISTENT);
        assertEquals(0, neutral.flags & Notification.FLAG_NO_CLEAR);
        assertEquals(0, neutral.flags & Notification.FLAG_ONGOING_EVENT);
        assertNotEquals(0,
                neutral.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        assertEquals(0, neutral.defaults);
        assertNull(neutral.sound);
        assertNull(neutral.vibrate);
        assertNull(neutral.fullScreenIntent);
        assertNull(neutral.contentIntent);
        assertNull(neutral.deleteIntent);
        assertNull(neutral.actions);
        // Clone-before-neutralize is important: SystemUI still owns the
        // original object until the exact tag/id replacement is submitted.
        assertNotNull(original.fullScreenIntent);
        assertNotNull(original.actions);
        assertNotEquals(0, original.flags & Notification.FLAG_INSISTENT);
    }

    @Test
    public void delayedAckCleanupRemovesLateOemPost() {
        CriticalAlarmSoundCatalog.AlertType type =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        String currentToken = token();
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, currentToken));

        // Model a vendor delivery that survives/reappears beyond the short
        // post-Activity retries observed on the physical Fold device.
        shadowOf(Looper.getMainLooper()).idleFor(31L, TimeUnit.SECONDS);
        Notification late = new Notification.Builder(application,
                selectedChannel(type))
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle("late INSISTENT delivery")
                .build();
        late.flags |= Notification.FLAG_INSISTENT;
        notifications.notify(8_405, late);
        assertNotNull(activeNotification(8_405));

        shadowOf(Looper.getMainLooper()).idleFor(30L, TimeUnit.SECONDS);

        assertNull(activeNotification(8_405));
    }

    @Test
    public void delayedAckCleanupNeverCancelsNewerCriticalEpisode() {
        assertTrue(CriticalGlucoseAlarm.showTest(application,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        String endedToken = token();
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, endedToken));

        assertTrue(CriticalGlucoseAlarm.showTest(application,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
        String newerToken = token();
        assertNotEquals(endedToken, newerToken);
        shadowOf(Looper.getMainLooper()).idleFor(65L, TimeUnit.SECONDS);

        assertNotNull(CriticalGlucoseAlarm.session(application, newerToken));
        assertNotNull(activeNotification(8_405));
    }

    @Test
    public void delayedAckCleanupKeepsNewIdAndCancelsStaleOtherId() {
        assertTrue(CriticalGlucoseAlarm.showTest(application,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        String endedToken = token();
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, endedToken));

        assertTrue(CriticalGlucoseAlarm.showActual(application, 1, 215f,
                "new measured high", true));
        CriticalGlucoseAlarm.Session newer =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(newer);
        assertNotEquals(endedToken, newer.token);
        assertNotNull(activeNotification(8_402));

        Notification stale = new Notification.Builder(application,
                selectedChannel(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW))
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle("stale test delivery")
                .build();
        stale.flags |= Notification.FLAG_INSISTENT
                | Notification.FLAG_NO_CLEAR;
        notifications.notify(8_405, stale);
        assertNotNull(activeNotification(8_405));

        shadowOf(Looper.getMainLooper()).idleFor(65L, TimeUnit.SECONDS);

        assertNotNull(CriticalGlucoseAlarm.session(application, newer.token));
        assertNotNull(activeNotification(8_402));
        assertNull(activeNotification(8_405));
    }

    @Test
    public void signalLossUsesOwnAlarmChannelVolumeAndResolvesOnReading() {
        AudioManager audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        try {
            assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(
                    application,
                    CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS, 100));
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
            long lastReading = System.currentTimeMillis() - 20L * 60_000L;
            assertTrue(CriticalGlucoseAlarm.showLossSignal(application,
                    lastReading, "No readings for 20 minutes"));

            CriticalGlucoseAlarm.Session session =
                    CriticalGlucoseAlarm.currentSession(application);
            assertNotNull(session);
            assertTrue(session.signalLoss());
            assertEquals(CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS,
                    session.alertType);
            assertEquals(100, session.minimumVolumePercent);
            assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
            StatusBarNotification notification = activeNotification(8_406);
            assertNotNull(notification);
            assertEquals(selectedChannel(
                            CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS),
                    notification.getNotification().getChannelId());
            assertEquals(1, notification.getNotification().actions.length);

            CriticalGlucoseAlarm.resolveSignalLoss(application);
            assertTrue(token().isEmpty());
            assertNull(activeNotification(8_406));
            assertEquals(0,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM));
        } finally {
            CriticalGlucoseAlarm.resolveSignalLoss(application);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        }
    }

    @Test
    public void measuredReadingPreemptsSignalLossWithoutParallelFallback() {
        long now = System.currentTimeMillis();
        assertTrue(CriticalGlucoseAlarm.showLossSignal(application,
                now - 20L * 60_000L, "Signal missing"));
        String lossToken = token();

        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                63f, "Measured low after reconnect", false));
        CriticalGlucoseAlarm.Session measured =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(measured);
        assertTrue(measured.actual());
        assertEquals(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW,
                measured.alertType);
        assertNotEquals(lossToken, measured.token);
        assertNull(activeNotification(8_406));
        assertNotNull(activeNotification(selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW)));
    }

    @Test
    public void coldStartCancelsOrphanInsistentTestNotificationWithoutPrefs()
            throws Exception {
        Notification orphan = new Notification.Builder(application,
                CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID)
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle("orphan critical test")
                .build();
        orphan.flags |= Notification.FLAG_INSISTENT;
        notifications.notify(8_405, orphan);
        assertNotNull(activeNotification(8_405));
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        assertNull(activeNotification(8_405));
        assertEquals(0, notifications.getActiveNotifications().length);
        assertTrue(token().isEmpty());
        assertNull(getStaticField("ringtone"));
        assertNull(getStaticField("delayedLoop"));
    }

    @Test
    public void coldStartExpiredSessionCancelsNotificationAndTokenAlarms()
            throws Exception {
        assertTrue(CriticalGlucoseAlarm.showTest(application, true));
        AlarmManager alarms = (AlarmManager) application.getSystemService(
                Context.ALARM_SERVICE);
        assertNotNull(alarms);
        assertFalse(shadowOf(alarms).getScheduledAlarms().isEmpty());
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().putLong("expires_at_ms",
                        System.currentTimeMillis() - 1L).commit();

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        assertEquals(0, notifications.getActiveNotifications().length);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
        assertTrue(token().isEmpty());
    }

    @Test
    public void coldStartCorruptSessionCancelsNotificationAndTokenAlarms()
            throws Exception {
        assertTrue(CriticalGlucoseAlarm.showTest(application, false));
        AlarmManager alarms = (AlarmManager) application.getSystemService(
                Context.ALARM_SERVICE);
        assertNotNull(alarms);
        assertFalse(shadowOf(alarms).getScheduledAlarms().isEmpty());
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString("expires_at_ms", "not-a-long").commit();

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        assertEquals(0, notifications.getActiveNotifications().length);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
        assertTrue(token().isEmpty());
    }

    @Test
    public void coldStartKeepsValidPersistedLiveSession() throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Valid low", "Keep me", now));
        String validToken = token();
        assertFalse(validToken.isEmpty());

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        assertEquals(validToken, token());
        assertNotNull(CriticalGlucoseAlarm.session(application, validToken));
        assertNotNull(activeNotification(selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW)));
    }

    @Test
    public void predictiveNotificationCarriesFsiAndOnlyStopSoundAction()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Act soon", now));

        StatusBarNotification active = activeNotification(selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW));
        assertNotNull(active);
        Notification notification = active.getNotification();
        assertNotNull(notification.fullScreenIntent);
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertEquals(Notification.CATEGORY_ALARM, notification.category);
        assertTrue((notification.flags & Notification.FLAG_NO_CLEAR) != 0);
        assertTrue((notification.flags & Notification.FLAG_INSISTENT) != 0);
        assertEquals(0, notification.flags
                & Notification.FLAG_ONLY_ALERT_ONCE);
        assertNotNull(notification.actions);
        assertEquals(1, notification.actions.length);
        Intent stopIntent = shadowOf(notification.actions[0].actionIntent)
                .getSavedIntent();
        assertEquals(CriticalGlucoseAlarmReceiver.ACTION_ACK,
                stopIntent.getAction());
        assertEquals(token(), stopIntent.getStringExtra(
                CriticalGlucoseAlarmReceiver.EXTRA_TOKEN));
        assertNull(getStaticField("delayedLoop"));
        assertNull(getStaticField("ringtone"));
    }

    @Test
    public void typeAwareTestKeepsActualLowChannelAcrossColdRestore()
            throws Exception {
        CriticalAlarmSoundCatalog.AlertType type =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        String currentToken = token();
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(session);
        assertEquals(type, session.alertType);
        assertEquals(CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT,
                session.minimumVolumePercent);
        assertNotNull(activeNotification(selectedChannel(type)));

        setStaticField("active", null);
        setStaticField("initialized", false);
        CriticalGlucoseAlarm.ensureChannels(application);

        CriticalGlucoseAlarm.Session restored =
                CriticalGlucoseAlarm.session(application, currentToken);
        assertNotNull(restored);
        assertEquals(type, restored.alertType);
        assertNotNull(activeNotification(selectedChannel(type)));
    }

    @Test
    public void staleTokenCannotAcknowledgeReplacementSession() {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Low", "First", now));
        String oldToken = token();
        assertFalse(oldToken.isEmpty());

        assertTrue(showPrediction(false, "High", "Replacement", now + 1));
        String replacementToken = token();
        assertNotEquals(oldToken, replacementToken);
        assertFalse(CriticalGlucoseAlarm.acknowledge(application, oldToken));
        assertNull(CriticalGlucoseAlarm.session(application, oldToken));
        assertNotNull(CriticalGlucoseAlarm.session(
                application, replacementToken));
    }

    @Test
    public void requestCodeCollisionKeepsPendingIntentTokenIdentityDistinct()
            throws Exception {
        String[] tokens = collidingTokens(1);
        CriticalGlucoseAlarm.Session stale = validPredictiveSession(tokens[0]);
        CriticalGlucoseAlarm.Session current = validPredictiveSession(tokens[1]);
        Method requestCode = CriticalGlucoseAlarm.class.getDeclaredMethod(
                "requestCode", CriticalGlucoseAlarm.Session.class, int.class);
        requestCode.setAccessible(true);
        assertEquals(requestCode.invoke(null, stale, 1),
                requestCode.invoke(null, current, 1));

        Method receiverIntent = CriticalGlucoseAlarm.class.getDeclaredMethod(
                "receiverIntent", Context.class,
                CriticalGlucoseAlarm.Session.class, String.class, int.class);
        receiverIntent.setAccessible(true);
        PendingIntent staleAck = (PendingIntent) receiverIntent.invoke(null,
                application, stale, CriticalGlucoseAlarmReceiver.ACTION_ACK, 1);
        PendingIntent currentAck = (PendingIntent) receiverIntent.invoke(null,
                application, current, CriticalGlucoseAlarmReceiver.ACTION_ACK, 1);
        try {
            assertNotEquals(staleAck, currentAck);
            Intent staleIntent = shadowOf(staleAck).getSavedIntent();
            Intent currentIntent = shadowOf(currentAck).getSavedIntent();
            assertNotNull(staleIntent.getData());
            assertNotNull(currentIntent.getData());
            assertTrue(staleIntent.getData().isOpaque());
            assertNotEquals(staleIntent.getData(), currentIntent.getData());
            assertEquals(tokens[0], staleIntent.getStringExtra(
                    CriticalGlucoseAlarmReceiver.EXTRA_TOKEN));
            assertEquals(tokens[1], currentIntent.getStringExtra(
                    CriticalGlucoseAlarmReceiver.EXTRA_TOKEN));

            persistSession(current);
            new CriticalGlucoseAlarmReceiver().onReceive(application,
                    staleIntent);
            assertNotNull(CriticalGlucoseAlarm.session(application, tokens[1]));
            assertNull(CriticalGlucoseAlarm.session(application, tokens[0]));
        } finally {
            staleAck.cancel();
            currentAck.cancel();
        }
    }

    @Test
    public void lowerPriorityCannotDowngradeSevereActualSession() {
        assertEquals(CriticalAlarmEpisodePolicy.Transition.KEEP_HIGHER_PRIORITY,
                CriticalAlarmEpisodePolicy.transition(true,
                        CriticalGlucoseAlarm.DIRECTION_LOW,
                        CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                        CriticalGlucoseAlarm.DIRECTION_LOW,
                        CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL));
        assertEquals(CriticalAlarmEpisodePolicy.Transition.KEEP_HIGHER_PRIORITY,
                CriticalAlarmEpisodePolicy.transition(true,
                        CriticalGlucoseAlarm.DIRECTION_LOW,
                        CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                        CriticalGlucoseAlarm.DIRECTION_LOW,
                        CriticalAlarmEpisodePolicy.PRIORITY_PREDICTIVE_LIKELY));

        assertTrue(CriticalGlucoseAlarm.showActual(application, 5,
                48f, "Severe actual low", true));
        String actualToken = token();
        CriticalGlucoseAlarm.Session before =
                CriticalGlucoseAlarm.session(application, actualToken);
        assertNotNull(before);
        assertEquals(CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                before.priority);

        assertFalse(showPrediction(true, "Predicted low",
                "Must not replace actual", System.currentTimeMillis()));
        assertEquals(actualToken, token());
        CriticalGlucoseAlarm.Session after =
                CriticalGlucoseAlarm.session(application, actualToken);
        assertNotNull(after);
        assertEquals("Severe actual low", after.body);
        assertEquals(CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                after.priority);
    }

    @Test
    public void repeatedEqualUpdateKeepsTokenAndNoParallelAppLoop()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "First body", now));
        String firstToken = token();
        assertNull(getStaticField("delayedLoop"));
        assertNull(getStaticField("ringtone"));

        assertTrue(showPrediction(true, "Likely low updated",
                "Updated body", now + 2_000L));
        assertEquals(firstToken, token());
        assertNull(getStaticField("delayedLoop"));
        assertNull(getStaticField("ringtone"));
        CriticalGlucoseAlarm.Session updated =
                CriticalGlucoseAlarm.session(application, firstToken);
        assertNotNull(updated);
        assertEquals("Updated body", updated.body);
    }

    @Test
    public void acknowledgeRejectsStaleTokenAndActualRecoveryClearsSession() {
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                63f, "Actual low", true));
        String current = token();
        assertFalse(CriticalGlucoseAlarm.acknowledge(application,
                "stale-" + current));
        assertEquals(current, token());

        assertTrue(CriticalGlucoseAlarm.acknowledge(application, current));
        assertTrue(token().isEmpty());

        assertTrue(CriticalGlucoseAlarm.showActual(application, 1,
                230f, "Actual high", true));
        CriticalGlucoseAlarm.resolveActual(application);
        assertTrue(token().isEmpty());
        assertNull(activeNotification(selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH)));
    }

    @Test
    public void snoozeKeepsValidatedTokenAndUpdatesPredictivePreference() {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Snooze me", now));
        String current = token();
        assertTrue(CriticalGlucoseAlarm.snooze(application, current,
                5L * 60_000L));

        CriticalGlucoseAlarm.Session snoozed =
                CriticalGlucoseAlarm.session(application, current);
        assertNotNull(snoozed);
        assertTrue(snoozed.snoozeUntilMs > now);
        assertFalse(CriticalGlucoseAlarm.predictiveActive(
                application, true, System.currentTimeMillis()));
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(application);
        assertTrue(preferences.snoozeBlocks(
                PredictiveAlertPreferences.DIRECTION_LOW,
                System.currentTimeMillis()));
        assertNull(activeNotification(selectedChannel(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW)));
    }

    @Test
    public void predictiveActiveProofAndDirectionScopedCancelAreCoherent() {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Active", now));
        assertTrue(CriticalGlucoseAlarm.predictiveActive(
                application, true, now + 1));
        assertFalse(CriticalGlucoseAlarm.predictiveActive(
                application, false, now + 1));

        CriticalGlucoseAlarm.cancelPredictive(application, false);
        assertTrue(CriticalGlucoseAlarm.predictiveActive(
                application, true, now + 2));
        CriticalGlucoseAlarm.cancelPredictive(application, true);
        assertFalse(CriticalGlucoseAlarm.predictiveActive(
                application, true, now + 3));
        assertTrue(token().isEmpty());
    }

    @Test
    public void nonTriggeredActualReplacesPredictiveAndTestButNotEmptyAckState() {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        String predictiveToken = token();
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                66f, "Measured low", false));
        String actualToken = token();
        assertNotEquals(predictiveToken, actualToken);
        CriticalGlucoseAlarm.Session actual =
                CriticalGlucoseAlarm.session(application, actualToken);
        assertNotNull(actual);
        assertTrue(actual.actual());

        CriticalGlucoseAlarm.resolveActual(application);
        assertTrue(CriticalGlucoseAlarm.showTest(application, true));
        String testToken = token();
        assertTrue(CriticalGlucoseAlarm.showActual(application, 1,
                205f, "Measured high", false));
        assertNotEquals(testToken, token());
        assertTrue(CriticalGlucoseAlarm.session(application, token()).actual());

        CriticalGlucoseAlarm.resolveActual(application);
        assertFalse(CriticalGlucoseAlarm.showActual(application, 0,
                67f, "Ordinary sample after ACK/recovery", false));
        assertTrue(token().isEmpty());
    }

    @Test
    public void oppositeActualDirectionIsNewEventEvenAtLowerPriority() {
        assertTrue(CriticalGlucoseAlarm.showActual(application, 5,
                48f, "Severe actual low", true));
        String severeLowToken = token();

        assertTrue(CriticalGlucoseAlarm.showActual(application, 1,
                210f, "Measured high reversal", false));
        String highToken = token();
        assertNotEquals(severeLowToken, highToken);
        CriticalGlucoseAlarm.Session high =
                CriticalGlucoseAlarm.session(application, highToken);
        assertNotNull(high);
        assertTrue(high.actual());
        assertEquals(CriticalGlucoseAlarm.DIRECTION_HIGH, high.direction);
        assertEquals(CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL,
                high.priority);

        assertFalse(showPrediction(true, "Predicted low",
                "Prediction cannot displace measured high",
                System.currentTimeMillis()));
        assertEquals(highToken, token());
    }

    @Test
    public void blockedDeliveryReturnsLegacyControlAndNeverStartsAppLoop()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        shadowOf(notifications).setNotificationsEnabled(false);
        try {
            assertFalse(CriticalGlucoseAlarm.showActual(application, 0,
                    61f, "Measured low", false));
            assertTrue(token().isEmpty());
            assertNull(getStaticField("delayedLoop"));
            assertNull(getStaticField("ringtone"));

            assertFalse(CriticalGlucoseAlarm.showActual(application, 1,
                    220f, "Fresh blocked actual", true));
            assertTrue(token().isEmpty());
            assertNull(getStaticField("delayedLoop"));
            assertNull(getStaticField("ringtone"));
        } finally {
            shadowOf(notifications).setNotificationsEnabled(true);
        }
    }

    @Test
    public void visibleActivityAdoptsReplacementTokenAndClosesOnResolution()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        String initialToken = token();
        Intent intent = new Intent(application,
                CriticalGlucoseAlarmActivity.class)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN,
                        initialToken);
        ActivityController<CriticalGlucoseAlarmActivity> controller =
                Robolectric.buildActivity(
                        CriticalGlucoseAlarmActivity.class, intent)
                        .create().resume();
        CriticalGlucoseAlarmActivity activity = controller.get();
        try {
            assertTrue(CriticalGlucoseAlarm.showActual(application, 1,
                    210f, "Measured high", false));
            shadowOf(Looper.getMainLooper()).idle();
            String replacementToken = token();
            assertNotEquals(initialToken, replacementToken);
            assertEquals(replacementToken,
                    instanceField(activity, "token"));
            assertFalse(activity.isFinishing());

            CriticalGlucoseAlarm.resolveActual(application);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.isFinishing());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void workerReplacementPublishesLatestTokenThroughMainLooper()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        String initialToken = token();
        Intent intent = new Intent(application,
                CriticalGlucoseAlarmActivity.class)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN,
                        initialToken);
        ActivityController<CriticalGlucoseAlarmActivity> controller =
                Robolectric.buildActivity(
                        CriticalGlucoseAlarmActivity.class, intent)
                        .create().resume();
        CriticalGlucoseAlarmActivity activity = controller.get();
        AtomicBoolean delivered = new AtomicBoolean(false);
        try {
            Thread worker = new Thread(() -> delivered.set(
                    CriticalGlucoseAlarm.showActual(application, 1,
                            215f, "Worker measured high", false)));
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive());
            assertTrue(delivered.get());
            String replacementToken = token();
            assertNotEquals(initialToken, replacementToken);
            assertEquals(initialToken, instanceField(activity, "token"));

            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(replacementToken, instanceField(activity, "token"));
            assertTrue(CriticalGlucoseAlarm.acknowledge(
                    application, replacementToken));
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.isFinishing());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void visibleActivityClosesWhenNotificationActionSnoozesSession()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        String current = token();
        Intent intent = new Intent(application,
                CriticalGlucoseAlarmActivity.class)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN, current);
        ActivityController<CriticalGlucoseAlarmActivity> controller =
                Robolectric.buildActivity(
                        CriticalGlucoseAlarmActivity.class, intent)
                        .create().resume();
        try {
            assertTrue(CriticalGlucoseAlarm.snooze(application, current,
                    5L * 60_000L));
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(controller.get().isFinishing());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    // Robolectric 4.16 no longer ships an API 21 android-all sandbox; API 23
    // is its oldest supported runtime. The source assertions below pin the
    // same guarded branch all the way down to the application's API 21 min.
    @Config(sdk = 23, application = Application.class)
    public void preOreoRuntimePostsInsistentAlarmAndUsesLegacyWindowFlags()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "API 21", now));
        assertFalse(token().isEmpty());
        assertNull(getStaticField("delayedLoop"));
        assertNull(getStaticField("ringtone"));

        Intent intent = new Intent(application,
                CriticalGlucoseAlarmActivity.class)
                .putExtra(CriticalGlucoseAlarmReceiver.EXTRA_TOKEN, token());
        ActivityController<CriticalGlucoseAlarmActivity> controller =
                Robolectric.buildActivity(
                        CriticalGlucoseAlarmActivity.class, intent).create();
        try {
            int flags = controller.get().getWindow().getAttributes().flags;
            assertTrue((flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    != 0);
            assertTrue((flags & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    != 0);
            assertTrue((flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    != 0);
        } finally {
            controller.destroy();
        }
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, token()));
    }

    @Test
    public void onlyLikelyEvidenceUsesCriticalPredictiveApi() throws Exception {
        ForecastRiskEvaluator.Decision likely = decision("likely");
        ForecastRiskEvaluator.Decision possible = decision("possible");
        assertTrue(PredictiveAlertNotifier.usesCriticalDelivery(likely));
        assertFalse(PredictiveAlertNotifier.usesCriticalDelivery(possible));

        String notifier = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertNotifier.java"));
        assertTrue(notifier.contains("if (usesCriticalDelivery(decision))"));
        assertTrue(notifier.contains("CriticalGlucoseAlarm.showPredictive"));
    }

    @Test
    public void criticalOwnershipStopsLegacyOnlyAfterSuccessfulShow()
            throws Exception {
        String notify = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "Notify.java"));
        int actualStart = notify.indexOf(
                "boolean handled=CriticalGlucoseAlarm.showActual(");
        int actualEnd = notify.indexOf("if(alarm) {", actualStart);
        assertTrue(actualStart >= 0);
        assertTrue(actualEnd > actualStart);
        String actualBranch = notify.substring(actualStart, actualEnd);
        int handled = actualBranch.indexOf("if(handled) {");
        int stop = actualBranch.indexOf("Notify.stopGlucoseAlarm();");
        int cancel = actualBranch.indexOf(
                "PredictiveAlertNotifier.cancelAll(Applic.app);");
        int returned = actualBranch.indexOf("return;");
        assertTrue(handled >= 0);
        assertTrue(stop > handled);
        assertTrue(cancel > stop);
        assertTrue(returned > cancel);

        String notifier = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertNotifier.java"));
        int likelyStart = notifier.indexOf(
                "if (usesCriticalDelivery(decision)) {");
        int possibleStart = notifier.indexOf(
                "Notification notification =", likelyStart);
        assertTrue(likelyStart >= 0);
        assertTrue(possibleStart > likelyStart);
        String likelyBranch = notifier.substring(likelyStart, possibleStart);
        int show = likelyBranch.indexOf(
                "CriticalGlucoseAlarm.showPredictive(");
        int shown = likelyBranch.indexOf("if (shown) {");
        int safeStop = likelyBranch.indexOf(
                "stopLegacyGlucoseAlarmAfterOwnership(");
        int likelyReturn = likelyBranch.indexOf("return shown;");
        assertTrue(show >= 0);
        assertTrue(shown > show);
        assertTrue(safeStop > shown);
        assertTrue(likelyReturn > safeStop);

        String possibleBranch = notifier.substring(possibleStart,
                notifier.indexOf("static boolean usesCriticalDelivery(",
                        possibleStart));
        assertFalse(possibleBranch.contains(
                "stopLegacyGlucoseAlarmAfterOwnership("));
        assertFalse(possibleBranch.contains("Notify.stopGlucoseAlarm()"));

        assertTrue(notify.contains(
                "CriticalGlucoseAlarm.showLossSignal("));
        assertTrue(notify.contains(
                "CriticalGlucoseAlarm.resolveSignalLoss(Applic.app)"));
    }

    @Test
    public void likelyHasOneAudioOwnerAndAckEndsCriticalOwnership() {
        long now = System.currentTimeMillis();
        assertTrue(showPrediction(true, "Likely low", "Prediction", now));
        String ownedToken = token();
        assertFalse(ownedToken.isEmpty());

        AtomicBoolean legacyAudioActive = new AtomicBoolean(true);
        assertTrue(PredictiveAlertNotifier
                .stopLegacyGlucoseAlarmAfterOwnership(
                        () -> legacyAudioActive.set(false)));
        assertFalse(legacyAudioActive.get());
        assertNotNull(CriticalGlucoseAlarm.session(application, ownedToken));

        assertTrue(CriticalGlucoseAlarm.acknowledge(application, ownedToken));
        assertTrue(token().isEmpty());
        assertNull(CriticalGlucoseAlarm.session(application, ownedToken));
    }

    @Test
    public void legacyStopStaticInitializationFailureCannotEscape() {
        assertFalse(PredictiveAlertNotifier
                .stopLegacyGlucoseAlarmAfterOwnership(() -> {
                    throw new ExceptionInInitializerError("test harness");
                }));
    }

    @Test
    public void manifestAndSourceKeepCriticalSurfacesPrivateAndNeverMutateDndOrVolume()
            throws Exception {
        String manifest = source(Paths.get("src", "mobile",
                "AndroidManifest.xml"));
        String mainManifest = source(Paths.get("src", "main",
                "AndroidManifest.xml"));
        assertTrue(manifest.contains(
                "android.permission.USE_FULL_SCREEN_INTENT"));
        assertTrue(manifest.contains(
                "android:name=\".CriticalGlucoseAlarmActivity\""));
        assertTrue(manifest.contains(
                "android:name=\".CriticalGlucoseAlarmReceiver\""));
        assertTrue(manifest.contains("android:showWhenLocked=\"true\""));
        assertTrue(manifest.contains("android:turnScreenOn=\"true\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertTrue(mainManifest.contains(
                "android.permission.SYSTEM_ALERT_WINDOW"));
        assertTrue(mainManifest.contains(
                "android.permission.MODIFY_AUDIO_SETTINGS"));

        String controller = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarm.java"));
        String activity = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarmActivity.java"));
        String receiver = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarmReceiver.java"));
        String notify = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "Notify.java"));
        String overlay = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarmOverlay.java"));

        assertTrue(controller.contains(".setFullScreenIntent(fullScreen, true)"));
        assertTrue(controller.contains("NotificationCompat.VISIBILITY_PRIVATE"));
        assertTrue(controller.contains("setBypassDnd(bypassDnd)"));
        assertTrue(controller.contains("isNotificationPolicyAccessGranted"));
        assertTrue(controller.contains("USAGE_ALARM"));
        assertTrue(controller.contains(
                "Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"));
        assertTrue(controller.contains(
                "Build.VERSION.SDK_INT < Build.VERSION_CODES.P"));
        assertTrue(controller.contains(
                "MAIN.postDelayed(delayedLoop"));
        assertTrue(controller.contains(
                "Build.VERSION.SDK_INT < Build.VERSION_CODES.O"));
        assertTrue(controller.contains("AudioManager.STREAM_ALARM"));
        assertTrue(controller.contains("Notification.FLAG_INSISTENT"));
        assertTrue(controller.contains(
                "notification.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE"));
        assertFalse(controller.contains(".setOnlyAlertOnce("));
        assertFalse(controller.contains(
                "getSystemService(NotificationManager.class)"));
        assertFalse(controller.contains(
                "getSystemService(AudioManager.class)"));
        assertTrue(activity.contains(
                "Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1"));
        assertTrue(activity.contains(
                "WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED"));
        assertTrue(activity.contains("setShowWhenLocked(true)"));
        assertTrue(activity.contains("setTurnScreenOn(true)"));
        assertTrue(receiver.contains("ACTION_ACK"));
        assertTrue(receiver.contains("ACTION_SNOOZE"));
        assertTrue(overlay.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(overlay.contains("CriticalGlucoseAlarm.session(app, token)"));
        assertTrue(notify.contains("CriticalGlucoseAlarm.resolveActual"));
        assertTrue(controller.contains(
                "if (!trigger && current == null) return false"));

        String safetySurface = controller + activity + receiver + notify
                + overlay;
        assertFalse(safetySurface.contains("setInterruptionFilter"));
        String audioReliability = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalAlarmAudioReliability.java"));
        assertTrue(audioReliability.contains("setStreamVolume"));
        assertTrue(audioReliability.contains("STREAM_ALARM"));
        assertTrue(audioReliability.contains("restoreAlarmVolume"));
        assertFalse(audioReliability.contains("setRingerMode"));
        assertFalse(safetySurface.contains("adjustStreamVolume"));
    }

    private boolean showPrediction(boolean low, String title, String body,
            long anchorMs) {
        return CriticalGlucoseAlarm.showPredictive(application, low,
                title, body, low ? "72 mg/dL" : "170 mg/dL", anchorMs,
                System.currentTimeMillis() + 15L * 60_000L,
                45L * 60_000L);
    }

    private String token() {
        return application.getSharedPreferences(
                CRITICAL_PREFS, Context.MODE_PRIVATE)
                .getString("token", "");
    }

    private Object getStaticField(String name) throws Exception {
        Field field = CriticalGlucoseAlarm.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void setStaticField(String name, Object value) throws Exception {
        Field field = CriticalGlucoseAlarm.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void persistSession(CriticalGlucoseAlarm.Session session)
            throws Exception {
        Method save = CriticalGlucoseAlarm.class.getDeclaredMethod(
                "save", Context.class, CriticalGlucoseAlarm.Session.class);
        save.setAccessible(true);
        save.invoke(null, application, session);
        setStaticField("active", session);
    }

    private CriticalGlucoseAlarm.Session validPredictiveSession(String token) {
        CriticalGlucoseAlarm.Session session =
                new CriticalGlucoseAlarm.Session();
        session.token = token;
        session.source = CriticalGlucoseAlarm.SOURCE_PREDICTIVE;
        session.direction = CriticalGlucoseAlarm.DIRECTION_LOW;
        session.priority =
                CriticalAlarmEpisodePolicy.PRIORITY_PREDICTIVE_LIKELY;
        session.title = "Likely low";
        session.body = "Act soon";
        session.value = "72 mg/dL";
        session.anchorMs = System.currentTimeMillis();
        session.expiresAtMs = session.anchorMs + 30L * 60_000L;
        session.soundRes = R.raw.lowsoon;
        session.alertType =
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        session.minimumVolumePercent =
                CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT;
        return session;
    }

    private String[] collidingTokens(int offset) {
        Map<Integer, String> byRequestCode = new HashMap<>();
        for (int index = 0; index <= 10_000; index++) {
            String token = "forced-collision-token-" + index;
            int requestCode = 50_000 + Math.abs(
                    (token + ':' + offset).hashCode() % 10_000);
            String previous = byRequestCode.putIfAbsent(requestCode, token);
            if (previous != null && !previous.equals(token)) {
                return new String[]{previous, token};
            }
        }
        throw new AssertionError("Pigeonhole request-code collision missing");
    }

    private Object instanceField(Object instance, String name)
            throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private StatusBarNotification activeNotification(String channelId) {
        for (StatusBarNotification item : notifications.getActiveNotifications()) {
            if (item != null && channelId.equals(
                    item.getNotification().getChannelId())) return item;
        }
        return null;
    }

    private String selectedChannel(
            CriticalAlarmSoundCatalog.AlertType alertType) {
        return CriticalGlucoseAlarm.selectedChannelId(application, alertType);
    }

    private StatusBarNotification activeNotification(int id) {
        for (StatusBarNotification item : notifications.getActiveNotifications()) {
            if (item != null && item.getId() == id) return item;
        }
        return null;
    }

    private ForecastRiskEvaluator.Decision decision(String evidence)
            throws Exception {
        Constructor<ForecastRiskEvaluator.Decision> constructor =
                ForecastRiskEvaluator.Decision.class.getDeclaredConstructor(
                        ForecastRiskEvaluator.Direction.class, String.class,
                        long.class, long.class, int.class, float.class,
                        float.class, float.class, float.class, String.class);
        constructor.setAccessible(true);
        long now = System.currentTimeMillis();
        return constructor.newInstance(ForecastRiskEvaluator.Direction.LOW,
                evidence, now, now + 15L * 60_000L, 15,
                110f, 72f, 68f, 75.6f, "");
    }

    private void clearRuntimeSession() {
        CriticalGlucoseAlarm.cancelPredictive(application, true);
        CriticalGlucoseAlarm.cancelPredictive(application, false);
        CriticalGlucoseAlarm.resolveActual(application);
        CriticalGlucoseAlarm.resolveSignalLoss(application);
        application.getSharedPreferences(CRITICAL_PREFS,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void deleteCriticalChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        for (String id : criticalChannelIds()) {
            notifications.deleteNotificationChannel(id);
        }
    }

    private static String[] criticalChannelIds() {
        return new String[]{
                CriticalAlarmDiagnostics.ACTUAL_LOW_CHANNEL_ID,
                CriticalAlarmDiagnostics.ACTUAL_HIGH_CHANNEL_ID,
                CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID,
                CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID,
                CriticalAlarmDiagnostics.SIGNAL_LOSS_CHANNEL_ID
        };
    }

    private static int[] criticalNotificationIds() {
        return new int[]{8_401, 8_402, 8_403, 8_404, 8_405, 8_406};
    }

    private String[] selectedCriticalChannelIds() {
        String[] actual = CriticalGlucoseAlarm.selectedChannelIds(
                application, true);
        String[] predictive = CriticalGlucoseAlarm.selectedChannelIds(
                application, false);
        String[] signalLoss = CriticalGlucoseAlarm
                .selectedSignalLossChannelIds(application);
        return new String[]{actual[0], actual[1], predictive[0],
                predictive[1], signalLoss[0]};
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) {
            relative = Paths.get("Common").resolve(relative);
        }
        return new String(Files.readAllBytes(relative),
                StandardCharsets.UTF_8);
    }
}
