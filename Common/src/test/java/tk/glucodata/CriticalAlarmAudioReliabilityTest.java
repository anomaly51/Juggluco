package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmAudioReliabilityTest {
    private Application application;
    private AudioManager audio;
    private int originalVolume;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        audio = (AudioManager) application.getSystemService(
                Context.AUDIO_SERVICE);
        assertNotNull(audio);
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        CriticalAlarmAudioReliability.restoreAlarmVolume(application);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
        application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void silentAlarmStreamIsRaisedAndRestored() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        assertTrue(floor > 0);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);

        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));
        assertEquals(floor,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(CriticalAlarmAudioReliability
                .restoreAlarmVolume(application));
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void manualVolumeChangeDuringAlarmIsNeverOverwrittenOnStop() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));

        int manual = floor == maximum ? Math.max(0, floor - 1) : maximum;
        audio.setStreamVolume(AudioManager.STREAM_ALARM, manual, 0);
        assertFalse(CriticalAlarmAudioReliability
                .restoreAlarmVolume(application));
        assertEquals(manual,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void alreadyAudibleVolumeIsNotChanged() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0);

        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));
        assertEquals(floor,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(CriticalAlarmAudioReliability
                .restoreAlarmVolume(application));
    }

    @Test
    public void deliveryWakeLockIsBoundedAndExplicitlyReleased() {
        PowerManager.WakeLock wakeLock = CriticalAlarmAudioReliability
                .acquireDeliveryWakeLock(application);
        assertNotNull(wakeLock);
        assertTrue(wakeLock.isHeld());

        CriticalAlarmAudioReliability.releaseDeliveryWakeLock(wakeLock);
        assertFalse(wakeLock.isHeld());
    }

    @Test
    public void audibleFloorIsBounded() {
        assertEquals(0,
                CriticalAlarmAudioReliability.minimumAudibleIndex(0));
        assertEquals(1,
                CriticalAlarmAudioReliability.minimumAudibleIndex(1));
        assertEquals(7,
                CriticalAlarmAudioReliability.minimumAudibleIndex(10));
        assertEquals(3,
                CriticalAlarmAudioReliability.minimumAudibleIndex(3, 70));
        assertEquals(9,
                CriticalAlarmAudioReliability.minimumAudibleIndex(10, 85));
        assertEquals(10,
                CriticalAlarmAudioReliability.minimumAudibleIndex(10, 100));
        assertEquals(7,
                CriticalAlarmAudioReliability.minimumAudibleIndex(10, 99));
    }

    @Test
    public void configuredFullVolumeFloorIsAppliedAndRestored() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);

        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application, 100));
        assertEquals(maximum,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(CriticalAlarmAudioReliability
                .restoreAlarmVolume(application));
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void preparedRestoreSurvivesSynchronousAndAsyncOemReassertion() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        assertTrue(floor >= 2);
        int before = Math.max(0, floor - 2);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));

        CriticalAlarmAudioReliability.RestorePlan plan =
                CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(
                        application, "ended-token");
        assertNotNull(plan);
        assertEquals(before, plan.originalVolume);
        assertEquals(floor, plan.forcedVolume);

        // Samsung can move the channel-owned step while its notification is
        // being neutralized. Preparation happened before cancellation, so this
        // must not be mistaken for a user's during-alarm change.
        audio.setStreamVolume(AudioManager.STREAM_ALARM, floor - 1, 0);
        assertTrue(CriticalAlarmAudioReliability
                .applyPreparedAlarmVolumeRestore(application, plan));
        assertEquals(before,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));

        // Model the observed asynchronous T+1.3s reassertion.
        audio.setStreamVolume(AudioManager.STREAM_ALARM, floor - 1, 0);
        assertTrue(CriticalAlarmAudioReliability
                .retryPreparedAlarmVolumeRestore(application, plan, false));
        assertEquals(before,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).getAll().isEmpty());

        assertTrue(CriticalAlarmAudioReliability
                .retryPreparedAlarmVolumeRestore(application, plan, true));
        assertTrue(application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test
    public void restoreRetryPreservesUnrecognizedPostAckManualChange() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        assertTrue(floor >= 3);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));
        CriticalAlarmAudioReliability.RestorePlan plan =
                CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(
                        application, "ended-token");
        assertNotNull(plan);
        assertTrue(CriticalAlarmAudioReliability
                .applyPreparedAlarmVolumeRestore(application, plan));

        int manual = Math.max(1, floor - 2);
        assertTrue(manual != plan.originalVolume
                && manual != plan.forcedVolume
                && manual != plan.forcedVolume - 1);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, manual, 0);

        assertFalse(CriticalAlarmAudioReliability
                .retryPreparedAlarmVolumeRestore(application, plan, false));
        assertEquals(manual,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test
    public void roundedForcedStepIsManualAfterShortOemWindow() {
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int floor = CriticalAlarmAudioReliability.minimumAudibleIndex(maximum);
        assertTrue(floor >= 2);
        int before = Math.max(0, floor - 2);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0);
        assertTrue(CriticalAlarmAudioReliability
                .ensureAudibleAlarmVolume(application));
        CriticalAlarmAudioReliability.RestorePlan plan =
                CriticalAlarmAudioReliability.prepareAlarmVolumeRestore(
                        application, "ended-token");
        assertNotNull(plan);
        assertTrue(CriticalAlarmAudioReliability
                .applyPreparedAlarmVolumeRestore(application, plan));

        ShadowSystemClock.advanceBy(Duration.ofMillis(1_300L));
        int manual = floor - 1;
        audio.setStreamVolume(AudioManager.STREAM_ALARM, manual, 0);

        assertFalse(CriticalAlarmAudioReliability
                .retryPreparedAlarmVolumeRestore(application, plan, false));
        assertEquals(manual,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(application.getSharedPreferences(
                CriticalAlarmAudioReliability.PREFS_NAME,
                Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test
    public void samsungRoundedStepBeforeAckRemainsControllerOwned() {
        assertTrue(CriticalAlarmAudioReliability.isSamsungRoundedForcedStep(
                10, 1, 11, "Samsung"));
        assertFalse(CriticalAlarmAudioReliability.isSamsungRoundedForcedStep(
                10, 1, 11, "Google"));
        assertFalse(CriticalAlarmAudioReliability.isSamsungRoundedForcedStep(
                9, 1, 11, "Samsung"));
        assertFalse(CriticalAlarmAudioReliability.isSamsungRoundedForcedStep(
                10, 10, 11, "Samsung"));
    }
}
