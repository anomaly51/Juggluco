package tk.glucodata;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded, immutable glucose context captured when a critical alarm is shown.
 *
 * <p>The payload contains only canonical mg/dL values. It is safe to persist in
 * the alarm's private preferences and never performs network work. Rendering
 * code converts units at the final presentation boundary.</p>
 */
final class CriticalDisplayPayload {
    static final long HISTORY_WINDOW_MS = 45L * 60_000L;
    static final long FORECAST_WINDOW_MS = 120L * 60_000L;
    static final int MAX_HISTORY_POINTS = 32;
    static final int MAX_FORECAST_POINTS = 32;

    private static final int SCHEMA_VERSION = 1;
    private static final int HISTORY_QUERY_LIMIT = 256;
    private static final int MAX_SERIALIZED_CHARS = 32_768;
    private static final long FUTURE_CLOCK_SKEW_MS = 60_000L;
    private static final float FORECAST_ANCHOR_TOLERANCE_MG_DL = 2f;
    private static final ExecutorService CAPTURE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "critical-display-capture");
                thread.setDaemon(true);
                return thread;
            });

    static final class HistoryPoint {
        final long atMs;
        final float glucoseMgDl;

        HistoryPoint(long atMs, float glucoseMgDl) {
            this.atMs = atMs;
            this.glucoseMgDl = glucoseMgDl;
        }
    }

    static final class ForecastPoint {
        final long atMs;
        final float medianMgDl;
        final float lowMgDl;
        final float highMgDl;

        ForecastPoint(long atMs, float medianMgDl, float lowMgDl,
                float highMgDl) {
            this.atMs = atMs;
            this.medianMgDl = medianMgDl;
            this.lowMgDl = Math.min(lowMgDl, highMgDl);
            this.highMgDl = Math.max(lowMgDl, highMgDl);
        }
    }

    static final CriticalDisplayPayload EMPTY = new CriticalDisplayPayload(
            0L, 0L, null, null,
            ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL,
            ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL,
            0L, 0L, 0f, Collections.emptyList(),
            Collections.emptyList());

    final long capturedAtMs;
    final long readingAtMs;
    final Float currentMgDl;
    final Float trendMgDlMin;
    final float targetLowMgDl;
    final float targetHighMgDl;
    final long forecastGeneratedAtMs;
    final long crossingAtMs;
    final float forecastConfidence;
    final List<HistoryPoint> history;
    final List<ForecastPoint> forecast;

    private CriticalDisplayPayload(long capturedAtMs, long readingAtMs,
            Float currentMgDl, Float trendMgDlMin, float targetLowMgDl,
            float targetHighMgDl, long forecastGeneratedAtMs,
            long crossingAtMs, float forecastConfidence,
            List<HistoryPoint> history, List<ForecastPoint> forecast) {
        this.capturedAtMs = Math.max(0L, capturedAtMs);
        this.readingAtMs = Math.max(0L, readingAtMs);
        this.currentMgDl = validGlucose(currentMgDl) ? currentMgDl : null;
        this.trendMgDlMin = finite(trendMgDlMin)
                && trendMgDlMin >= -30f && trendMgDlMin <= 30f
                ? trendMgDlMin : null;
        if (validGlucose(targetLowMgDl) && validGlucose(targetHighMgDl)
                && targetLowMgDl < targetHighMgDl) {
            this.targetLowMgDl = targetLowMgDl;
            this.targetHighMgDl = targetHighMgDl;
        } else {
            this.targetLowMgDl = ForecastSnapshot.AlertAssessment
                    .DEFAULT_TARGET_LOW_MG_DL;
            this.targetHighMgDl = ForecastSnapshot.AlertAssessment
                    .DEFAULT_TARGET_HIGH_MG_DL;
        }
        this.forecastGeneratedAtMs = Math.max(0L, forecastGeneratedAtMs);
        long forecastEndMs = safeAdd(this.readingAtMs, FORECAST_WINDOW_MS);
        this.crossingAtMs = crossingAtMs >= this.readingAtMs
                && crossingAtMs <= forecastEndMs ? crossingAtMs : 0L;
        this.forecastConfidence = ForecastSnapshot.clamp01(
                forecastConfidence);
        this.history = Collections.unmodifiableList(new ArrayList<>(
                boundedHistory(history, this.readingAtMs,
                        this.readingAtMs)));
        this.forecast = Collections.unmodifiableList(new ArrayList<>(
                boundedForecast(forecast, this.readingAtMs,
                        this.crossingAtMs)));
    }

    /** Captures current native history and a strictly matching cached forecast. */
    static CriticalDisplayPayload captureActual(Context context,
            long readingAtMs, int currentMgDl, Float trendMgDlMin,
            long nowMs) {
        if (readingAtMs <= 0L || !validGlucose(currentMgDl)) return EMPTY;
        List<ForecastReading> recent = recentReadings(readingAtMs);
        ForecastSnapshot cached = cachedForecast(context);
        return fromActual(recent, cached, readingAtMs, currentMgDl,
                trendMgDlMin, nowMs);
    }

    /**
     * Captures the newest real local sample for the delivery-only test UI.
     * No network refresh is started and no synthetic medical values are ever
     * introduced: when native history is unavailable the test chart remains
     * an explicit empty state.
     */
    static CriticalDisplayPayload captureLatestForTest(Context context,
            long nowMs) {
        long safeNowMs = Math.max(1L, nowMs);
        List<ForecastReading> recent = recentReadings(safeNowMs);
        return fromLatestLocal(recent, cachedForecast(context), safeNowMs);
    }

    static CriticalDisplayPayload fromLatestLocal(
            List<ForecastReading> recent, ForecastSnapshot cached,
            long nowMs) {
        ForecastReading latest = null;
        long latestAllowedMs = safeAdd(Math.max(1L, nowMs),
                FUTURE_CLOCK_SKEW_MS);
        if (recent != null) {
            for (ForecastReading reading : recent) {
                if (reading == null || reading.measuredAtMs <= 0L
                        || reading.measuredAtMs > latestAllowedMs
                        || !validGlucose(reading.glucoseMgDl)) continue;
                if (latest == null
                        || reading.measuredAtMs > latest.measuredAtMs) {
                    latest = reading;
                }
            }
        }
        if (latest == null) return EMPTY;
        return fromActual(recent, cached, latest.measuredAtMs,
                latest.glucoseMgDl, latest.trendMgDlMin,
                Math.max(1L, nowMs));
    }

    /** Immediate actual context; deliberately performs no JNI or repository I/O. */
    static CriticalDisplayPayload immediateActual(long readingAtMs,
            int currentMgDl, Float trendMgDlMin, long nowMs) {
        return fromActual(Collections.emptyList(),
                ForecastSnapshot.empty("no_data"), readingAtMs,
                currentMgDl, trendMgDlMin, nowMs);
    }

    /** Accepts both legacy epoch-seconds and current epoch-millis samples. */
    static long readingTimeMillis(long timestamp, long fallbackNowMs) {
        long fallback = Math.max(0L, fallbackNowMs);
        if (timestamp <= 0L) return fallback;
        // Current production notGlucose instances use milliseconds, while
        // native glucose records and older bridges expose epoch seconds.
        // Values below this boundary cannot be a contemporary millisecond
        // timestamp. Keep the multiply explicitly guarded for malformed input.
        final long minimumContemporaryMillis = 100_000_000_000L;
        if (timestamp >= minimumContemporaryMillis) return timestamp;
        if (timestamp > Long.MAX_VALUE / 1000L) return fallback;
        long converted = timestamp * 1000L;
        return converted > 0L ? converted : fallback;
    }

    /** Captures current native history and the exact accepted alert forecast. */
    static CriticalDisplayPayload capturePredictive(Context context,
            ForecastSnapshot snapshot, ForecastRiskEvaluator.Decision decision,
            long nowMs) {
        if (snapshot == null || decision == null || !decision.shouldNotify()) {
            return EMPTY;
        }
        long readingAtMs = snapshot.basedOnReadingAtMs;
        List<ForecastReading> recent = recentReadings(readingAtMs);
        return fromPredictive(recent, snapshot, decision, nowMs);
    }

    /** Immediate forecast context; native history is enriched after delivery. */
    static CriticalDisplayPayload immediatePredictive(
            ForecastSnapshot snapshot, ForecastRiskEvaluator.Decision decision,
            long nowMs) {
        return fromPredictive(Collections.emptyList(), snapshot, decision,
                nowMs);
    }

    static void enrichActualAsync(Context context, String token,
            long readingAtMs, int currentMgDl, Float trendMgDlMin,
            long capturedAtMs) {
        if (context == null || token == null || token.isEmpty()) return;
        Context app = context.getApplicationContext();
        executeCapture(() -> CriticalGlucoseAlarm.updateDisplayPayload(app,
                token, captureActual(app, readingAtMs, currentMgDl,
                        trendMgDlMin, Math.max(capturedAtMs,
                                System.currentTimeMillis()))));
    }

    static void enrichPredictiveAsync(Context context, String token,
            ForecastSnapshot snapshot, ForecastRiskEvaluator.Decision decision,
            long capturedAtMs) {
        if (context == null || token == null || token.isEmpty()) return;
        Context app = context.getApplicationContext();
        executeCapture(() -> CriticalGlucoseAlarm.updateDisplayPayload(app,
                token, capturePredictive(app, snapshot, decision,
                        Math.max(capturedAtMs,
                                System.currentTimeMillis()))));
    }

    static CriticalDisplayPayload fromActual(List<ForecastReading> recent,
            ForecastSnapshot cached, long readingAtMs, float currentMgDl,
            Float trendMgDlMin, long nowMs) {
        if (readingAtMs <= 0L || !validGlucose(currentMgDl)) return EMPTY;
        ArrayList<HistoryPoint> history = historyPoints(recent, readingAtMs);
        float canonicalCurrent = currentMgDl;
        boolean exactNativeRow = false;
        if (recent != null) {
            for (ForecastReading reading : recent) {
                if (reading != null && reading.measuredAtMs == readingAtMs
                        && validGlucose(reading.glucoseMgDl)) {
                    canonicalCurrent = reading.glucoseMgDl;
                    exactNativeRow = true;
                }
            }
        }
        // The native row can become visible just after the callback. Always
        // retain the alarm sample as a fallback, without replacing an exact
        // immutable raw row that is already committed.
        if (!exactNativeRow) {
            history.add(new HistoryPoint(readingAtMs, canonicalCurrent));
        }

        ForecastSnapshot safe = cached == null
                ? ForecastSnapshot.empty("no_data") : cached;
        boolean matching = safe.isGraphUsable(nowMs)
                && Math.abs(safe.basedOnReadingAtMs - readingAtMs)
                <= FUTURE_CLOCK_SKEW_MS
                && safe.basedOnGlucoseMgDl != null
                && Math.abs(safe.basedOnGlucoseMgDl - canonicalCurrent)
                <= FORECAST_ANCHOR_TOLERANCE_MG_DL;
        List<ForecastPoint> forecast = matching
                ? forecastPoints(safe, readingAtMs)
                : Collections.emptyList();
        return new CriticalDisplayPayload(nowMs, readingAtMs, canonicalCurrent,
                trendMgDlMin,
                ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL,
                ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL,
                matching ? safe.generatedAtMs : 0L, 0L,
                matching ? safe.confidence : 0f, history, forecast);
    }

    static CriticalDisplayPayload fromPredictive(List<ForecastReading> recent,
            ForecastSnapshot snapshot, ForecastRiskEvaluator.Decision decision,
            long nowMs) {
        if (snapshot == null || decision == null || !decision.shouldNotify()
                || !snapshot.isAlertFresh(nowMs)
                || snapshot.basedOnReadingAtMs != decision.anchorMs
                || !validGlucose(decision.currentMgDl)) return EMPTY;
        long anchorMs = snapshot.basedOnReadingAtMs;
        ArrayList<HistoryPoint> history = historyPoints(recent, anchorMs);
        history.add(new HistoryPoint(anchorMs, decision.currentMgDl));
        ForecastSnapshot.AlertAssessment assessment = snapshot.alertAssessment;
        float targetLow = assessment == null
                ? ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL
                : assessment.targetLowMgDl;
        float targetHigh = assessment == null
                ? ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL
                : assessment.targetHighMgDl;
        long crossingAtMs = decision.crossingAtMs >= anchorMs
                && decision.crossingAtMs <= safeAdd(anchorMs,
                        FORECAST_WINDOW_MS)
                ? decision.crossingAtMs : 0L;
        return new CriticalDisplayPayload(nowMs, anchorMs,
                decision.currentMgDl, newestTrend(recent, anchorMs),
                targetLow, targetHigh, snapshot.generatedAtMs, crossingAtMs,
                snapshot.confidence, history,
                forecastPoints(snapshot, anchorMs));
    }

    boolean isEmpty() {
        return currentMgDl == null && history.isEmpty() && forecast.isEmpty();
    }

    boolean hasForecast(long nowMs) {
        if (forecast.size() < 2 || readingAtMs <= 0L
                || forecastGeneratedAtMs <= 0L) return false;
        long age = nowMs - readingAtMs;
        return age >= -FUTURE_CLOCK_SKEW_MS
                && age <= ForecastSnapshot.MAX_GRAPH_AGE_MS;
    }

    String toJsonString() {
        if (isEmpty()) return "";
        try {
            JSONObject value = new JSONObject();
            value.put("v", SCHEMA_VERSION);
            value.put("c", capturedAtMs);
            value.put("r", readingAtMs);
            if (currentMgDl != null) value.put("g", currentMgDl.doubleValue());
            if (trendMgDlMin != null) {
                value.put("d", trendMgDlMin.doubleValue());
            }
            value.put("tl", targetLowMgDl);
            value.put("th", targetHighMgDl);
            if (forecastGeneratedAtMs > 0L) {
                value.put("fg", forecastGeneratedAtMs);
            }
            if (crossingAtMs > 0L) value.put("x", crossingAtMs);
            if (forecastConfidence > 0f) value.put("q", forecastConfidence);
            JSONArray historyValues = new JSONArray();
            for (HistoryPoint point : history) {
                historyValues.put(new JSONArray()
                        .put(point.atMs).put(point.glucoseMgDl));
            }
            value.put("h", historyValues);
            JSONArray forecastValues = new JSONArray();
            for (ForecastPoint point : forecast) {
                forecastValues.put(new JSONArray().put(point.atMs)
                        .put(point.medianMgDl).put(point.lowMgDl)
                        .put(point.highMgDl));
            }
            value.put("f", forecastValues);
            String encoded = value.toString();
            return encoded.length() <= MAX_SERIALIZED_CHARS ? encoded : "";
        } catch (JSONException impossible) {
            return "";
        }
    }

    /** Adds real local history to an already visible delivery-test surface. */
    static void enrichTestAsync(Context context, String token,
            long capturedAtMs) {
        if (context == null || token == null || token.isEmpty()) return;
        Context app = context.getApplicationContext();
        executeCapture(() -> CriticalGlucoseAlarm.updateDisplayPayload(app,
                token, captureLatestForTest(app,
                        Math.max(capturedAtMs, System.currentTimeMillis()))));
    }

    static CriticalDisplayPayload fromJsonString(String encoded) {
        if (encoded == null || encoded.isEmpty()
                || encoded.length() > MAX_SERIALIZED_CHARS) return EMPTY;
        try {
            JSONObject value = new JSONObject(encoded);
            if (value.optInt("v", -1) != SCHEMA_VERSION) return EMPTY;
            long capturedAtMs = value.optLong("c", 0L);
            long readingAtMs = value.optLong("r", 0L);
            Float current = optionalFloat(value, "g");
            Float trend = optionalFloat(value, "d");
            float targetLow = requiredFloat(value, "tl");
            float targetHigh = requiredFloat(value, "th");
            long forecastGeneratedAtMs = value.optLong("fg", 0L);
            long crossingAtMs = value.optLong("x", 0L);
            Float optionalConfidence = optionalFloat(value, "q");
            float confidence = optionalConfidence == null ? 0f
                    : optionalConfidence;
            if (capturedAtMs <= 0L || readingAtMs <= 0L
                    || !validGlucose(current)
                    || !validGlucose(targetLow) || !validGlucose(targetHigh)
                    || targetLow >= targetHigh
                    || (trend != null && (!finite(trend)
                    || trend < -30f || trend > 30f))
                    || (value.has("d") && trend == null)
                    || (value.has("q") && optionalConfidence == null)
                    || forecastGeneratedAtMs < 0L || crossingAtMs < 0L
                    || !finite(confidence)) return EMPTY;

            JSONArray rawHistory = value.optJSONArray("h");
            JSONArray rawForecast = value.optJSONArray("f");
            if (rawHistory == null || rawForecast == null
                    || rawHistory.length() > MAX_HISTORY_POINTS
                    || rawForecast.length() > MAX_FORECAST_POINTS) {
                return EMPTY;
            }
            ArrayList<HistoryPoint> history = new ArrayList<>();
            for (int index = 0; index < rawHistory.length(); index++) {
                JSONArray item = rawHistory.optJSONArray(index);
                if (item == null || item.length() != 2) return EMPTY;
                long atMs = item.optLong(0, 0L);
                float glucose = jsonFloat(item, 1);
                if (atMs <= 0L || !validGlucose(glucose)) return EMPTY;
                history.add(new HistoryPoint(atMs, glucose));
            }
            ArrayList<ForecastPoint> forecast = new ArrayList<>();
            for (int index = 0; index < rawForecast.length(); index++) {
                JSONArray item = rawForecast.optJSONArray(index);
                if (item == null || item.length() != 4) return EMPTY;
                long atMs = item.optLong(0, 0L);
                float median = jsonFloat(item, 1);
                float low = jsonFloat(item, 2);
                float high = jsonFloat(item, 3);
                if (atMs <= 0L || !validGlucose(median)
                        || !validGlucose(low) || !validGlucose(high)) {
                    return EMPTY;
                }
                forecast.add(new ForecastPoint(atMs, median, low, high));
            }
            return new CriticalDisplayPayload(capturedAtMs, readingAtMs,
                    current, trend, targetLow, targetHigh,
                    forecastGeneratedAtMs, crossingAtMs, confidence,
                    history, forecast);
        } catch (JSONException | RuntimeException corrupt) {
            return EMPTY;
        }
    }

    private static ForecastSnapshot cachedForecast(Context context) {
        if (context == null) return ForecastSnapshot.empty("no_data");
        try {
            ForecastRepository.State state = ForecastRepository.get(
                    context.getApplicationContext()).snapshot();
            return state == null || state.forecast == null
                    ? ForecastSnapshot.empty("no_data") : state.forecast;
        } catch (LinkageError | RuntimeException unavailable) {
            return ForecastSnapshot.empty("no_data");
        }
    }

    private static void executeCapture(Runnable work) {
        try {
            CAPTURE_EXECUTOR.execute(() -> {
                try {
                    work.run();
                } catch (Throwable unavailable) {
                    // Chart enrichment is optional and must never disturb an
                    // already actionable critical alarm.
                }
            });
        } catch (RuntimeException unavailable) {
            // The immediate payload remains usable.
        }
    }

    private static List<ForecastReading> recentReadings(long readingAtMs) {
        if (readingAtMs <= 0L) return Collections.emptyList();
        try {
            long afterMs = Math.max(0L,
                    readingAtMs - HISTORY_WINDOW_MS - 1L);
            return ForecastRepository.decodeNativeReadings(
                    Natives.forecastReadings(afterMs, HISTORY_QUERY_LIMIT));
        } catch (LinkageError | RuntimeException unavailable) {
            return Collections.emptyList();
        }
    }

    private static ArrayList<HistoryPoint> historyPoints(
            List<ForecastReading> recent, long readingAtMs) {
        ArrayList<HistoryPoint> result = new ArrayList<>();
        long startMs = Math.max(0L, readingAtMs - HISTORY_WINDOW_MS);
        long endMs = safeAdd(readingAtMs, FUTURE_CLOCK_SKEW_MS);
        if (recent != null) {
            for (ForecastReading reading : recent) {
                if (reading == null || reading.measuredAtMs < startMs
                        || reading.measuredAtMs > endMs
                        || !validGlucose(reading.glucoseMgDl)) continue;
                result.add(new HistoryPoint(reading.measuredAtMs,
                        reading.glucoseMgDl));
            }
        }
        return result;
    }

    private static List<ForecastPoint> forecastPoints(
            ForecastSnapshot snapshot, long anchorMs) {
        ArrayList<ForecastPoint> result = new ArrayList<>();
        if (snapshot == null) return result;
        long startMs = Math.max(0L, anchorMs - FUTURE_CLOCK_SKEW_MS);
        long endMs = safeAdd(anchorMs, FORECAST_WINDOW_MS);
        for (ForecastSnapshot.Point point : snapshot.points) {
            if (point == null || point.atMs < startMs || point.atMs > endMs
                    || !validGlucose(point.medianMgDl)
                    || !validGlucose(point.lowMgDl)
                    || !validGlucose(point.highMgDl)) continue;
            result.add(new ForecastPoint(point.atMs, point.medianMgDl,
                    point.lowMgDl, point.highMgDl));
        }
        return result;
    }

    private static Float newestTrend(List<ForecastReading> recent,
            long anchorMs) {
        ForecastReading newest = null;
        if (recent != null) {
            for (ForecastReading reading : recent) {
                if (reading == null || reading.measuredAtMs > anchorMs
                        + FUTURE_CLOCK_SKEW_MS) continue;
                if (newest == null
                        || reading.measuredAtMs > newest.measuredAtMs) {
                    newest = reading;
                }
            }
        }
        return newest == null ? null : newest.trendMgDlMin;
    }

    private static List<HistoryPoint> boundedHistory(
            List<HistoryPoint> source, long anchorMs, long markerMs) {
        ArrayList<HistoryPoint> sorted = new ArrayList<>();
        long minimumAtMs = Math.max(0L, anchorMs - HISTORY_WINDOW_MS);
        long maximumAtMs = safeAdd(anchorMs, FUTURE_CLOCK_SKEW_MS);
        if (source != null) {
            for (HistoryPoint point : source) {
                if (point != null && point.atMs > 0L
                        && point.atMs >= minimumAtMs
                        && point.atMs <= maximumAtMs
                        && validGlucose(point.glucoseMgDl)) sorted.add(point);
            }
        }
        sorted.sort(Comparator.comparingLong(point -> point.atMs));
        dedupeHistoryLastWins(sorted);
        return selectHistory(sorted, markerMs, MAX_HISTORY_POINTS);
    }

    private static List<ForecastPoint> boundedForecast(
            List<ForecastPoint> source, long anchorMs, long markerMs) {
        ArrayList<ForecastPoint> sorted = new ArrayList<>();
        long minimumAtMs = Math.max(0L, anchorMs - FUTURE_CLOCK_SKEW_MS);
        long maximumAtMs = safeAdd(anchorMs, FORECAST_WINDOW_MS);
        if (source != null) {
            for (ForecastPoint point : source) {
                if (point != null && point.atMs > 0L
                        && point.atMs >= minimumAtMs
                        && point.atMs <= maximumAtMs
                        && validGlucose(point.medianMgDl)
                        && validGlucose(point.lowMgDl)
                        && validGlucose(point.highMgDl)) sorted.add(point);
            }
        }
        sorted.sort(Comparator.comparingLong(point -> point.atMs));
        dedupeForecastLastWins(sorted);
        return selectForecast(sorted, markerMs, MAX_FORECAST_POINTS);
    }

    private static void dedupeHistoryLastWins(ArrayList<HistoryPoint> values) {
        for (int index = values.size() - 1; index > 0; index--) {
            if (values.get(index).atMs == values.get(index - 1).atMs) {
                values.remove(index - 1);
            }
        }
    }

    private static void dedupeForecastLastWins(
            ArrayList<ForecastPoint> values) {
        for (int index = values.size() - 1; index > 0; index--) {
            if (values.get(index).atMs == values.get(index - 1).atMs) {
                values.remove(index - 1);
            }
        }
    }

    private static List<HistoryPoint> selectHistory(
            ArrayList<HistoryPoint> values, long markerMs, int maximum) {
        if (values.size() <= maximum) return values;
        int[] indices = selectedIndices(values.size(), nearestHistory(
                values, markerMs), maximum);
        ArrayList<HistoryPoint> result = new ArrayList<>(indices.length);
        for (int index : indices) result.add(values.get(index));
        return result;
    }

    private static List<ForecastPoint> selectForecast(
            ArrayList<ForecastPoint> values, long markerMs, int maximum) {
        if (values.size() <= maximum) return values;
        int[] indices = selectedIndices(values.size(), nearestForecast(
                values, markerMs), maximum);
        ArrayList<ForecastPoint> result = new ArrayList<>(indices.length);
        for (int index : indices) result.add(values.get(index));
        return result;
    }

    private static int[] selectedIndices(int size, int marker, int maximum) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        selected.add(0);
        if (marker >= 0) selected.add(marker);
        selected.add(size - 1);
        for (int slot = 0; slot < maximum && selected.size() < maximum;
                slot++) {
            selected.add(Math.round(slot * (size - 1f)
                    / Math.max(1, maximum - 1)));
        }
        for (int index = 0; index < size && selected.size() < maximum;
                index++) selected.add(index);
        ArrayList<Integer> sorted = new ArrayList<>(selected);
        Collections.sort(sorted);
        int[] result = new int[Math.min(maximum, sorted.size())];
        for (int index = 0; index < result.length; index++) {
            result[index] = sorted.get(index);
        }
        return result;
    }

    private static int nearestHistory(List<HistoryPoint> values,
            long markerMs) {
        long distance = Long.MAX_VALUE;
        int result = -1;
        for (int index = 0; index < values.size(); index++) {
            long candidate = absoluteDifference(values.get(index).atMs,
                    markerMs);
            if (candidate < distance) {
                distance = candidate;
                result = index;
            }
        }
        return result;
    }

    private static int nearestForecast(List<ForecastPoint> values,
            long markerMs) {
        long distance = Long.MAX_VALUE;
        int result = -1;
        for (int index = 0; index < values.size(); index++) {
            long candidate = absoluteDifference(values.get(index).atMs,
                    markerMs);
            if (candidate < distance) {
                distance = candidate;
                result = index;
            }
        }
        return result;
    }

    private static long absoluteDifference(long left, long right) {
        if (right <= 0L) return Long.MAX_VALUE;
        long difference = left - right;
        return difference == Long.MIN_VALUE ? Long.MAX_VALUE
                : Math.abs(difference);
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE : value + increment;
    }

    private static Float optionalFloat(JSONObject value, String key) {
        if (!value.has(key) || value.isNull(key)) return null;
        double parsed = value.optDouble(key, Double.NaN);
        return Double.isNaN(parsed) || Double.isInfinite(parsed)
                ? null : (float) parsed;
    }

    private static float requiredFloat(JSONObject value, String key) {
        double parsed = value.optDouble(key, Double.NaN);
        return Double.isNaN(parsed) || Double.isInfinite(parsed)
                ? Float.NaN : (float) parsed;
    }

    private static float jsonFloat(JSONArray value, int index) {
        double parsed = value.optDouble(index, Double.NaN);
        return Double.isNaN(parsed) || Double.isInfinite(parsed)
                ? Float.NaN : (float) parsed;
    }

    private static boolean validGlucose(Float value) {
        return finite(value) && value >= 20f && value <= 600f;
    }

    private static boolean validGlucose(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value >= 20f && value <= 600f;
    }

    private static boolean finite(Float value) {
        return value != null && !Float.isNaN(value)
                && !Float.isInfinite(value);
    }
}
