package tk.glucodata;

import org.json.JSONObject;

/** Optional training/data/accuracy metadata; unknown backend fields are ignored. */
final class ForecastModelStatus {
    final String status;
    final String serverInstanceId;
    final String modelVersion;
    final String trainingState;
    final long lastTrainedAtMs;
    final long nextEligibleAtMs;
    final long sampleCount;
    final long minimumSamples;
    final long readingCount;
    final double daysCovered;
    final long confirmedMeals;
    final long rapidEvents;
    final long longEvents;
    final long lastReadingAtMs;
    final long scoredPoints;
    final Double mae30;
    final Double mae60;
    final Double mae120;
    final Double mae7d;
    final Double mae30d;
    final Double coverage80;
    final AccuracyWindow accuracy7d;
    final AccuracyWindow accuracy30d;

    static final class AccuracyWindow {
        final long scoredPoints;
        final Double mae30;
        final Double mae60;
        final Double mae120;

        AccuracyWindow(long scoredPoints, Double mae30, Double mae60,
                Double mae120) {
            this.scoredPoints = scoredPoints;
            this.mae30 = mae30;
            this.mae60 = mae60;
            this.mae120 = mae120;
        }

        boolean hasValues() {
            return scoredPoints > 0L || mae30 != null || mae60 != null
                    || mae120 != null;
        }
    }

    private ForecastModelStatus(String status, String serverInstanceId,
            String modelVersion,
            String trainingState, long lastTrainedAtMs, long nextEligibleAtMs,
            long sampleCount, long minimumSamples, long readingCount,
            double daysCovered, long confirmedMeals, long rapidEvents,
            long longEvents, long lastReadingAtMs, long scoredPoints,
            Double mae30, Double mae60, Double mae120, Double coverage80,
            Double mae7d, Double mae30d, AccuracyWindow accuracy7d,
            AccuracyWindow accuracy30d) {
        this.status = clean(status);
        this.serverInstanceId = clean(serverInstanceId);
        this.modelVersion = clean(modelVersion);
        this.trainingState = clean(trainingState);
        this.lastTrainedAtMs = lastTrainedAtMs;
        this.nextEligibleAtMs = nextEligibleAtMs;
        this.sampleCount = sampleCount;
        this.minimumSamples = minimumSamples;
        this.readingCount = readingCount;
        this.daysCovered = finite(daysCovered) ? daysCovered : 0d;
        this.confirmedMeals = confirmedMeals;
        this.rapidEvents = rapidEvents;
        this.longEvents = longEvents;
        this.lastReadingAtMs = lastReadingAtMs;
        this.scoredPoints = scoredPoints;
        this.mae30 = mae30;
        this.mae60 = mae60;
        this.mae120 = mae120;
        this.mae7d = mae7d;
        this.mae30d = mae30d;
        this.coverage80 = coverage80;
        this.accuracy7d = accuracy7d;
        this.accuracy30d = accuracy30d;
    }

    static ForecastModelStatus empty() {
        return fromJson(new JSONObject());
    }

    static ForecastModelStatus fromJson(JSONObject root) {
        if (root == null) root = new JSONObject();
        JSONObject training = object(root, "training");
        JSONObject data = object(root, "data");
        JSONObject accuracy = object(root, "accuracy");
        AccuracyWindow seven = accuracyWindow(root.optJSONObject("accuracy_7d"));
        AccuracyWindow thirty = accuracyWindow(root.optJSONObject("accuracy_30d"));
        return new ForecastModelStatus(root.optString("status", "unknown"),
                root.optString("server_instance_id", ""),
                root.optString("model_version", ""),
                training.optString("state", root.optString("training_state", "")),
                training.optLong("last_trained_at_ms",
                        root.optLong("last_trained_at_ms", 0L)),
                training.optLong("next_eligible_at_ms", 0L),
                training.optLong("sample_count", 0L),
                training.optLong("minimum_samples", 0L),
                data.optLong("reading_count", root.optLong("reading_count", 0L)),
                finiteDouble(data, "days_covered", 0d),
                data.optLong("confirmed_meals", 0L),
                data.optLong("rapid_events", 0L),
                data.optLong("long_events", 0L),
                data.optLong("last_reading_at_ms", 0L),
                accuracy.optLong("scored_points", 0L),
                optionalDouble(accuracy, "mae_30_mg_dl"),
                optionalDouble(accuracy, "mae_60_mg_dl"),
                optionalDouble(accuracy, "mae_120_mg_dl"),
                optionalDouble(accuracy, "coverage_80"),
                optionalDouble(accuracy, "mae_7d_mg_dl"),
                optionalDouble(accuracy, "mae_30d_mg_dl"), seven, thirty);
    }

    private static AccuracyWindow accuracyWindow(JSONObject value) {
        if (value == null) return new AccuracyWindow(0L, null, null, null);
        return new AccuracyWindow(value.optLong("scored_points", 0L),
                firstDouble(value, "mae_30_mg_dl", "mae_30"),
                firstDouble(value, "mae_60_mg_dl", "mae_60"),
                firstDouble(value, "mae_120_mg_dl", "mae_120"));
    }

    private static JSONObject object(JSONObject root, String name) {
        JSONObject value = root.optJSONObject(name);
        return value == null ? new JSONObject() : value;
    }

    private static Double firstDouble(JSONObject value, String first,
            String second) {
        Double result = optionalDouble(value, first);
        return result == null ? optionalDouble(value, second) : result;
    }

    private static Double optionalDouble(JSONObject value, String name) {
        if (value == null || !value.has(name) || value.isNull(name)) return null;
        double number = value.optDouble(name, Double.NaN);
        return finite(number) ? number : null;
    }

    private static double finiteDouble(JSONObject value, String name,
            double fallback) {
        Double number = optionalDouble(value, name);
        return number == null ? fallback : number;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
