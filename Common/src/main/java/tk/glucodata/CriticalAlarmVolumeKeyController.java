package tk.glucodata;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Process-wide bridge between focused Android windows and the pure
 * volume-down gesture recognizer.
 *
 * <p>Every decision is revalidated against the exact live alarm session.
 * The fifth press is acknowledged after releasing {@link #LOCK}; the alarm
 * acknowledgement synchronously notifies UI surfaces and must never be
 * invoked while the recognizer lock is held.</p>
 */
final class CriticalAlarmVolumeKeyController {
    private static final Object LOCK = new Object();
    private static final CriticalAlarmVolumeDownGesture GESTURE =
            new CriticalAlarmVolumeDownGesture();
    private static final Result IGNORED = new Result(false, false, 0, false);

    private CriticalAlarmVolumeKeyController() {}

    /** Immutable outcome consumed by the Activity and overlay key routers. */
    static final class Result {
        final boolean consumed;
        final boolean advanced;
        final int progress;
        final boolean acknowledged;

        private Result(boolean consumed, boolean advanced, int progress,
                boolean acknowledged) {
            this.consumed = consumed;
            this.advanced = advanced;
            this.progress = progress;
            this.acknowledged = acknowledged;
        }
    }

    /**
     * Handles one hardware-key event for an exact alarm token. A null token
     * deliberately means "the current session", which is used by the main
     * application window when the above-app surface is temporarily hidden.
     */
    static Result dispatch(Context context, String optionalExactToken,
            KeyEvent event) {
        if (context == null || event == null) return IGNORED;
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        CriticalGlucoseAlarm.Session session = resolveSession(app,
                optionalExactToken);
        if (!eligible(session, System.currentTimeMillis())) {
            synchronized (LOCK) {
                GESTURE.reset();
            }
            return IGNORED;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return IGNORED;
        }

        final boolean completed;
        final boolean advanced;
        final int eventProgress;
        synchronized (LOCK) {
            int before = GESTURE.progress(session.token, session.direction,
                    event.getEventTime());
            completed = GESTURE.onKeyEvent(session.token, session.direction,
                    event.getKeyCode(), event.getAction(),
                    event.getRepeatCount(), event.getEventTime());
            int after = GESTURE.progress(session.token, session.direction,
                    event.getEventTime());
            advanced = completed || after > before;
            // Completion resets the pure recognizer. Preserve the fifth step
            // in this event result so the UI can present coherent progress if
            // exact acknowledgement loses a race with session replacement.
            eventProgress = completed
                    ? CriticalAlarmVolumeDownGesture.REQUIRED_PRESSES : after;
        }

        // Do not move this call into LOCK. acknowledge() synchronously
        // publishes sessionChanged(null), which also enters this controller.
        boolean acknowledged = completed
                && CriticalGlucoseAlarm.acknowledge(app, session.token);
        // Once an eligible low-alarm sequence starts, consume down/up/repeat
        // events alike so presses one through four cannot make its alarm
        // stream quieter. Only initial ACTION_DOWN events advance GESTURE.
        return new Result(true, advanced, eventProgress, acknowledged);
    }

    /** Returns non-expired progress for the exact live eligible session. */
    static int progress(Context context, String token) {
        if (context == null) return 0;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        CriticalGlucoseAlarm.Session session = resolveSession(app, token);
        if (!eligible(session, System.currentTimeMillis())) {
            synchronized (LOCK) {
                GESTURE.reset();
            }
            return 0;
        }
        synchronized (LOCK) {
            return GESTURE.progress(session.token, session.direction,
                    SystemClock.uptimeMillis());
        }
    }

    /**
     * Retains partial progress only across updates of the same eligible alarm
     * episode. Replacement, snooze, expiry and terminal publication reset it.
     */
    static void sessionChanged(CriticalGlucoseAlarm.Session session) {
        long wallNow = System.currentTimeMillis();
        long monotonicNow = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            if (!eligible(session, wallNow)) {
                GESTURE.reset();
                return;
            }
            GESTURE.retainSession(session.token, session.direction,
                    monotonicNow);
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            GESTURE.reset();
        }
    }

    private static CriticalGlucoseAlarm.Session resolveSession(Context context,
            String optionalExactToken) {
        return optionalExactToken == null
                ? CriticalGlucoseAlarm.currentSession(context)
                : CriticalGlucoseAlarm.session(context, optionalExactToken);
    }

    private static boolean eligible(CriticalGlucoseAlarm.Session session,
            long nowMs) {
        return session != null && session.token != null
                && !session.token.isEmpty()
                && session.expiresAtMs > nowMs
                && session.snoozeUntilMs <= nowMs
                && session.volumeDownStopEligible();
    }
}
