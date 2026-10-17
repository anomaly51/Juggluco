package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Exercises persistent audible delivery, not forecast eligibility. The direct
 * predictive API here represents an already-approved likely decision; model
 * approval and interval-only rejection remain covered by the engine tests.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CriticalAlarmAudioWatchdogTest {
    private static final String SESSION_PREFS = "critical_glucose_alarm_v1";
    private static final long WATCHDOG_TEST_SECONDS = 35L;

    private Application application;
    private NotificationManager notifications;
    private AudioManager audio;
    private int originalVolume;
    private int originalRingerMode;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        CriticalAlarmTestMedia.resetAndRegister(application);
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        audio = (AudioManager) application.getSystemService(Context.AUDIO_SERVICE);
        assertNotNull(notifications);
        assertNotNull(audio);
        shadowOf(notifications).setNotificationsEnabled(true);
        shadowOf(notifications).setNotificationPolicyAccessGranted(true);
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        originalRingerMode = audio.getRingerMode();
        endCurrentSession();
        clearPreferences(SESSION_PREFS);
        clearPreferences(CriticalAlarmSoundCatalog.PREFS_NAME);
        clearPreferences(CriticalAlertPreferences.PREFS_NAME);
        clearPreferences(CriticalAlarmAudioReliability.PREFS_NAME);
        clearPreferences(PredictiveAlertPreferences.PREFS_NAME);
        notifications.cancelAll();
        CriticalGlucoseAlarm.ensureChannels(application);
    }

    @After
    public void tearDown() {
        shadowOf(notifications).setNotificationsEnabled(true);
        shadowOf(notifications).setNotificationPolicyAccessGranted(true);
        endCurrentSession();
        // Let bounded ACK cleanup finish before resetting the test stream.
        shadowOf(Looper.getMainLooper()).idleFor(65L, TimeUnit.SECONDS);
        notifications.cancelAll();
        clearPreferences(SESSION_PREFS);
        clearPreferences(CriticalAlarmSoundCatalog.PREFS_NAME);
        clearPreferences(CriticalAlertPreferences.PREFS_NAME);
        clearPreferences(CriticalAlarmAudioReliability.PREFS_NAME);
        clearPreferences(PredictiveAlertPreferences.PREFS_NAME);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
        audio.setRingerMode(originalRingerMode);
        CriticalAlarmTestMedia.reset();
    }

    @Test
    public void actualLowRecoversSilentAlarmStreamDuringActiveEpisode()
            throws Exception {
        assertActiveFloorRecovers(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
    }

    @Test
    public void actualHighRecoversSilentAlarmStreamDuringActiveEpisode()
            throws Exception {
        assertActiveFloorRecovers(CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH);
    }

    @Test
    public void approvedPredictiveLowRecoversSilentAlarmStreamDuringEpisode()
            throws Exception {
        assertActiveFloorRecovers(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW);
    }

    @Test
    public void approvedPredictiveHighRecoversSilentAlarmStreamDuringEpisode()
            throws Exception {
        assertActiveFloorRecovers(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH);
    }

    @Test
    public void watchdogKeepsVisualNotificationAndAppPlayerRunning()
            throws Exception {
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        String token = currentToken();
        Notification first = activeNotification(8_401).getNotification();
        assertNotNull(first.fullScreenIntent);
        String channel = first.getChannelId();

        idleWatchdog();

        Notification repeated = activeNotification(8_401).getNotification();
        // The watchdog repairs the app-owned player; it does not repost or
        // relaunch the already-visible full-screen notification.
        assertNotNull(repeated.fullScreenIntent);
        assertEquals(channel, repeated.getChannelId());
        assertEquals(token, currentToken());
        assertEquals(0, repeated.flags & Notification.FLAG_INSISTENT);
        assertTrue((repeated.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertOnlyCriticalNotification(8_401);
        assertSingleAppAudio();
    }

    @Test
    public void supersededSameTokenWatchdogCannotReassertItsOldNotification()
            throws Exception {
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0, 61f,
                "First reading", true));
        String token = currentToken();
        Runnable oldWatchdog = watchdog();
        assertNotNull(oldWatchdog);

        assertTrue(CriticalGlucoseAlarm.showActual(application, 0, 65f,
                "Updated reading", true));
        Runnable replacement = watchdog();
        assertNotNull(replacement);
        assertNotSame(oldWatchdog, replacement);
        assertEquals(token, currentToken());

        // Advance elapsed time without dispatching the paused main-looper tasks.
        // A token-only guard would allow the obsolete callback to post here.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(WATCHDOG_TEST_SECONDS));
        oldWatchdog.run();
        Notification unchanged = activeNotification(8_401).getNotification();
        assertNotNull(unchanged.fullScreenIntent);
        assertEquals("Updated reading",
                unchanged.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertSame(replacement, watchdog());

        replacement.run();
        Notification repeated = activeNotification(8_401).getNotification();
        assertNotNull(repeated.fullScreenIntent);
        assertEquals("Updated reading",
                repeated.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertEquals(token, currentToken());
        assertOnlyCriticalNotification(8_401);
        assertSingleAppAudio();
    }

    @Test
    public void retainedSevereWatchdogReassertsLatestLowerPriorityReading()
            throws Exception {
        long firstAt = System.currentTimeMillis();
        assertTrue(CriticalGlucoseAlarm.showActual(application, 5, 48f,
                "Severe first reading", true,
                CriticalDisplayPayload.immediateActual(firstAt, 48, null, firstAt)));
        String token = currentToken();
        Runnable retained = watchdog();
        assertNotNull(retained);
        Notification first = activeNotification(8_401).getNotification();
        String channel = first.getChannelId();

        ShadowSystemClock.advanceBy(Duration.ofSeconds(1L));
        long latestAt = System.currentTimeMillis();
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0, 65f,
                "Latest measured reading", false,
                CriticalDisplayPayload.immediateActual(latestAt, 65, null, latestAt)));
        CriticalGlucoseAlarm.Session live =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(live);
        assertEquals(token, live.token);
        assertEquals(CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                live.priority);
        assertEquals("Latest measured reading", live.body);
        assertSame(retained, watchdog());

        idleWatchdog();

        Notification repeated = activeNotification(8_401).getNotification();
        assertNull(repeated.fullScreenIntent);
        assertEquals(channel, repeated.getChannelId());
        assertEquals(live.title,
                repeated.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(live.body,
                repeated.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertEquals(live.body,
                repeated.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertEquals(token, currentToken());
        assertOnlyCriticalNotification(8_401);
        assertSingleAppAudio();
    }

    @Test
    public void ackCancelsWatchdogAndStaleCallbackCannotRaiseVolume()
            throws Exception {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        Runnable oldWatchdog = watchdog();
        assertNotNull(oldWatchdog);
        String oldToken = currentToken();

        assertTrue(CriticalGlucoseAlarm.acknowledge(application, oldToken));
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        oldWatchdog.run();
        idleWatchdog();

        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoCriticalNotification();
        assertNoAppAudio();
    }

    @Test
    public void snoozeCancelsWatchdogWithoutDestroyingResumableEpisode()
            throws Exception {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW));
        String token = currentToken();
        Runnable oldWatchdog = watchdog();
        assertNotNull(oldWatchdog);

        assertTrue(CriticalGlucoseAlarm.snooze(application, token, 300_000L));
        oldWatchdog.run();
        idleWatchdog();

        CriticalGlucoseAlarm.Session snoozed =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(snoozed);
        assertEquals(token, snoozed.token);
        assertTrue(snoozed.snoozeUntilMs > System.currentTimeMillis());
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(watchdog());
        assertNoCriticalNotification();
    }

    @Test
    public void actualRecoveryCancelsWatchdogAndStaleCallback()
            throws Exception {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
        Runnable oldWatchdog = watchdog();
        assertNotNull(oldWatchdog);

        CriticalGlucoseAlarm.resolveActual(application);
        oldWatchdog.run();
        idleWatchdog();

        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoCriticalNotification();
    }

    @Test
    public void oldCallbackCannotRaiseVolumeOrCancelReplacementOwner()
            throws Exception {
        assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(application,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH, 100));
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW));
        String oldToken = currentToken();
        Runnable oldWatchdog = watchdog();
        assertNotNull(oldWatchdog);

        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
        String newToken = currentToken();
        assertNotEquals(oldToken, newToken);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        oldWatchdog.run();

        // The old callback must not even apply the old episode's floor.
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(newToken, currentToken());
        assertNotNull(activeNotification(8_402));
        idleWatchdog();
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(newToken, currentToken());
        assertSingleAppAudio();
    }

    @Test
    public void lowImportanceChannelIsNotClaimedAsAudibleActualDelivery()
            throws Exception {
        assertMutedChannelRejected(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW,
                NotificationManager.IMPORTANCE_LOW);
    }

    @Test
    public void minimumImportanceChannelIsNotClaimedAsAudiblePredictiveDelivery()
            throws Exception {
        assertMutedChannelRejected(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH,
                NotificationManager.IMPORTANCE_MIN);
    }

    @Test
    public void removedNotificationEndsOwnerInsteadOfResurrectingAlarm()
            throws Exception {
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        assertNotNull(activeNotification(8_401));
        notifications.cancel(8_401);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);

        idleWatchdog();

        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoCriticalNotification();
        assertNoAppAudio();
    }

    @Test
    public void transientNotificationLookupMissesNeedThreeConsecutiveTicks()
            throws Exception {
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        String token = currentToken();
        Runnable liveWatchdog = watchdog();
        assertNotNull(liveWatchdog);
        notifications.cancel(8_401);

        liveWatchdog.run();
        assertEquals(token, currentToken());
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(token));
        liveWatchdog.run();
        assertEquals(token, currentToken());
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(token));

        liveWatchdog.run();
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoAppAudio();
    }

    @Test
    public void revokedNotificationPermissionEndsAudioOwnership()
            throws Exception {
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH));
        shadowOf(notifications).setNotificationsEnabled(false);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);

        idleWatchdog();

        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoCriticalNotification();
        assertNoAppAudio();
    }

    @Test
    public void watchdogKeepsPostedChannelWhenDndAccessChanges()
            throws Exception {
        shadowOf(notifications).setNotificationPolicyAccessGranted(false);
        assertTrue(show(CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        String token = currentToken();
        StatusBarNotification posted = activeNotification(8_401);
        assertNotNull(posted);
        String originalChannel = posted.getNotification().getChannelId();
        assertTrue(originalChannel.contains("_standard_"));

        shadowOf(notifications).setNotificationPolicyAccessGranted(true);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        idleWatchdog();

        StatusBarNotification kept = activeNotification(8_401);
        assertNotNull(kept);
        assertEquals(token, currentToken());
        assertEquals(originalChannel, kept.getNotification().getChannelId());
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0);
        assertSingleAppAudio();
    }

    private void assertActiveFloorRecovers(
            CriticalAlarmSoundCatalog.AlertType alertType) throws Exception {
        assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(application,
                alertType, 85));
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(show(alertType));
        String token = currentToken();
        int expectedFloor = CriticalAlarmAudioReliability.minimumAudibleIndex(
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 85);
        assertEquals(expectedFloor,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNotNull(watchdog());

        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        idleWatchdog();

        assertEquals(expectedFloor,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.getRingerMode());
        assertEquals(token, currentToken());
        StatusBarNotification posted = activeNotification(notificationId(alertType));
        assertNotNull(posted);
        assertEquals(0, posted.getNotification().flags
                & Notification.FLAG_INSISTENT);
        assertTrue((posted.getNotification().flags
                & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertSingleAppAudio();
    }

    private void assertMutedChannelRejected(
            CriticalAlarmSoundCatalog.AlertType alertType, int importance)
            throws Exception {
        String id = CriticalGlucoseAlarm.selectedChannelId(application, alertType);
        NotificationChannel channel = notifications.getNotificationChannel(id);
        assertNotNull(channel);
        channel.setImportance(importance);
        notifications.createNotificationChannel(channel);
        assertEquals(importance,
                notifications.getNotificationChannel(id).getImportance());
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);

        assertFalse(show(alertType));
        idleWatchdog();

        assertEquals(importance,
                notifications.getNotificationChannel(id).getImportance());
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertNull(watchdog());
        assertNoCriticalNotification();
    }

    private boolean show(CriticalAlarmSoundCatalog.AlertType alertType) {
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW) {
            return CriticalGlucoseAlarm.showActual(application, 0, 65f,
                    "Measured low", true);
        }
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH) {
            return CriticalGlucoseAlarm.showActual(application, 1, 230f,
                    "Measured high", true);
        }
        boolean low = alertType
                == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        long now = System.currentTimeMillis();
        return CriticalGlucoseAlarm.showPredictive(application, low,
                low ? "Approved likely low" : "Approved likely high",
                "Delivery test", low ? "72 mg/dL" : "170 mg/dL", now,
                now + 15L * 60_000L, 45L * 60_000L);
    }

    private int notificationId(CriticalAlarmSoundCatalog.AlertType alertType) {
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW) return 8_401;
        if (alertType == CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH) return 8_402;
        if (alertType == CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW) return 8_403;
        return 8_404;
    }

    private void idleWatchdog() {
        shadowOf(Looper.getMainLooper()).idleFor(WATCHDOG_TEST_SECONDS,
                TimeUnit.SECONDS);
    }

    private String currentToken() {
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(session);
        return session.token;
    }

    private Runnable watchdog() throws Exception {
        return (Runnable) staticField("audioWatchdog");
    }

    private Object staticField(String name) throws Exception {
        Field field = CriticalGlucoseAlarm.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void assertSingleAppAudio() {
        String token = currentToken();
        assertTrue(CriticalAlarmAudioPlayer.owns(token));
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(token));
        assertNotNull(CriticalAlarmAudioPlayer.playerForTests());
    }

    private void assertNoAppAudio() {
        assertNull(CriticalAlarmAudioPlayer.ownerTokenForTests());
        assertNull(CriticalAlarmAudioPlayer.playerForTests());
    }

    private StatusBarNotification activeNotification(int id) {
        for (StatusBarNotification item : notifications.getActiveNotifications()) {
            if (item != null && item.getId() == id) return item;
        }
        return null;
    }

    private void assertNoCriticalNotification() {
        for (int id = 8_401; id <= 8_406; id++) {
            assertNull("Unexpected critical notification " + id,
                    activeNotification(id));
        }
    }

    private void assertOnlyCriticalNotification(int expectedId) {
        int count = 0;
        for (StatusBarNotification item : notifications.getActiveNotifications()) {
            if (item != null && item.getId() >= 8_401 && item.getId() <= 8_406) {
                assertEquals(expectedId, item.getId());
                count++;
            }
        }
        assertEquals(1, count);
    }

    private void endCurrentSession() {
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        if (session != null) {
            CriticalGlucoseAlarm.acknowledge(application, session.token);
        }
    }

    private void clearPreferences(String name) {
        application.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }
}
