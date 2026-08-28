package tk.glucodata;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stable, built-in sound choices for every critical glucose alert type.
 *
 * <p>Preferences contain catalog ids rather than generated {@code R.raw}
 * integers, so selections survive resource-table changes between releases.
 * Existing users keep the historic per-alert defaults until they explicitly
 * choose another sound.</p>
 */
public final class CriticalAlarmSoundCatalog {
    static final String PREFS_NAME = "critical_alarm_sounds_v1";

    private static final String SOURCE_ACTUAL = "actual";
    private static final String DIRECTION_LOW = "low";
    private static final long PREVIEW_LIMIT_MS = 8_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AudioAttributes PREVIEW_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

    private static final Tone[] BUILT_IN_TONES = {
            new Tone("siren", R.raw.siren,
                    R.string.critical_alarm_sound_siren, Category.URGENT),
            new Tone("classic", R.raw.classic,
                    R.string.critical_alarm_sound_classic, Category.URGENT),
            new Tone("ghost", R.raw.ghost,
                    R.string.critical_alarm_sound_ghost, Category.DIGITAL),
            new Tone("nudge", R.raw.nudge,
                    R.string.critical_alarm_sound_nudge, Category.DIGITAL),
            new Tone("elves", R.raw.elves,
                    R.string.critical_alarm_sound_elves, Category.CHIMES),
            new Tone("very_low", R.raw.verylow,
                    R.string.critical_alarm_sound_very_low, Category.URGENT),
            new Tone("very_high", R.raw.veryhigh,
                    R.string.critical_alarm_sound_very_high, Category.URGENT),
            new Tone("low_soon", R.raw.lowsoon,
                    R.string.critical_alarm_sound_low_soon, Category.MELODIC),
            new Tone("high_soon", R.raw.highsoon,
                    R.string.critical_alarm_sound_high_soon, Category.MELODIC),

            new Tone("urgent_pulse", R.raw.alert_urgent_pulse,
                    R.string.critical_alarm_sound_urgent_pulse, Category.URGENT),
            new Tone("air_horn", R.raw.alert_air_horn,
                    R.string.critical_alarm_sound_air_horn, Category.URGENT),
            new Tone("rapid_beacon", R.raw.alert_rapid_beacon,
                    R.string.critical_alarm_sound_rapid_beacon, Category.URGENT),
            new Tone("rising_alarm", R.raw.alert_rising_alarm,
                    R.string.critical_alarm_sound_rising_alarm, Category.URGENT),
            new Tone("double_knock", R.raw.alert_double_knock,
                    R.string.critical_alarm_sound_double_knock, Category.GENTLE),

            new Tone("crystal_bells", R.raw.alert_crystal_bells,
                    R.string.critical_alarm_sound_crystal_bells, Category.CHIMES),
            new Tone("door_chime", R.raw.alert_door_chime,
                    R.string.critical_alarm_sound_door_chime, Category.CHIMES),
            new Tone("temple_bowl", R.raw.alert_temple_bowl,
                    R.string.critical_alarm_sound_temple_bowl, Category.CHIMES),
            new Tone("glass_drops", R.raw.alert_glass_drops,
                    R.string.critical_alarm_sound_glass_drops, Category.CHIMES),
            new Tone("music_box", R.raw.alert_music_box,
                    R.string.critical_alarm_sound_music_box, Category.CHIMES),
            new Tone("wind_chimes", R.raw.alert_wind_chimes,
                    R.string.critical_alarm_sound_wind_chimes, Category.CHIMES),

            new Tone("sonar_ping", R.raw.alert_sonar_ping,
                    R.string.critical_alarm_sound_sonar_ping, Category.DIGITAL),
            new Tone("radar_sweep", R.raw.alert_radar_sweep,
                    R.string.critical_alarm_sound_radar_sweep, Category.DIGITAL),
            new Tone("pixel_jump", R.raw.alert_pixel_jump,
                    R.string.critical_alarm_sound_pixel_jump, Category.DIGITAL),
            new Tone("retro_game", R.raw.alert_retro_game,
                    R.string.critical_alarm_sound_retro_game, Category.DIGITAL),
            new Tone("signal_code", R.raw.alert_signal_code,
                    R.string.critical_alarm_sound_signal_code, Category.DIGITAL),
            new Tone("neon_wave", R.raw.alert_neon_wave,
                    R.string.critical_alarm_sound_neon_wave, Category.DIGITAL),

            new Tone("bright_marimba", R.raw.alert_bright_marimba,
                    R.string.critical_alarm_sound_bright_marimba, Category.MELODIC),
            new Tone("piano_steps", R.raw.alert_piano_steps,
                    R.string.critical_alarm_sound_piano_steps, Category.MELODIC),
            new Tone("sunrise", R.raw.alert_sunrise,
                    R.string.critical_alarm_sound_sunrise, Category.MELODIC),
            new Tone("major_arpeggio", R.raw.alert_major_arpeggio,
                    R.string.critical_alarm_sound_major_arpeggio, Category.MELODIC),
            new Tone("minor_arpeggio", R.raw.alert_minor_arpeggio,
                    R.string.critical_alarm_sound_minor_arpeggio, Category.MELODIC),

            new Tone("soft_pop", R.raw.alert_soft_pop,
                    R.string.critical_alarm_sound_soft_pop, Category.GENTLE),
            new Tone("rain_drops", R.raw.alert_rain_drops,
                    R.string.critical_alarm_sound_rain_drops, Category.GENTLE),
            new Tone("wood_tap", R.raw.alert_wood_tap,
                    R.string.critical_alarm_sound_wood_tap, Category.GENTLE),
            new Tone("double_beat", R.raw.alert_double_beat,
                    R.string.critical_alarm_sound_double_beat, Category.GENTLE),
            new Tone("calm_chord", R.raw.alert_calm_chord,
                    R.string.critical_alarm_sound_calm_chord, Category.GENTLE)
    };
    private static final List<Tone> TONES = Collections.unmodifiableList(
            Arrays.asList(BUILT_IN_TONES));

    private static Ringtone previewRingtone;
    private static Runnable previewTimeout;

    private CriticalAlarmSoundCatalog() {}

    public enum AlertType {
        ACTUAL_LOW("actual_low", "very_low"),
        ACTUAL_HIGH("actual_high", "very_high"),
        PREDICTIVE_LOW("predictive_low", "low_soon"),
        PREDICTIVE_HIGH("predictive_high", "high_soon"),
        SIGNAL_LOSS("signal_loss", "siren");

        private final String preferenceKey;
        private final String defaultToneId;

        AlertType(String preferenceKey, String defaultToneId) {
            this.preferenceKey = preferenceKey;
            this.defaultToneId = defaultToneId;
        }

        String stableId() {
            return preferenceKey;
        }

        static AlertType fromStableId(String stableId) {
            if (stableId != null) {
                for (AlertType type : values()) {
                    if (type.preferenceKey.equals(stableId)) return type;
                }
            }
            return null;
        }
    }

    /** Stable visual groups used by the sound-library picker. */
    public enum Category {
        URGENT(R.string.critical_alarm_sound_category_urgent),
        CHIMES(R.string.critical_alarm_sound_category_chimes),
        DIGITAL(R.string.critical_alarm_sound_category_digital),
        MELODIC(R.string.critical_alarm_sound_category_melodic),
        GENTLE(R.string.critical_alarm_sound_category_gentle);

        public final int labelRes;

        Category(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** Immutable catalog item safe to bind directly to a settings list. */
    public static final class Tone {
        public final String id;
        public final int soundRes;
        public final int labelRes;
        public final Category category;
        public final long durationMs;

        private Tone(String id, int soundRes, int labelRes,
                Category category) {
            this.id = id;
            this.soundRes = soundRes;
            this.labelRes = labelRes;
            this.category = category;
            this.durationMs = declaredDurationMs(id);
        }
    }

    public static List<Tone> tones() {
        return TONES;
    }

    /**
     * Integration boundary used by the critical alarm controller.
     * Test alerts intentionally preview the corresponding predictive choice.
     */
    public static int selectedSoundRes(Context context, String source,
            String direction) {
        return selectedSoundRes(context, alertType(source, direction));
    }

    public static int selectedSoundRes(Context context, AlertType alertType) {
        return selectedTone(context, alertType).soundRes;
    }

    /** Resolves a stable catalog id for dynamic notification-channel setup. */
    public static int soundRes(String toneId) {
        Tone tone = find(toneId);
        return tone == null ? 0 : tone.soundRes;
    }

    /** Exact encoded duration used by pre-API 28 replay scheduling. */
    public static long durationMs(int soundRes) {
        for (Tone tone : BUILT_IN_TONES) {
            if (tone.soundRes == soundRes) return tone.durationMs;
        }
        return 8_500L;
    }

    public static String selectedToneId(Context context, AlertType alertType) {
        return selectedTone(context, alertType).id;
    }

    public static CharSequence selectedLabel(Context context,
            AlertType alertType) {
        return label(context, selectedToneId(context, alertType));
    }

    public static CharSequence label(Context context, String toneId) {
        Tone tone = find(toneId);
        if (context == null || tone == null) return "";
        return context.getString(tone.labelRes);
    }

    /** Saves only recognized built-in ids; invalid input leaves the old choice. */
    public static boolean select(Context context, AlertType alertType,
            String toneId) {
        if (context == null || alertType == null || find(toneId) == null) {
            return false;
        }
        preferences(context).edit()
                .putString(alertType.preferenceKey, toneId)
                .apply();
        return true;
    }

    /** Plays a bounded alarm-stream sample without starting an alarm episode. */
    static synchronized boolean preview(Context context, String toneId) {
        Tone tone = find(toneId);
        if (context == null || tone == null) return false;
        stopPreviewLocked();
        try {
            Context app = context.getApplicationContext();
            Ringtone candidate = RingtoneManager.getRingtone(app,
                    resourceUri(app, tone.soundRes));
            if (candidate == null) return false;
            candidate.setAudioAttributes(PREVIEW_ATTRIBUTES);
            previewRingtone = candidate;
            candidate.play();
            previewTimeout = CriticalAlarmSoundCatalog::stopPreview;
            MAIN.postDelayed(previewTimeout, PREVIEW_LIMIT_MS);
            return true;
        } catch (Throwable failure) {
            stopPreviewLocked();
            Log.stack("CriticalAlarmSounds", "preview", failure);
            return false;
        }
    }

    public static synchronized void stopPreview() {
        stopPreviewLocked();
    }

    public static AlertType alertType(String source, String direction) {
        if ("signal_loss".equals(source)) return AlertType.SIGNAL_LOSS;
        boolean low = DIRECTION_LOW.equals(direction);
        if (SOURCE_ACTUAL.equals(source)) {
            return low ? AlertType.ACTUAL_LOW : AlertType.ACTUAL_HIGH;
        }
        // Predictive and explicit test sessions share the forecast choices.
        // Unknown sources fail safely to the high predictive default instead
        // of ever returning an invalid resource id.
        return low ? AlertType.PREDICTIVE_LOW : AlertType.PREDICTIVE_HIGH;
    }

    private static Tone selectedTone(Context context, AlertType alertType) {
        AlertType safeType = alertType == null
                ? AlertType.PREDICTIVE_HIGH : alertType;
        String selected = context == null ? safeType.defaultToneId
                : preferences(context).getString(safeType.preferenceKey,
                        safeType.defaultToneId);
        Tone tone = find(selected);
        if (tone != null) return tone;
        Tone fallback = find(safeType.defaultToneId);
        if (fallback != null) return fallback;
        return BUILT_IN_TONES[0];
    }

    private static Tone find(String toneId) {
        if (toneId == null) return null;
        for (Tone tone : BUILT_IN_TONES) {
            if (tone.id.equals(toneId)) return tone;
        }
        return null;
    }

    private static long declaredDurationMs(String toneId) {
        switch (toneId) {
            case "classic": return 8_020L;
            case "elves": return 4_120L;
            case "ghost": return 8_920L;
            case "nudge": return 6_380L;
            case "siren": return 15_740L;
            case "high_soon": return 7_730L;
            case "low_soon": return 9_430L;
            case "very_high": return 4_900L;
            case "very_low": return 7_750L;
            case "urgent_pulse": return 3_600L;
            case "air_horn": return 3_450L;
            case "rapid_beacon": return 3_400L;
            case "rising_alarm": return 3_700L;
            case "double_knock": return 3_600L;
            case "crystal_bells": return 4_100L;
            case "door_chime": return 4_000L;
            case "temple_bowl": return 4_800L;
            case "glass_drops": return 4_000L;
            case "music_box": return 4_250L;
            case "wind_chimes": return 4_700L;
            case "sonar_ping": return 4_200L;
            case "radar_sweep": return 4_000L;
            case "pixel_jump": return 3_800L;
            case "retro_game": return 4_200L;
            case "signal_code": return 4_100L;
            case "neon_wave": return 4_000L;
            case "bright_marimba": return 4_200L;
            case "piano_steps": return 4_400L;
            case "sunrise": return 4_800L;
            case "major_arpeggio": return 4_000L;
            case "minor_arpeggio": return 4_000L;
            case "soft_pop": return 4_000L;
            case "rain_drops": return 4_300L;
            case "wood_tap": return 4_000L;
            case "double_beat": return 4_400L;
            case "calm_chord": return 4_800L;
            default: return 8_500L;
        }
    }

    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Uri resourceUri(Context context, int soundRes) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + context.getPackageName() + "/" + soundRes);
    }

    private static void stopPreviewLocked() {
        if (previewTimeout != null) {
            MAIN.removeCallbacks(previewTimeout);
            previewTimeout = null;
        }
        if (previewRingtone != null) {
            try {
                previewRingtone.stop();
            } catch (Throwable ignored) {
                // A disappearing audio route must not break the settings UI.
            }
            previewRingtone = null;
        }
    }
}
