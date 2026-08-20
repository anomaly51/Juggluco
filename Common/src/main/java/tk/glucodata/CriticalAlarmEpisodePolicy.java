package tk.glucodata;

/** Pure priority and stale-action policy for the critical alarm runtime. */
final class CriticalAlarmEpisodePolicy {
    static final int PRIORITY_PREDICTIVE_LIKELY = 1;
    static final int PRIORITY_ACTUAL = 2;
    static final int PRIORITY_ACTUAL_SEVERE = 3;

    enum Transition {
        START,
        UPDATE_WITHOUT_RESTART,
        KEEP_HIGHER_PRIORITY
    }

    private CriticalAlarmEpisodePolicy() {}

    static Transition transition(boolean hasActive, String activeDirection,
            int activePriority, String incomingDirection,
            int incomingPriority) {
        if (!hasActive) return Transition.START;
        if (incomingPriority > activePriority) return Transition.START;
        if (incomingPriority < activePriority) {
            return Transition.KEEP_HIGHER_PRIORITY;
        }
        boolean sameDirection = normalized(activeDirection).equals(
                normalized(incomingDirection));
        if (!sameDirection) {
            // A same-severity reversal is a materially new clinical event.
            return Transition.START;
        }
        return Transition.UPDATE_WITHOUT_RESTART;
    }

    static boolean actionMatches(String activeToken, String actionToken) {
        return activeToken != null && !activeToken.isEmpty()
                && activeToken.equals(actionToken);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(
                java.util.Locale.ROOT);
    }
}
