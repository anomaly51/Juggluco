package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;

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
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

/** Integration tests for the process-wide hardware-key alarm controller. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CriticalAlarmVolumeKeyControllerTest {
    private static final String SESSION_PREFS = "critical_glucose_alarm_v1";

    private Application application;
    private NotificationManager notifications;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        CriticalAlarmTestMedia.resetAndRegister(application);
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        assertNotNull(notifications);
        ShadowNotificationManager shadow = shadowOf(notifications);
        shadow.setNotificationsEnabled(true);
        shadow.setNotificationPolicyAccessGranted(true);
        endCurrentSession();
        application.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences(CriticalAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        application.getSharedPreferences(
                CriticalAlarmSoundCatalog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        notifications.cancelAll();
        CriticalAlarmVolumeKeyController.resetForTests();
        CriticalGlucoseAlarm.ensureChannels(application);
    }

    @After
    public void tearDown() {
        endCurrentSession();
        CriticalAlarmVolumeKeyController.resetForTests();
        notifications.cancelAll();
        application.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        CriticalAlarmTestMedia.reset();
    }

    @Test
    public void fifthInitialDownAcknowledgesExactActualLowSession() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);

        for (int count = 1; count <= 5; count++) {
            CriticalAlarmVolumeKeyController.Result result = dispatch(
                    session.token, initialVolumeDown());
            assertTrue(result.consumed);
            assertTrue(result.advanced);
            assertEquals(count, result.progress);
            assertEquals(count == 5, result.acknowledged);
            if (count < 5) {
                assertEquals(count, CriticalAlarmVolumeKeyController.progress(
                        application, session.token));
                advance(120L);
            }
        }

        assertNull(CriticalGlucoseAlarm.currentSession(application));
        assertFalse(CriticalAlarmAudioPlayer.isPlaying(session.token));
    }

    @Test
    public void volumeDownUpAndRepeatsAreConsumedButNeverAdvance() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
        CriticalAlarmVolumeKeyController.Result first = dispatch(session.token,
                initialVolumeDown());
        assertTrue(first.advanced);
        assertEquals(1, first.progress);

        advance(80L);
        CriticalAlarmVolumeKeyController.Result repeat = dispatch(
                session.token, volumeDown(KeyEvent.ACTION_DOWN, 4));
        assertTrue(repeat.consumed);
        assertFalse(repeat.advanced);
        assertEquals(1, repeat.progress);
        assertFalse(repeat.acknowledged);

        advance(80L);
        CriticalAlarmVolumeKeyController.Result up = dispatch(session.token,
                volumeDown(KeyEvent.ACTION_UP, 0));
        assertTrue(up.consumed);
        assertFalse(up.advanced);
        assertEquals(1, up.progress);
        assertFalse(up.acknowledged);
    }

    @Test
    public void staleExactTokenAndNonVolumeKeysAreNotConsumed() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);

        CriticalAlarmVolumeKeyController.Result stale = dispatch(
                "stale-token", initialVolumeDown());
        assertFalse(stale.consumed);
        assertFalse(stale.advanced);
        assertEquals(0, stale.progress);
        assertFalse(stale.acknowledged);

        CriticalAlarmVolumeKeyController.Result volumeUp = dispatch(
                session.token, key(KeyEvent.KEYCODE_VOLUME_UP,
                        KeyEvent.ACTION_DOWN, 0));
        assertFalse(volumeUp.consumed);
        assertEquals(0, CriticalAlarmVolumeKeyController.progress(application,
                session.token));
        assertNotNull(CriticalGlucoseAlarm.currentSession(application));
    }

    @Test
    public void nullExactTokenUsesCurrentEligibleSession() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);

        CriticalAlarmVolumeKeyController.Result result = dispatch(null,
                initialVolumeDown());

        assertTrue(result.consumed);
        assertTrue(result.advanced);
        assertEquals(1, result.progress);
        assertEquals(1, CriticalAlarmVolumeKeyController.progress(application,
                session.token));
    }

    @Test
    public void highAndPredictiveLowTestSessionsAreIneligible() {
        CriticalGlucoseAlarm.Session high = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH);
        assertFalse(dispatch(high.token, initialVolumeDown()).consumed);
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, high.token));

        CriticalGlucoseAlarm.Session predictive = startTest(
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW);
        CriticalAlarmVolumeKeyController.Result result = dispatch(
                predictive.token, initialVolumeDown());
        assertFalse(result.consumed);
        assertEquals(0, CriticalAlarmVolumeKeyController.progress(application,
                predictive.token));
        assertNotNull(CriticalGlucoseAlarm.currentSession(application));
    }

    @Test
    public void progressExpiresAndSameSessionUpdateDoesNotResetIt() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
        assertTrue(dispatch(session.token, initialVolumeDown()).advanced);
        advance(100L);
        assertTrue(dispatch(session.token, initialVolumeDown()).advanced);
        assertEquals(2, CriticalAlarmVolumeKeyController.progress(application,
                session.token));

        CriticalAlarmVolumeKeyController.sessionChanged(session.copy());
        assertEquals(2, CriticalAlarmVolumeKeyController.progress(application,
                session.token));

        advance(CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS + 1L);
        assertEquals(0, CriticalAlarmVolumeKeyController.progress(application,
                session.token));
    }

    @Test
    public void snoozedSessionPublicationResetsPartialSequence() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
        assertTrue(dispatch(session.token, initialVolumeDown()).advanced);
        assertEquals(1, CriticalAlarmVolumeKeyController.progress(application,
                session.token));

        CriticalGlucoseAlarm.Session snoozed = session.copy();
        snoozed.snoozeUntilMs = System.currentTimeMillis() + 60_000L;
        CriticalAlarmVolumeKeyController.sessionChanged(snoozed);

        assertEquals(0, CriticalAlarmVolumeKeyController.progress(application,
                session.token));
    }

    @Test
    public void fullscreenActivityRoutesFivePressesToExactAcknowledgement() {
        CriticalGlucoseAlarm.Session session = startTest(
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW);
        Intent intent = new Intent(application,
                CriticalGlucoseAlarmActivity.class).putExtra(
                CriticalGlucoseAlarmReceiver.EXTRA_TOKEN, session.token);
        ActivityController<CriticalGlucoseAlarmActivity> controller =
                Robolectric.buildActivity(CriticalGlucoseAlarmActivity.class,
                        intent).create().start().resume().visible();
        CriticalGlucoseAlarmActivity activity = controller.get();
        try {
            for (int count = 1; count <= 4; count++) {
                assertTrue(activity.dispatchKeyEvent(initialVolumeDown()));
                assertNotNull(CriticalGlucoseAlarm.session(application,
                        session.token));
                advance(100L);
            }
            assertTrue(activity.dispatchKeyEvent(initialVolumeDown()));
            assertNull(CriticalGlucoseAlarm.session(application,
                    session.token));
            assertTrue(activity.isFinishing());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private CriticalGlucoseAlarm.Session startTest(
            CriticalAlarmSoundCatalog.AlertType type) {
        assertTrue(CriticalGlucoseAlarm.showTest(application, type));
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        assertNotNull(session);
        return session;
    }

    private CriticalAlarmVolumeKeyController.Result dispatch(String token,
            KeyEvent event) {
        return CriticalAlarmVolumeKeyController.dispatch(application, token,
                event);
    }

    private static KeyEvent initialVolumeDown() {
        return volumeDown(KeyEvent.ACTION_DOWN, 0);
    }

    private static KeyEvent volumeDown(int action, int repeatCount) {
        return key(KeyEvent.KEYCODE_VOLUME_DOWN, action, repeatCount);
    }

    private static KeyEvent key(int keyCode, int action, int repeatCount) {
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, keyCode, repeatCount);
    }

    private static void advance(long durationMs) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(durationMs));
    }

    private void endCurrentSession() {
        CriticalGlucoseAlarm.Session current =
                CriticalGlucoseAlarm.currentSession(application);
        if (current != null) {
            CriticalGlucoseAlarm.acknowledge(application, current.token);
        }
    }
}
