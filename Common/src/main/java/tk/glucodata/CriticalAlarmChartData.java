package tk.glucodata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Small immutable glucose snapshot used by the critical alarm surface.
 *
 * <p>The snapshot is deliberately read-only and bounded. It never initiates a
 * backend refresh: a time-critical acknowledgement surface must be available
 * even when native history or the forecasting backend is unavailable.</p>
 */
final class CriticalAlarmChartData {
    static final long HISTORY_WINDOW_MS = 90L * 60_000L;
    private static final long FUTURE_CLOCK_SKEW_MS = 60_000L;

    static final class Point {
        final long atMs;
        final float glucoseMgDl;
        final float lowMgDl;
        final float highMgDl;

        Point(long atMs, float glucoseMgDl) {
            this(atMs, glucoseMgDl, glucoseMgDl, glucoseMgDl);
        }

        Point(long atMs, float glucoseMgDl, float lowMgDl,
                float highMgDl) {
            this.atMs = atMs;
            this.glucoseMgDl = glucoseMgDl;
            this.lowMgDl = Math.min(lowMgDl, highMgDl);
            this.highMgDl = Math.max(lowMgDl, highMgDl);
        }
    }

    final List<Point> history;
    final List<Point> forecast;
    final float targetLowMgDl;
    final float targetHighMgDl;
    final long nowMs;
    final int forecastMinutes;
    final Float trendMgDlMin;
    final int historyMinutes;

    private CriticalAlarmChartData(List<Point> history, List<Point> forecast,
            float targetLowMgDl, float targetHighMgDl, long nowMs,
            int forecastMinutes, Float trendMgDlMin, int historyMinutes) {
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
        this.forecast = Collections.unmodifiableList(new ArrayList<>(forecast));
        this.targetLowMgDl = targetLowMgDl;
        this.targetHighMgDl = targetHighMgDl;
        this.nowMs = nowMs;
        this.forecastMinutes = forecastMinutes;
        this.trendMgDlMin = finite(trendMgDlMin) ? trendMgDlMin : null;
        this.historyMinutes = Math.max(1, Math.min(180, historyMinutes));
    }

    static CriticalAlarmChartData empty(long nowMs) {
        return new CriticalAlarmChartData(Collections.emptyList(),
                Collections.emptyList(),
                ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL,
                ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL,
                nowMs, 0, null,
                (int) (CriticalDisplayPayload.HISTORY_WINDOW_MS / 60_000L));
    }

    static CriticalAlarmChartData from(CriticalDisplayPayload payload,
            long nowMs) {
        if (payload == null || payload.isEmpty()) return empty(nowMs);
        ArrayList<Point> history = new ArrayList<>();
        for (CriticalDisplayPayload.HistoryPoint point : payload.history) {
            if (point != null && validGlucose(point.glucoseMgDl)) {
                history.add(new Point(point.atMs, point.glucoseMgDl));
            }
        }
        sortAndDedupe(history);
        ArrayList<Point> forecast = new ArrayList<>();
        if (payload.hasForecast(nowMs)) {
            for (CriticalDisplayPayload.ForecastPoint point
                    : payload.forecast) {
                if (point != null && validGlucose(point.medianMgDl)
                        && validGlucose(point.lowMgDl)
                        && validGlucose(point.highMgDl)) {
                    forecast.add(new Point(point.atMs, point.medianMgDl,
                            point.lowMgDl, point.highMgDl));
                }
            }
            sortAndDedupe(forecast);
        }
        int horizon = 0;
        if (forecast.size() >= 2 && payload.readingAtMs > 0L) {
            long end = forecast.get(forecast.size() - 1).atMs;
            horizon = (int) Math.max(0L, Math.min(
                    ForecastSnapshot.MAX_HORIZON_MINUTES,
                    (end - payload.readingAtMs + 59_999L) / 60_000L));
        }
        return new CriticalAlarmChartData(history, forecast,
                payload.targetLowMgDl, payload.targetHighMgDl,
                nowMs, horizon, payload.trendMgDlMin,
                (int) (CriticalDisplayPayload.HISTORY_WINDOW_MS / 60_000L));
    }

    static CriticalAlarmChartData from(List<ForecastReading> readings,
            ForecastSnapshot snapshot, long nowMs) {
        ArrayList<Point> history = new ArrayList<>();
        long historyStart = Math.max(0L, nowMs - HISTORY_WINDOW_MS);
        if (readings != null) {
            for (ForecastReading reading : readings) {
                if (reading == null
                        || reading.measuredAtMs < historyStart
                        || reading.measuredAtMs > nowMs + FUTURE_CLOCK_SKEW_MS
                        || !validGlucose(reading.glucoseMgDl)) continue;
                history.add(new Point(reading.measuredAtMs,
                        reading.glucoseMgDl));
            }
        }
        sortAndDedupe(history);

        ForecastSnapshot safe = snapshot == null
                ? ForecastSnapshot.empty("no_data") : snapshot;
        ArrayList<Point> forecast = new ArrayList<>();
        boolean usable = safe.isGraphUsable(nowMs);
        if (usable) {
            for (ForecastSnapshot.Point point : safe.points) {
                if (point == null || point.atMs < nowMs - FUTURE_CLOCK_SKEW_MS
                        || !validGlucose(point.medianMgDl)
                        || !validGlucose(point.lowMgDl)
                        || !validGlucose(point.highMgDl)) continue;
                forecast.add(new Point(point.atMs, point.medianMgDl,
                        point.lowMgDl, point.highMgDl));
            }
            sortAndDedupe(forecast);
        }

        ForecastSnapshot.AlertAssessment assessment = safe.alertAssessment;
        float targetLow = assessment == null
                ? ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL
                : assessment.targetLowMgDl;
        float targetHigh = assessment == null
                ? ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL
                : assessment.targetHighMgDl;
        if (!validGlucose(targetLow) || !validGlucose(targetHigh)
                || targetLow >= targetHigh) {
            targetLow = ForecastSnapshot.AlertAssessment
                    .DEFAULT_TARGET_LOW_MG_DL;
            targetHigh = ForecastSnapshot.AlertAssessment
                    .DEFAULT_TARGET_HIGH_MG_DL;
        }
        int horizon = forecast.size() < 2 ? 0
                : Math.max(0, Math.min(ForecastSnapshot.MAX_HORIZON_MINUTES,
                safe.horizonMinutes));
        return new CriticalAlarmChartData(history, forecast,
                targetLow, targetHigh, nowMs, horizon,
                newestTrend(readings, nowMs),
                (int) (HISTORY_WINDOW_MS / 60_000L));
    }

    boolean hasData() {
        return !history.isEmpty() || !forecast.isEmpty();
    }

    boolean hasForecast() {
        return forecast.size() >= 2 && forecastMinutes > 0;
    }

    /** Five-minute-normalized direction from the two most recent readings. */
    int trend() {
        if (trendMgDlMin != null) {
            if (trendMgDlMin >= 1.6f) return 2;
            if (trendMgDlMin >= .4f) return 1;
            if (trendMgDlMin <= -1.6f) return -2;
            if (trendMgDlMin <= -.4f) return -1;
            return 0;
        }
        if (history.size() < 2) return 0;
        Point previous = history.get(history.size() - 2);
        Point latest = history.get(history.size() - 1);
        long elapsed = latest.atMs - previous.atMs;
        if (elapsed < 30_000L || elapsed > 30L * 60_000L) return 0;
        float fiveMinuteDelta = (latest.glucoseMgDl - previous.glucoseMgDl)
                * (5f * 60_000f / elapsed);
        if (fiveMinuteDelta >= 8f) return 2;
        if (fiveMinuteDelta >= 2f) return 1;
        if (fiveMinuteDelta <= -8f) return -2;
        if (fiveMinuteDelta <= -2f) return -1;
        return 0;
    }

    private static boolean validGlucose(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value >= 20f && value <= 600f;
    }

    private static Float newestTrend(List<ForecastReading> readings,
            long nowMs) {
        ForecastReading newest = null;
        if (readings != null) {
            for (ForecastReading reading : readings) {
                if (reading == null
                        || reading.measuredAtMs > nowMs + FUTURE_CLOCK_SKEW_MS
                        || reading.trendMgDlMin == null) continue;
                if (newest == null
                        || reading.measuredAtMs > newest.measuredAtMs) {
                    newest = reading;
                }
            }
        }
        return newest == null ? null : newest.trendMgDlMin;
    }

    private static boolean finite(Float value) {
        return value != null && !Float.isNaN(value)
                && !Float.isInfinite(value);
    }

    private static void sortAndDedupe(ArrayList<Point> points) {
        points.sort(Comparator.comparingLong(point -> point.atMs));
        for (int index = points.size() - 1; index > 0; index--) {
            if (points.get(index).atMs == points.get(index - 1).atMs) {
                // Prefer the later deterministic source entry for overlaps.
                points.remove(index - 1);
            }
        }
    }
}
