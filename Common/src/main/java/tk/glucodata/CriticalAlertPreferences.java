package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-alert delivery preferences shared by settings and the alarm runtime. */
final class CriticalAlertPreferences {
    static final String PREFS_NAME = "critical_alert_delivery_v1";
    static final int DEFAULT_MINIMUM_VOLUME_PERCENT = 70;

    private static final int[] MINIMUM_VOLUME_OPTIONS = {70, 85, 100};
    private static final String KEY_VOLUME_PREFIX = "minimum_volume_";

    private CriticalAlertPreferences() {}

    static int[] volumeOptions() {
        return MINIMUM_VOLUME_OPTIONS.clone();
    }

    static int getMinimumVolumePercent(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        CriticalAlarmSoundCatalog.AlertType safeType = alertType == null
                ? CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW : alertType;
        if (context == null) return DEFAULT_MINIMUM_VOLUME_PERCENT;
        int saved = preferences(context).getInt(
                KEY_VOLUME_PREFIX + safeType.stableId(),
                DEFAULT_MINIMUM_VOLUME_PERCENT);
        return validMinimumVolumePercent(saved)
                ? saved : DEFAULT_MINIMUM_VOLUME_PERCENT;
    }

    static boolean setMinimumVolumePercent(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType, int percent) {
        if (context == null || alertType == null
                || !validMinimumVolumePercent(percent)) return false;
        preferences(context).edit().putInt(
                KEY_VOLUME_PREFIX + alertType.stableId(), percent).apply();
        return true;
    }

    /** Compatibility aliases for early callers of the unified settings API. */
    static int[] minimumVolumeOptions() {
        return volumeOptions();
    }

    static int minimumVolumePercent(Context context,
            CriticalAlarmSoundCatalog.AlertType alertType) {
        return getMinimumVolumePercent(context, alertType);
    }

    static boolean validMinimumVolumePercent(int percent) {
        for (int option : MINIMUM_VOLUME_OPTIONS) {
            if (option == percent) return true;
        }
        return false;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }
}
