package tk.glucodata;

import android.content.Context;

/**
 * Shared presentation rules for an estimated carbohydrate-absorption speed.
 *
 * <p>The backend value is continuous ({@code 0..1}). The UI deliberately
 * presents it as an approximate 0..100 speed score, not as a measured
 * glycemic index.</p>
 */
final class CarbAbsorptionUi {
    enum Band { NOT_ESTIMATED, SLOW, MEDIUM, FAST }

    private CarbAbsorptionUi() {}

    /** Returns 0..100, or -1 when there is no valid estimate. */
    static int index(Float speed) {
        if (!isUnitValue(speed)) return -1;
        return Math.round(speed * 100.0f);
    }

    static Band band(Float speed) {
        int value = index(speed);
        if (value < 0) return Band.NOT_ESTIMATED;
        if (value <= 33) return Band.SLOW;
        if (value <= 66) return Band.MEDIUM;
        return Band.FAST;
    }

    /** Localized compact value such as "Fast · 78/100". */
    static String compact(Context context, Float speed) {
        int value = index(speed);
        if (value < 0) {
            return context.getString(R.string.carb_absorption_not_estimated);
        }
        return context.getString(R.string.carb_absorption_value,
                context.getString(bandLabel(band(speed))), value);
    }

    /**
     * Localized two-line summary. Timing and confidence are shown only when
     * the backend actually supplied valid values.
     */
    static String details(Context context, Float speed, Integer peakMinutes,
            Integer durationMinutes, Float confidence) {
        return context.getString(R.string.carb_absorption_summary,
                valueDetails(context, speed, peakMinutes, durationMinutes,
                        confidence));
    }

    /** Value and optional evidence, for a view that already has a heading. */
    static String valueDetails(Context context, Float speed,
            Integer peakMinutes, Integer durationMinutes, Float confidence) {
        StringBuilder value = new StringBuilder(compact(context, speed));
        StringBuilder metadata = new StringBuilder();
        append(metadata, validMinutes(peakMinutes)
                ? context.getString(R.string.carb_absorption_peak,
                        peakMinutes) : null);
        append(metadata, validMinutes(durationMinutes)
                ? context.getString(R.string.carb_absorption_duration,
                        durationMinutes) : null);
        append(metadata, isUnitValue(confidence)
                ? context.getString(R.string.carb_absorption_confidence,
                        Math.round(confidence * 100.0f)) : null);
        if (metadata.length() > 0) value.append('\n').append(metadata);
        return value.toString();
    }

    static int bandLabel(Band band) {
        if (band == Band.SLOW) return R.string.carb_absorption_slow;
        if (band == Band.MEDIUM) return R.string.carb_absorption_medium;
        if (band == Band.FAST) return R.string.carb_absorption_fast;
        return R.string.carb_absorption_not_estimated;
    }

    private static void append(StringBuilder target, String part) {
        if (part == null || part.isEmpty()) return;
        if (target.length() > 0) target.append(" \u00b7 ");
        target.append(part);
    }

    private static boolean validMinutes(Integer value) {
        return value != null && value > 0;
    }

    private static boolean isUnitValue(Float value) {
        return value != null && Float.isFinite(value)
                && value >= 0.0f && value <= 1.0f;
    }
}
