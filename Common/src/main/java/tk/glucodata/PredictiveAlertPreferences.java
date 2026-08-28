package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Small, process-safe preference boundary for forecast alerts.
 *
 * <p>The forecasting coordinator deliberately receives an immutable snapshot,
 * so a single reading is always evaluated against one coherent configuration.</p>
 */
final class PredictiveAlertPreferences {
    static final int SENSITIVITY_EARLY = 0;
    static final int SENSITIVITY_BALANCED = 1;
    static final int SENSITIVITY_FEWER = 2;

    static final String DIRECTION_LOW = "low";
    static final String DIRECTION_HIGH = "high";
    static final String EVIDENCE_POSSIBLE = "possible";
    static final String EVIDENCE_LIKELY = "likely";

    static final float TARGET_LOW_MG_DL = 75.6f;
    static final float TARGET_HIGH_MG_DL = 162.0f;
    static final float TARGET_LOW_MMOL_L = 4.2f;
    static final float TARGET_HIGH_MMOL_L = 9.0f;

    static final int[] HORIZON_OPTIONS_MINUTES = {15, 20, 30, 45, 60};
    static final int[] COOLDOWN_OPTIONS_MINUTES = {15, 30, 60, 120};

    static final String PREFS_NAME = "predictive_alerts_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LOW_ENABLED = "low_enabled";
    private static final String KEY_HIGH_ENABLED = "high_enabled";
    private static final String KEY_LOW_HORIZON = "low_horizon_minutes";
    private static final String KEY_HIGH_HORIZON = "high_horizon_minutes";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_COOLDOWN = "cooldown_minutes";
    private static final String KEY_LOW_SENSITIVITY = "low_sensitivity";
    private static final String KEY_HIGH_SENSITIVITY = "high_sensitivity";
    private static final String KEY_LOW_COOLDOWN = "low_cooldown_minutes";
    private static final String KEY_HIGH_COOLDOWN = "high_cooldown_minutes";
    private static final String KEY_LAST_ALERT_PREFIX = "last_alert_at_";
    private static final String KEY_ACTIVE_EPISODE_DIRECTION =
            "active_episode_direction";
    private static final String KEY_ACTIVE_ANCHOR = "active_anchor_ms";
    private static final String KEY_ACTIVE_EVIDENCE = "active_evidence";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until_ms";
    private static final String KEY_SNOOZE_DIRECTION = "snooze_direction";
    private static final String KEY_SNOOZE_ANCHOR = "snooze_anchor_ms";
    // v2 corrects the old JNI float-to-uint truncation (75.6 could become 75.5).
    private static final String KEY_TARGET_MIGRATED =
            "target_4_2_9_0_migrated_v2";

    private static final int DEFAULT_LOW_HORIZON_MINUTES = 20;
    private static final int DEFAULT_HIGH_HORIZON_MINUTES = 30;
    private static final int DEFAULT_COOLDOWN_MINUTES = 60;

    private final SharedPreferences preferences;

    PredictiveAlertPreferences(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateSharedDirectionPolicy();
        migrateNativeTargetOnce();
    }

    Snapshot snapshot() {
        // A repository may be constructed before the native library has
        // finished loading. Retrying here makes the one-time migration robust.
        migrateSharedDirectionPolicy();
        migrateNativeTargetOnce();
        boolean enabled = effectiveEnabled();
        int lowSensitivity = directionSensitivity(true);
        int highSensitivity = directionSensitivity(false);
        int lowCooldown = directionCooldownMinutes(true);
        int highCooldown = directionCooldownMinutes(false);
        return new Snapshot(
                enabled,
                preferences.getBoolean(KEY_LOW_ENABLED, true),
                preferences.getBoolean(KEY_HIGH_ENABLED, true),
                validOption(preferences.getInt(KEY_LOW_HORIZON,
                                DEFAULT_LOW_HORIZON_MINUTES),
                        HORIZON_OPTIONS_MINUTES, DEFAULT_LOW_HORIZON_MINUTES),
                validOption(preferences.getInt(KEY_HIGH_HORIZON,
                                DEFAULT_HIGH_HORIZON_MINUTES),
                        HORIZON_OPTIONS_MINUTES, DEFAULT_HIGH_HORIZON_MINUTES),
                lowSensitivity, highSensitivity,
                lowCooldown, highCooldown);
    }

    void setEnabled(boolean enabled) {
        boolean effective = enabled
                && PredictiveAlertNotifier.supportsExpiringAlerts();
        preferences.edit().putBoolean(KEY_ENABLED, effective).apply();
        if (!effective) {
            clearEpisode();
            setSnoozeUntil(0L);
        }
    }

    void setLowEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_LOW_ENABLED, enabled).apply();
    }

    void setHighEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_HIGH_ENABLED, enabled).apply();
    }

    /**
     * Updates one direction and keeps the master switch coherent. Enabling any
     * direction enables the master; disabling the last enabled direction turns
     * it off. A disabled master is never turned on merely by turning a
     * direction off.
     */
    void setDirectionEnabled(boolean lowDirection, boolean enabled) {
        String key = lowDirection ? KEY_LOW_ENABLED : KEY_HIGH_ENABLED;
        String otherKey = lowDirection ? KEY_HIGH_ENABLED : KEY_LOW_ENABLED;
        boolean masterWasEnabled = preferences.getBoolean(KEY_ENABLED, false);
        boolean otherEnabled = preferences.getBoolean(otherKey, true);
        // Older installs represented an untouched direction as a missing key,
        // whose read-time default was true. On the first enable from a disabled
        // master that implicit default must not silently opt the other
        // direction in. Preserve it only when the master is already active or
        // the user previously made an explicit choice for that direction.
        boolean preserveOther = masterWasEnabled
                || preferences.contains(otherKey);
        if (enabled && !preserveOther) otherEnabled = false;
        boolean requestedMaster = enabled || (masterWasEnabled && otherEnabled);
        boolean effectiveMaster = requestedMaster
                && PredictiveAlertNotifier.supportsExpiringAlerts();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(key, enabled)
                .putBoolean(KEY_ENABLED, effectiveMaster);
        if (enabled && !preserveOther) {
            // Materialize the one-time choice so subsequent toggles no longer
            // need to distinguish the legacy default from user intent.
            editor.putBoolean(otherKey, false);
        }
        editor.apply();
        if (!effectiveMaster) {
            clearEpisode();
            setSnoozeUntil(0L);
        }
    }

    void setLowHorizonMinutes(int minutes) {
        preferences.edit().putInt(KEY_LOW_HORIZON,
                validOption(minutes, HORIZON_OPTIONS_MINUTES,
                        DEFAULT_LOW_HORIZON_MINUTES)).apply();
    }

    void setHighHorizonMinutes(int minutes) {
        preferences.edit().putInt(KEY_HIGH_HORIZON,
                validOption(minutes, HORIZON_OPTIONS_MINUTES,
                        DEFAULT_HIGH_HORIZON_MINUTES)).apply();
    }

    void setDirectionHorizonMinutes(boolean lowDirection, int minutes) {
        if (lowDirection) {
            setLowHorizonMinutes(minutes);
        } else {
            setHighHorizonMinutes(minutes);
        }
    }

    void setSensitivity(int sensitivity) {
        int valid = validSensitivity(sensitivity);
        preferences.edit()
                .putInt(KEY_SENSITIVITY, valid)
                .putInt(KEY_LOW_SENSITIVITY, valid)
                .putInt(KEY_HIGH_SENSITIVITY, valid)
                .apply();
    }

    void setCooldownMinutes(int minutes) {
        int valid = validOption(minutes, COOLDOWN_OPTIONS_MINUTES,
                DEFAULT_COOLDOWN_MINUTES);
        preferences.edit()
                .putInt(KEY_COOLDOWN, valid)
                .putInt(KEY_LOW_COOLDOWN, valid)
                .putInt(KEY_HIGH_COOLDOWN, valid)
                .apply();
    }

    void setDirectionSensitivity(boolean lowDirection, int sensitivity) {
        preferences.edit().putInt(
                lowDirection ? KEY_LOW_SENSITIVITY : KEY_HIGH_SENSITIVITY,
                validSensitivity(sensitivity)).apply();
    }

    void setLowSensitivity(int sensitivity) {
        setDirectionSensitivity(true, sensitivity);
    }

    void setHighSensitivity(int sensitivity) {
        setDirectionSensitivity(false, sensitivity);
    }

    void setDirectionCooldownMinutes(boolean lowDirection, int minutes) {
        int fallback = DEFAULT_COOLDOWN_MINUTES;
        preferences.edit().putInt(
                lowDirection ? KEY_LOW_COOLDOWN : KEY_HIGH_COOLDOWN,
                validOption(minutes, COOLDOWN_OPTIONS_MINUTES, fallback))
                .apply();
    }

    void setLowCooldownMinutes(int minutes) {
        setDirectionCooldownMinutes(true, minutes);
    }

    void setHighCooldownMinutes(int minutes) {
        setDirectionCooldownMinutes(false, minutes);
    }

    long lastAlertAt(String direction) {
        return preferences.getLong(KEY_LAST_ALERT_PREFIX
                + normalizedDirection(direction), 0L);
    }

    String activeEpisodeDirection() {
        return preferences.getString(KEY_ACTIVE_EPISODE_DIRECTION, "");
    }

    long activeAnchorMs() {
        return preferences.getLong(KEY_ACTIVE_ANCHOR, 0L);
    }

    String activeEvidence() {
        return preferences.getString(KEY_ACTIVE_EVIDENCE, "");
    }

    void recordAlert(String direction, long atMs, long anchorMs) {
        recordAlert(direction, atMs, anchorMs, "");
    }

    void recordAlert(String direction, long atMs, long anchorMs,
            String evidence) {
        String normalized = normalizedDirection(direction);
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_LAST_ALERT_PREFIX + normalized, Math.max(0L, atMs))
                .putString(KEY_ACTIVE_EPISODE_DIRECTION, normalized)
                .putLong(KEY_ACTIVE_ANCHOR, Math.max(0L, anchorMs))
                .putString(KEY_ACTIVE_EVIDENCE, normalizedEvidence(evidence));
        String snoozedDirection = preferences.getString(
                KEY_SNOOZE_DIRECTION, "");
        if (snoozedDirection.isEmpty()
                || snoozedDirection.equals(normalized)) {
            editor.remove(KEY_SNOOZE_UNTIL)
                    .remove(KEY_SNOOZE_DIRECTION)
                    .remove(KEY_SNOOZE_ANCHOR);
        }
        editor.apply();
    }

    void clearEpisode() {
        preferences.edit()
                .remove(KEY_ACTIVE_EPISODE_DIRECTION)
                .remove(KEY_ACTIVE_ANCHOR)
                .remove(KEY_ACTIVE_EVIDENCE)
                .apply();
    }

    void clearDeliveryState() {
        preferences.edit()
                .remove(KEY_LAST_ALERT_PREFIX + DIRECTION_LOW)
                .remove(KEY_LAST_ALERT_PREFIX + DIRECTION_HIGH)
                .remove(KEY_ACTIVE_EPISODE_DIRECTION)
                .remove(KEY_ACTIVE_ANCHOR)
                .remove(KEY_ACTIVE_EVIDENCE)
                .remove(KEY_SNOOZE_UNTIL)
                .remove(KEY_SNOOZE_DIRECTION)
                .remove(KEY_SNOOZE_ANCHOR)
                .apply();
    }

    void setSnoozeUntil(long atMs) {
        if (atMs <= 0L) {
            clearSnooze();
        } else {
            // Kept as a process-recreation-compatible fallback for callers
            // that do not have a session direction/anchor.
            preferences.edit()
                    .putLong(KEY_SNOOZE_UNTIL, atMs)
                    .remove(KEY_SNOOZE_DIRECTION)
                    .remove(KEY_SNOOZE_ANCHOR)
                    .apply();
        }
    }

    /** Called only after the shared alarm controller validates its session. */
    void snoozePrediction(String direction, long anchorMs, long untilMs) {
        if (untilMs <= 0L) {
            clearSnooze();
            return;
        }
        preferences.edit()
                .putLong(KEY_SNOOZE_UNTIL, untilMs)
                .putString(KEY_SNOOZE_DIRECTION,
                        normalizedDirection(direction))
                .putLong(KEY_SNOOZE_ANCHOR, Math.max(0L, anchorMs))
                .apply();
    }

    long snoozeUntil() {
        return preferences.getLong(KEY_SNOOZE_UNTIL, 0L);
    }

    boolean snoozeBlocks(String direction, long nowMs) {
        long untilMs = snoozeUntil();
        if (untilMs <= nowMs) return false;
        String snoozedDirection = preferences.getString(
                KEY_SNOOZE_DIRECTION, "");
        return snoozedDirection.isEmpty()
                || snoozedDirection.equals(normalizedDirection(direction));
    }

    /**
     * An expired, validated snooze is a one-shot bypass of both the active
     * anchor and cooldown gates. A refreshed forecast may have a newer anchor,
     * so the original anchor is treated as a lower bound.
     */
    boolean snoozeReplayDue(String direction, long anchorMs, long nowMs) {
        long untilMs = snoozeUntil();
        if (untilMs <= 0L || nowMs < untilMs) return false;
        String normalized = normalizedDirection(direction);
        String snoozedDirection = preferences.getString(
                KEY_SNOOZE_DIRECTION, "");
        if (!snoozedDirection.isEmpty()
                && !snoozedDirection.equals(normalized)) {
            return false;
        }
        long snoozedAnchor = preferences.getLong(KEY_SNOOZE_ANCHOR, 0L);
        return anchorMs >= snoozedAnchor;
    }

    void clearSnooze() {
        preferences.edit()
                .remove(KEY_SNOOZE_UNTIL)
                .remove(KEY_SNOOZE_DIRECTION)
                .remove(KEY_SNOOZE_ANCHOR)
                .apply();
    }

    private boolean effectiveEnabled() {
        boolean enabled = preferences.getBoolean(KEY_ENABLED, false);
        if (enabled && !PredictiveAlertNotifier.supportsExpiringAlerts()) {
            // Pre-O notifications cannot be given a platform-enforced expiry.
            // Persist the fail-closed state so legacy early warnings remain on.
            preferences.edit().putBoolean(KEY_ENABLED, false).apply();
            return false;
        }
        return enabled;
    }

    /** Copies the former shared policy into both direction-specific slots. */
    private void migrateSharedDirectionPolicy() {
        if (preferences.contains(KEY_LOW_SENSITIVITY)
                && preferences.contains(KEY_HIGH_SENSITIVITY)
                && preferences.contains(KEY_LOW_COOLDOWN)
                && preferences.contains(KEY_HIGH_COOLDOWN)) {
            return;
        }
        int sharedSensitivity = validSensitivity(preferences.getInt(
                KEY_SENSITIVITY, SENSITIVITY_BALANCED));
        int sharedCooldown = validOption(preferences.getInt(KEY_COOLDOWN,
                        DEFAULT_COOLDOWN_MINUTES), COOLDOWN_OPTIONS_MINUTES,
                DEFAULT_COOLDOWN_MINUTES);
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(KEY_LOW_SENSITIVITY)) {
            editor.putInt(KEY_LOW_SENSITIVITY, sharedSensitivity);
        }
        if (!preferences.contains(KEY_HIGH_SENSITIVITY)) {
            editor.putInt(KEY_HIGH_SENSITIVITY, sharedSensitivity);
        }
        if (!preferences.contains(KEY_LOW_COOLDOWN)) {
            editor.putInt(KEY_LOW_COOLDOWN, sharedCooldown);
        }
        if (!preferences.contains(KEY_HIGH_COOLDOWN)) {
            editor.putInt(KEY_HIGH_COOLDOWN, sharedCooldown);
        }
        editor.commit();
    }

    private int directionSensitivity(boolean lowDirection) {
        int shared = validSensitivity(preferences.getInt(KEY_SENSITIVITY,
                SENSITIVITY_BALANCED));
        return validSensitivity(preferences.getInt(lowDirection
                ? KEY_LOW_SENSITIVITY : KEY_HIGH_SENSITIVITY, shared));
    }

    private int directionCooldownMinutes(boolean lowDirection) {
        int shared = validOption(preferences.getInt(KEY_COOLDOWN,
                        DEFAULT_COOLDOWN_MINUTES), COOLDOWN_OPTIONS_MINUTES,
                DEFAULT_COOLDOWN_MINUTES);
        return validOption(preferences.getInt(lowDirection
                        ? KEY_LOW_COOLDOWN : KEY_HIGH_COOLDOWN, shared),
                COOLDOWN_OPTIONS_MINUTES, shared);
    }

    private void migrateNativeTargetOnce() {
        if (preferences.getBoolean(KEY_TARGET_MIGRATED, false)
                || !Applic.Nativesloaded) {
            return;
        }
        try {
            if (Natives.getunit() == 1) {
                Natives.setTargetRange(TARGET_LOW_MMOL_L, TARGET_HIGH_MMOL_L);
            } else {
                Natives.setTargetRange(TARGET_LOW_MG_DL,
                        TARGET_HIGH_MG_DL);
            }
            preferences.edit().putBoolean(KEY_TARGET_MIGRATED, true).commit();
        } catch (Throwable error) {
            Log.stack("PredictiveAlerts", "migrateNativeTargetOnce", error);
        }
    }

    /**
     * Applies a runtime-only, per-direction handoff. The coordinator is the
     * sole positive owner of this gate; no saved native alarm setting changes.
     */
    void setLegacyPredictionSuppression(boolean suppressLow,
            boolean suppressHigh) {
        setLegacyPredictionSuppression(suppressLow, suppressHigh, 0L);
    }

    void setLegacyPredictionSuppression(boolean suppressLow,
            boolean suppressHigh, long expiresAtMs) {
        if (!Applic.Nativesloaded) return;
        try {
            Natives.setSuppressLegacyPredictionAlarms(suppressLow,
                    suppressHigh, Math.max(0L, expiresAtMs));
        } catch (Throwable error) {
            Log.stack("PredictiveAlerts", "setLegacyPredictionSuppression",
                    error);
        }
    }

    private static int validSensitivity(int value) {
        return value >= SENSITIVITY_EARLY && value <= SENSITIVITY_FEWER
                ? value : SENSITIVITY_BALANCED;
    }

    private static int validOption(int value, int[] options, int fallback) {
        for (int option : options) {
            if (value == option) return value;
        }
        return fallback;
    }

    private static String normalizedDirection(String direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Alert direction is required");
        }
        String normalized = direction.trim().toLowerCase(Locale.ROOT);
        if (DIRECTION_LOW.equals(normalized) || DIRECTION_HIGH.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unknown alert direction: " + direction);
    }

    private static String normalizedEvidence(String evidence) {
        if (evidence == null) return "";
        String normalized = evidence.trim().toLowerCase(Locale.ROOT);
        return EVIDENCE_POSSIBLE.equals(normalized)
                || EVIDENCE_LIKELY.equals(normalized) ? normalized : "";
    }

    static final class Snapshot {
        final boolean enabled;
        final boolean lowEnabled;
        final boolean highEnabled;
        final int lowHorizonMinutes;
        final int highHorizonMinutes;
        final int lowSensitivity;
        final int highSensitivity;
        final int lowCooldownMinutes;
        final int highCooldownMinutes;
        /** Compatibility alias for callers that still use one shared value. */
        final int sensitivity;
        /** Compatibility alias for callers that still use one shared value. */
        final int cooldownMinutes;

        Snapshot(boolean enabled, boolean lowEnabled, boolean highEnabled,
                int lowHorizonMinutes, int highHorizonMinutes,
                int sensitivity, int cooldownMinutes) {
            this(enabled, lowEnabled, highEnabled, lowHorizonMinutes,
                    highHorizonMinutes, sensitivity, sensitivity,
                    cooldownMinutes, cooldownMinutes);
        }

        Snapshot(boolean enabled, boolean lowEnabled, boolean highEnabled,
                int lowHorizonMinutes, int highHorizonMinutes,
                int lowSensitivity, int highSensitivity,
                int lowCooldownMinutes, int highCooldownMinutes) {
            this.enabled = enabled;
            this.lowEnabled = lowEnabled;
            this.highEnabled = highEnabled;
            this.lowHorizonMinutes = lowHorizonMinutes;
            this.highHorizonMinutes = highHorizonMinutes;
            this.lowSensitivity = validSensitivity(lowSensitivity);
            this.highSensitivity = validSensitivity(highSensitivity);
            this.lowCooldownMinutes = validOption(lowCooldownMinutes,
                    COOLDOWN_OPTIONS_MINUTES, DEFAULT_COOLDOWN_MINUTES);
            this.highCooldownMinutes = validOption(highCooldownMinutes,
                    COOLDOWN_OPTIONS_MINUTES, DEFAULT_COOLDOWN_MINUTES);
            this.sensitivity = this.lowSensitivity;
            this.cooldownMinutes = this.lowCooldownMinutes;
        }

        int sensitivityFor(boolean lowDirection) {
            return lowDirection ? lowSensitivity : highSensitivity;
        }

        int cooldownMinutesFor(boolean lowDirection) {
            return lowDirection ? lowCooldownMinutes : highCooldownMinutes;
        }

        int horizonMinutesFor(boolean lowDirection) {
            return lowDirection ? lowHorizonMinutes : highHorizonMinutes;
        }
    }
}
