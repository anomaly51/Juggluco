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
    private static final String KEY_LAST_ALERT_PREFIX = "last_alert_at_";
    private static final String KEY_ACTIVE_EPISODE_DIRECTION =
            "active_episode_direction";
    private static final String KEY_ACTIVE_ANCHOR = "active_anchor_ms";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until_ms";
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
        migrateNativeTargetOnce();
    }

    Snapshot snapshot() {
        // A repository may be constructed before the native library has
        // finished loading. Retrying here makes the one-time migration robust.
        migrateNativeTargetOnce();
        boolean enabled = effectiveEnabled();
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
                validSensitivity(preferences.getInt(KEY_SENSITIVITY,
                        SENSITIVITY_BALANCED)),
                validOption(preferences.getInt(KEY_COOLDOWN,
                                DEFAULT_COOLDOWN_MINUTES),
                        COOLDOWN_OPTIONS_MINUTES, DEFAULT_COOLDOWN_MINUTES));
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

    void setSensitivity(int sensitivity) {
        preferences.edit().putInt(KEY_SENSITIVITY,
                validSensitivity(sensitivity)).apply();
    }

    void setCooldownMinutes(int minutes) {
        preferences.edit().putInt(KEY_COOLDOWN,
                validOption(minutes, COOLDOWN_OPTIONS_MINUTES,
                        DEFAULT_COOLDOWN_MINUTES)).apply();
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

    void recordAlert(String direction, long atMs, long anchorMs) {
        String normalized = normalizedDirection(direction);
        preferences.edit()
                .putLong(KEY_LAST_ALERT_PREFIX + normalized, Math.max(0L, atMs))
                .putString(KEY_ACTIVE_EPISODE_DIRECTION, normalized)
                .putLong(KEY_ACTIVE_ANCHOR, Math.max(0L, anchorMs))
                .apply();
    }

    void clearEpisode() {
        preferences.edit()
                .remove(KEY_ACTIVE_EPISODE_DIRECTION)
                .remove(KEY_ACTIVE_ANCHOR)
                .apply();
    }

    void clearDeliveryState() {
        preferences.edit()
                .remove(KEY_LAST_ALERT_PREFIX + DIRECTION_LOW)
                .remove(KEY_LAST_ALERT_PREFIX + DIRECTION_HIGH)
                .remove(KEY_ACTIVE_EPISODE_DIRECTION)
                .remove(KEY_ACTIVE_ANCHOR)
                .remove(KEY_SNOOZE_UNTIL)
                .apply();
    }

    void setSnoozeUntil(long atMs) {
        if (atMs <= 0L) {
            preferences.edit().remove(KEY_SNOOZE_UNTIL).apply();
        } else {
            preferences.edit().putLong(KEY_SNOOZE_UNTIL, atMs).apply();
        }
    }

    long snoozeUntil() {
        return preferences.getLong(KEY_SNOOZE_UNTIL, 0L);
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

    static final class Snapshot {
        final boolean enabled;
        final boolean lowEnabled;
        final boolean highEnabled;
        final int lowHorizonMinutes;
        final int highHorizonMinutes;
        final int sensitivity;
        final int cooldownMinutes;

        Snapshot(boolean enabled, boolean lowEnabled, boolean highEnabled,
                int lowHorizonMinutes, int highHorizonMinutes,
                int sensitivity, int cooldownMinutes) {
            this.enabled = enabled;
            this.lowEnabled = lowEnabled;
            this.highEnabled = highEnabled;
            this.lowHorizonMinutes = lowHorizonMinutes;
            this.highHorizonMinutes = highHorizonMinutes;
            this.sensitivity = sensitivity;
            this.cooldownMinutes = cooldownMinutes;
        }
    }
}
