package tk.glucodata;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/** One immutable CGM sample sent to the user-owned prediction backend. */
final class ForecastReading {
    final String readingId;
    final long measuredAtMs;
    final int glucoseMgDl;
    final Float trendMgDlMin;
    final String sensorId;
    final String sensorGeneration;
    final Float quality;
    final int utcOffsetMinutes;

    ForecastReading(String readingId, long measuredAtMs, int glucoseMgDl,
            Float trendMgDlMin, String sensorId, String sensorGeneration,
            Float quality) {
        if (measuredAtMs <= 0L || glucoseMgDl < 20 || glucoseMgDl > 600) {
            throw new IllegalArgumentException("Invalid glucose reading");
        }
        this.readingId = clean(readingId).isEmpty()
                ? stableId(measuredAtMs, glucoseMgDl) : clean(readingId);
        this.measuredAtMs = measuredAtMs;
        this.glucoseMgDl = glucoseMgDl;
        this.trendMgDlMin = finite(trendMgDlMin)
                && trendMgDlMin >= -30f && trendMgDlMin <= 30f
                ? trendMgDlMin : null;
        this.sensorId = clean(sensorId);
        this.sensorGeneration = clean(sensorGeneration);
        this.quality = finite(quality) && quality >= 0f && quality <= 1f
                ? quality : null;
        this.utcOffsetMinutes = TimeZone.getDefault()
                .getOffset(measuredAtMs) / 60_000;
    }

    static ForecastReading live(String sensorId, int sensorGeneration,
            long measuredAtMs, int glucoseMgDl, float trendMgDlMin) {
        return new ForecastReading(stableId(measuredAtMs, glucoseMgDl),
                measuredAtMs, glucoseMgDl, trendMgDlMin,
                sensorId, String.valueOf(sensorGeneration), 1f);
    }

    static ForecastReading historical(long measuredAtMs, int glucoseMgDl,
            float trendMgDlMin) {
        return new ForecastReading(stableId(measuredAtMs, glucoseMgDl),
                measuredAtMs, glucoseMgDl, trendMgDlMin,
                "", "", null);
    }

    JSONObject toJson() throws JSONException {
        return toJson(true);
    }

    JSONObject toJson(boolean includeUtcOffset) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("reading_id", readingId);
        value.put("measured_at_ms", measuredAtMs);
        value.put("glucose_mg_dl", glucoseMgDl);
        if (includeUtcOffset) {
            value.put("utc_offset_minutes", utcOffsetMinutes);
        }
        if (trendMgDlMin != null) {
            value.put("trend_mg_dl_min", trendMgDlMin.doubleValue());
        }
        if (!sensorId.isEmpty()) value.put("sensor_id", sensorId);
        if (!sensorGeneration.isEmpty()) {
            value.put("sensor_generation", sensorGeneration);
        }
        if (quality != null) value.put("quality", quality.doubleValue());
        return value;
    }

    private static String stableId(long measuredAtMs, int glucoseMgDl) {
        // The same identity is used for live and native-history paths, making a
        // retry or later backfill harmless at the backend.
        return String.format(Locale.ROOT, "cgm-%d", measuredAtMs);
    }

    private static boolean finite(Float value) {
        return value != null && !Float.isNaN(value)
                && !Float.isInfinite(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
