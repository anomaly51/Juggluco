package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for the side-effect-free low-alarm hardware-key recognizer. */
public class CriticalAlarmVolumeDownGestureTest {
    private static final String LOW = CriticalGlucoseAlarm.DIRECTION_LOW;
    private static final String HIGH = CriticalGlucoseAlarm.DIRECTION_HIGH;
    private static final String SIGNAL = CriticalGlucoseAlarm.DIRECTION_SIGNAL;
    private static final String FIRST_TOKEN = "low-episode-one";
    private static final String SECOND_TOKEN = "low-episode-two";

    private CriticalAlarmVolumeDownGesture gesture;

    @Before
    public void setUp() {
        gesture = new CriticalAlarmVolumeDownGesture();
    }

    @Test
    public void acknowledgesOnFifthDistinctInitialDownForSameLowSession() {
        long atMs = 10_000L;
        for (int press = 1; press < 5; press++) {
            assertFalse(initialVolumeDown(FIRST_TOKEN, LOW,
                    atMs + press * 250L));
            assertEquals(press, progress(FIRST_TOKEN, LOW,
                    atMs + press * 250L));
        }

        assertTrue(initialVolumeDown(FIRST_TOKEN, LOW, atMs + 1_250L));
        assertEquals(0, progress(FIRST_TOKEN, LOW, atMs + 1_250L));
    }

    @Test
    public void actionUpAndHardwareRepeatNeverAdvanceProgress() {
        long atMs = 20_000L;
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, atMs));

        assertFalse(event(FIRST_TOKEN, LOW, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_DOWN, 1, atMs + 100L));
        assertFalse(event(FIRST_TOKEN, LOW, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_DOWN, 8, atMs + 200L));
        assertFalse(event(FIRST_TOKEN, LOW, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_UP, 0, atMs + 300L));

        assertEquals(1, progress(FIRST_TOKEN, LOW, atMs + 300L));
    }

    @Test
    public void otherHardwareKeysNeverAdvanceProgress() {
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, 30_000L));
        assertFalse(event(FIRST_TOKEN, LOW, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN, 0, 30_200L));
        assertFalse(event(FIRST_TOKEN, LOW, KeyEvent.KEYCODE_POWER,
                KeyEvent.ACTION_DOWN, 0, 30_400L));

        assertEquals(1, progress(FIRST_TOKEN, LOW, 30_400L));
    }

    @Test
    public void duplicateDispatchWithSameEventTimeCountsOnlyOnce() {
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, 40_000L));
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, 40_000L));

        assertEquals(1, progress(FIRST_TOKEN, LOW, 40_000L));
    }

    @Test
    public void tokenReplacementStartsACompletelyNewSequence() {
        pressTimes(FIRST_TOKEN, LOW, 50_000L, 3);
        assertEquals(3, progress(FIRST_TOKEN, LOW, 50_400L));

        assertFalse(initialVolumeDown(SECOND_TOKEN, LOW, 51_000L));
        assertEquals(1, progress(SECOND_TOKEN, LOW, 51_000L));
        pressTimes(SECOND_TOKEN, LOW, 51_250L, 3);
        assertTrue(initialVolumeDown(SECOND_TOKEN, LOW, 52_250L));
    }

    @Test
    public void directionChangeResetsLowSequence() {
        pressTimes(FIRST_TOKEN, LOW, 60_000L, 4);

        assertFalse(initialVolumeDown(FIRST_TOKEN, HIGH, 61_000L));
        assertEquals(0, progress(FIRST_TOKEN, HIGH, 61_000L));
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, 61_250L));
        assertEquals(1, progress(FIRST_TOKEN, LOW, 61_250L));
    }

    @Test
    public void highAndSignalAlarmsCanNeverCompleteGesture() {
        for (int press = 0; press < 8; press++) {
            assertFalse(initialVolumeDown(FIRST_TOKEN, HIGH,
                    70_000L + press * 100L));
            assertFalse(initialVolumeDown(FIRST_TOKEN, SIGNAL,
                    71_000L + press * 100L));
        }

        assertEquals(0, progress(FIRST_TOKEN, SIGNAL, 72_000L));
    }

    @Test
    public void slowSequenceCompletesWhenEveryGapIsAtMostTimeout() {
        long startMs = 80_000L;
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, startMs));
        for (int press = 1; press < 4; press++) {
            assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, startMs
                    + press * CriticalAlarmVolumeDownGesture
                            .INTER_PRESS_TIMEOUT_MS));
        }
        assertTrue(initialVolumeDown(FIRST_TOKEN, LOW, startMs
                + 4L * CriticalAlarmVolumeDownGesture
                        .INTER_PRESS_TIMEOUT_MS));
    }

    @Test
    public void gapBeyondTimeoutMakesCurrentPressFirstOfNewSequence() {
        long startMs = 100_000L;
        pressTimes(FIRST_TOKEN, LOW, startMs, 4);

        long lastPressMs = startMs + 600L;
        long expiredMs = lastPressMs
                + CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS + 1L;
        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, expiredMs));
        assertEquals(1, progress(FIRST_TOKEN, LOW, expiredMs));

        pressTimes(FIRST_TOKEN, LOW, expiredMs + 200L, 3);
        assertTrue(initialVolumeDown(FIRST_TOKEN, LOW, expiredMs + 1_000L));
    }

    @Test
    public void progressQueryExpiresWithoutAnotherKeyEvent() {
        long startMs = 120_000L;
        pressTimes(FIRST_TOKEN, LOW, startMs, 2);
        long lastPressMs = startMs + 200L;

        assertEquals(2, progress(FIRST_TOKEN, LOW, lastPressMs
                + CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS));
        assertEquals(0, progress(FIRST_TOKEN, LOW, lastPressMs
                + CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS + 1L));
    }

    @Test
    public void missingTokenCannotAccumulateProgress() {
        pressTimes(FIRST_TOKEN, LOW, 140_000L, 2);
        assertFalse(initialVolumeDown(null, LOW, 140_500L));
        assertFalse(initialVolumeDown("", LOW, 140_750L));

        assertEquals(0, progress("", LOW, 140_750L));
    }

    @Test
    public void regressedMonotonicTimeStartsNewSequence() {
        pressTimes(FIRST_TOKEN, LOW, 160_000L, 3);

        assertFalse(initialVolumeDown(FIRST_TOKEN, LOW, 159_000L));
        assertEquals(1, progress(FIRST_TOKEN, LOW, 159_000L));
    }

    @Test
    public void explicitResetDropsBindingAndPartialProgress() {
        pressTimes(FIRST_TOKEN, LOW, 180_000L, 3);

        gesture.reset();

        assertEquals(0, progress(FIRST_TOKEN, LOW, 180_500L));
        assertTrue(gesture.retainSession(FIRST_TOKEN, LOW, 180_500L));
        assertFalse(gesture.retainSession(FIRST_TOKEN, HIGH, 180_500L));
    }

    private void pressTimes(String token, String direction, long startMs,
            int count) {
        for (int index = 0; index < count; index++) {
            assertFalse(initialVolumeDown(token, direction,
                    startMs + index * 200L));
        }
    }

    private boolean initialVolumeDown(String token, String direction,
            long atMs) {
        return event(token, direction, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_DOWN, 0, atMs);
    }

    private boolean event(String token, String direction, int keyCode,
            int action, int repeatCount, long atMs) {
        return gesture.onKeyEvent(token, direction, keyCode, action,
                repeatCount, atMs);
    }

    private int progress(String token, String direction, long nowMs) {
        return gesture.progress(token, direction, nowMs);
    }
}
