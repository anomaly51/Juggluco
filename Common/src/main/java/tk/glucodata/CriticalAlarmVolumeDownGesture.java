package tk.glucodata;

import android.view.KeyEvent;

import java.util.Objects;

/**
 * Pure, token-bound recognizer for the deliberate low-alarm volume gesture.
 *
 * <p>Five distinct volume-down presses must follow one another without a long
 * pause. Key-repeat events from holding the button never advance the gesture.
 * The caller remains responsible for revalidating and acknowledging the live
 * alarm after this recognizer returns {@code true}.</p>
 */
final class CriticalAlarmVolumeDownGesture {
    static final int REQUIRED_PRESSES = 5;
    static final long INTER_PRESS_TIMEOUT_MS = 4_000L;

    private static final long NO_EVENT_TIME = Long.MIN_VALUE;

    private String boundToken;
    private String boundDirection;
    private int acceptedPresses;
    private long lastPressAtMs = NO_EVENT_TIME;

    /**
     * Consumes only an initial volume-down {@link KeyEvent#ACTION_DOWN} for a
     * low-direction session. {@code eventTimeMs} must use the monotonic clock
     * supplied by {@link KeyEvent#getEventTime()}.
     */
    boolean onKeyEvent(String sessionToken, String direction, int keyCode,
            int action, int repeatCount, long eventTimeMs) {
        if (!retainSession(sessionToken, direction, eventTimeMs)) return false;
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                || action != KeyEvent.ACTION_DOWN || repeatCount != 0) {
            return false;
        }

        // A duplicated dispatch of the same physical down event must not be
        // mistaken for another press. Android gives all copies one eventTime.
        if (eventTimeMs == lastPressAtMs) return false;

        acceptedPresses++;
        lastPressAtMs = eventTimeMs;
        if (acceptedPresses < REQUIRED_PRESSES) return false;

        reset();
        return true;
    }

    /** Clears partial progress, including its session binding. */
    void reset() {
        boundToken = null;
        boundDirection = null;
        acceptedPresses = 0;
        lastPressAtMs = NO_EVENT_TIME;
    }

    /**
     * Keeps progress only for this exact eligible session and returns its
     * non-expired press count. This method performs lazy timeout cleanup even
     * when no further hardware key event arrives.
     */
    int progress(String sessionToken, String direction, long nowMs) {
        return retainSession(sessionToken, direction, nowMs)
                ? acceptedPresses : 0;
    }

    /**
     * Retains progress only while the exact low session remains current.
     * Controllers can call this when their visible session changes even if
     * they do not currently need the numeric progress.
     */
    boolean retainSession(String sessionToken, String direction, long nowMs) {
        if (!Objects.equals(boundToken, sessionToken)
                || !Objects.equals(boundDirection, direction)) {
            bind(sessionToken, direction);
        }
        if (!eligible(sessionToken, direction)) {
            reset();
            return false;
        }
        expireProgress(nowMs);
        return true;
    }

    private void bind(String sessionToken, String direction) {
        boundToken = sessionToken;
        boundDirection = direction;
        acceptedPresses = 0;
        lastPressAtMs = NO_EVENT_TIME;
    }

    private void expireProgress(long nowMs) {
        if (acceptedPresses == 0) return;
        if (nowMs < lastPressAtMs
                || nowMs - lastPressAtMs > INTER_PRESS_TIMEOUT_MS) {
            acceptedPresses = 0;
            lastPressAtMs = NO_EVENT_TIME;
        }
    }

    private static boolean eligible(String sessionToken, String direction) {
        return sessionToken != null && !sessionToken.isEmpty()
                && CriticalGlucoseAlarm.DIRECTION_LOW.equals(direction);
    }
}
