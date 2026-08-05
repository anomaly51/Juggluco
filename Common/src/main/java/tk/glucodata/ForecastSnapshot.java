package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Tolerant, immutable representation of the backend's current forecast. */
final class ForecastSnapshot {
    static final int MAX_HORIZON_MINUTES = 120;
    static final long MAX_GRAPH_AGE_MS = 15L * 60L * 1000L;

    static final class Point {
        final long atMs;
        final float medianMgDl;
        final float lowMgDl;
        final float highMgDl;

        Point(long atMs, float medianMgDl, float lowMgDl, float highMgDl) {
            this.atMs = atMs;
            this.medianMgDl = medianMgDl;
            this.lowMgDl = Math.min(lowMgDl, highMgDl);
            this.highMgDl = Math.max(lowMgDl, highMgDl);
        }
    }

    static final class Activity {
        static final int KIND_MEAL = 1;
        static final int KIND_RAPID = 2;
        static final int KIND_LONG = 3;

        final String eventId;
        final int kind;
        final String label;
        final long startMs;
        final long peakMs;
        final long endMs;
        final float strength;
        final float confidence;
        final Float absorptionSpeed;
        final Float amount;
        final String unit;
        final String profileSource;
        final Float profileConfidence;
        /** Optional effective-action timing emitted by newer forecast APIs. */
        final Long onsetMs;
        final Long peakLowMs;
        final Long peakHighMs;
        final Long endLowMs;
        final Long endHighMs;
        final Float attributionConfidence;
        final String identifiability;
        final String actionModel;
        final int overlapCount;
        final List<ActivityPoint> points;

        Activity(String eventId, int kind, String label, long startMs,
                long peakMs, long endMs, float strength, float confidence,
                Float absorptionSpeed) {
            this(eventId, kind, label, startMs, peakMs, endMs, strength,
                    confidence, absorptionSpeed, null, "", "", null,
                    Collections.emptyList());
        }

        Activity(String eventId, int kind, String label, long startMs,
                long peakMs, long endMs, float strength, float confidence,
                Float absorptionSpeed, Float amount, String unit,
                String profileSource, Float profileConfidence,
                List<ActivityPoint> points) {
            this(eventId, kind, label, startMs, peakMs, endMs, strength,
                    confidence, absorptionSpeed, amount, unit, profileSource,
                    profileConfidence, null, null, null, null, null, null,
                    "", "", 0, points);
        }

        Activity(String eventId, int kind, String label, long startMs,
                long peakMs, long endMs, float strength, float confidence,
                Float absorptionSpeed, Float amount, String unit,
                String profileSource, Float profileConfidence, Long onsetMs,
                Long peakLowMs, Long peakHighMs, Long endLowMs,
                Long endHighMs, Float attributionConfidence,
                String identifiability, String actionModel, int overlapCount,
                List<ActivityPoint> points) {
            this.eventId = clean(eventId);
            this.kind = kind;
            this.label = clean(label);
            this.startMs = startMs;
            this.peakMs = Math.max(startMs, peakMs);
            this.endMs = Math.max(this.peakMs, endMs);
            this.strength = finiteOrZero(strength);
            this.confidence = clamp01(confidence);
            this.absorptionSpeed = absorptionSpeed == null ? null
                    : clamp01(absorptionSpeed);
            this.amount = amount != null && isFinite(amount) && amount >= 0f
                    ? amount : null;
            this.unit = clean(unit);
            this.profileSource = clean(profileSource);
            this.profileConfidence = profileConfidence == null ? null
                    : clamp01(profileConfidence);

            Long normalizedEndLow = null;
            Long normalizedEndHigh = null;
            if (validTimestamp(endLowMs) || validTimestamp(endHighMs)) {
                long rawLow = validTimestamp(endLowMs) ? endLowMs
                        : endHighMs;
                long rawHigh = validTimestamp(endHighMs) ? endHighMs
                        : endLowMs;
                long low = Math.min(rawLow, rawHigh);
                long high = Math.max(rawLow, rawHigh);
                // A legacy representative end remains valid and must sit
                // inside the newer uncertainty interval.
                low = Math.min(low, this.endMs);
                high = Math.max(high, this.endMs);
                low = Math.max(this.peakMs, low);
                high = Math.max(low, high);
                normalizedEndLow = low;
                normalizedEndHigh = high;
            }
            long latestEnd = normalizedEndHigh == null ? this.endMs
                    : Math.max(this.endMs, normalizedEndHigh);
            Long normalizedOnset = validTimestamp(onsetMs)
                    ? Math.max(this.startMs, Math.min(this.peakMs, onsetMs))
                    : null;
            long actionStart = normalizedOnset == null ? this.startMs
                    : normalizedOnset;

            Long normalizedPeakLow = null;
            Long normalizedPeakHigh = null;
            if (validTimestamp(peakLowMs) || validTimestamp(peakHighMs)) {
                long rawLow = validTimestamp(peakLowMs) ? peakLowMs
                        : peakHighMs;
                long rawHigh = validTimestamp(peakHighMs) ? peakHighMs
                        : peakLowMs;
                long low = Math.min(rawLow, rawHigh);
                long high = Math.max(rawLow, rawHigh);
                low = Math.min(low, this.peakMs);
                high = Math.max(high, this.peakMs);
                low = Math.max(actionStart, Math.min(latestEnd, low));
                high = Math.max(low, Math.min(latestEnd, high));
                normalizedPeakLow = low;
                normalizedPeakHigh = high;
            }
            if (normalizedEndLow != null && normalizedPeakHigh != null
                    && normalizedEndLow < normalizedPeakHigh) {
                normalizedEndLow = normalizedPeakHigh;
                normalizedEndHigh = Math.max(normalizedEndLow,
                        normalizedEndHigh);
            }
            this.onsetMs = normalizedOnset;
            this.peakLowMs = normalizedPeakLow;
            this.peakHighMs = normalizedPeakHigh;
            this.endLowMs = normalizedEndLow;
            this.endHighMs = normalizedEndHigh;
            this.attributionConfidence = attributionConfidence == null ? null
                    : clamp01(attributionConfidence);
            this.identifiability = clean(identifiability)
                    .toLowerCase(java.util.Locale.ROOT);
            this.actionModel = clean(actionModel)
                    .toLowerCase(java.util.Locale.ROOT);
            // The backend reports the number of *other* factors that overlap
            // this one. Zero therefore means this event is isolated.
            this.overlapCount = Math.max(0, Math.min(999, overlapCount));
            ArrayList<ActivityPoint> sorted = new ArrayList<>(points == null
                    ? Collections.emptyList() : points);
            sorted.sort(Comparator.comparingLong(point -> point.atMs));
            this.points = Collections.unmodifiableList(sorted);
        }

        boolean hasEstimatedActionWindow() {
            return onsetMs != null || peakLowMs != null || peakHighMs != null
                    || endLowMs != null || endHighMs != null;
        }

        long effectiveOnsetMs() {
            return onsetMs == null ? startMs : onsetMs;
        }

        long effectivePeakLowMs() {
            return peakLowMs == null ? peakMs : peakLowMs;
        }

        long effectivePeakHighMs() {
            return peakHighMs == null ? peakMs : peakHighMs;
        }

        long effectiveEndLowMs() {
            return endLowMs == null ? endMs : endLowMs;
        }

        long effectiveEndHighMs() {
            return endHighMs == null ? endMs : endHighMs;
        }

        static Activity fromJson(JSONObject value) {
            String rawKind = value.optString("kind", "")
                    .trim().toLowerCase(java.util.Locale.ROOT);
            int kind;
            if (rawKind.contains("meal") || rawKind.contains("food")
                    || rawKind.contains("carb")) {
                kind = KIND_MEAL;
            } else if (rawKind.contains("rapid")
                    || rawKind.contains("novorapid")) {
                kind = KIND_RAPID;
            } else if (rawKind.contains("long")
                    || rawKind.contains("tresiba")
                    || rawKind.contains("basal")) {
                kind = KIND_LONG;
            } else {
                return null;
            }
            long start = value.optLong("start_ms", 0L);
            long peak = value.optLong("peak_ms", start);
            long end = value.optLong("end_ms", peak);
            if (start <= 0L || peak < start || end < peak) return null;
            Float absorption = null;
            if (value.has("absorption_speed")
                    && !value.isNull("absorption_speed")) {
                float parsed = finiteFloat(value, "absorption_speed",
                        Float.NaN);
                if (isFinite(parsed)) absorption = parsed;
            }
            Float amount = optionalFloat(value, "amount");
            Float profileConfidence = optionalFloat(value,
                    "profile_confidence");
            Long onsetMs = optionalLong(value, "onset_ms");
            Long peakLowMs = optionalLong(value, "peak_low_ms");
            Long peakHighMs = optionalLong(value, "peak_high_ms");
            Long endLowMs = optionalLong(value, "end_low_ms");
            Long endHighMs = optionalLong(value, "end_high_ms");
            Float attributionConfidence = optionalFloat(value,
                    "attribution_confidence");
            ArrayList<ActivityPoint> points = new ArrayList<>();
            JSONArray pointValues = value.optJSONArray("points");
            if (pointValues != null) {
                for (int index = 0; index < pointValues.length(); index++) {
                    JSONObject item = pointValues.optJSONObject(index);
                    ActivityPoint point = item == null ? null
                            : ActivityPoint.fromJson(item);
                    if (point != null) points.add(point);
                }
            }
            return new Activity(value.optString("event_id", ""), kind,
                    value.optString("label", rawKind), start, peak, end,
                    finiteFloat(value, "strength", 0f),
                    finiteFloat(value, "confidence", 0f), absorption,
                    amount, value.optString("unit", ""),
                    value.optString("profile_source", ""),
                    profileConfidence, onsetMs, peakLowMs, peakHighMs,
                    endLowMs, endHighMs, attributionConfidence,
                    value.optString("identifiability", ""),
                    value.optString("action_model", ""),
                    value.optInt("overlap_count", 0), points);
        }

        private static boolean validTimestamp(Long value) {
            return value != null && value > 0L;
        }
    }

    static final class ActivityPoint {
        final long atMs;
        final float minutesFromAnchor;
        final float contributionMgDl;
        final float activity;

        ActivityPoint(long atMs, float minutesFromAnchor,
                float contributionMgDl, float activity) {
            this.atMs = atMs;
            this.minutesFromAnchor = minutesFromAnchor;
            this.contributionMgDl = contributionMgDl;
            this.activity = clamp01(activity);
        }

        static ActivityPoint fromJson(JSONObject value) {
            float minutes = finiteFloat(value, "minutes_from_anchor",
                    Float.NaN);
            long atMs = value.optLong("at_ms", 0L);
            float contribution = finiteFloat(value,
                    "contribution_mg_dl", Float.NaN);
            float activity = finiteFloat(value, "activity", Float.NaN);
            if (atMs <= 0L || !isFinite(contribution)
                    || !isFinite(activity)) return null;
            if (!isFinite(minutes)) minutes = 0f;
            return new ActivityPoint(atMs, minutes, contribution, activity);
        }
    }

    final String status;
    final long generatedAtMs;
    final long basedOnReadingAtMs;
    final int horizonMinutes;
    final String modelVersion;
    final float confidence;
    final List<Point> points;
    final List<Activity> activities;
    final String conditionalNotice;

    ForecastSnapshot(String status, long generatedAtMs,
            long basedOnReadingAtMs, int horizonMinutes, String modelVersion,
            float confidence, List<Point> points, List<Activity> activities,
            String conditionalNotice) {
        this.status = clean(status);
        this.generatedAtMs = generatedAtMs;
        this.basedOnReadingAtMs = basedOnReadingAtMs;
        this.horizonMinutes = Math.max(0,
                Math.min(MAX_HORIZON_MINUTES, horizonMinutes));
        this.modelVersion = clean(modelVersion);
        this.confidence = clamp01(confidence);
        this.points = immutableSorted(points);
        this.activities = Collections.unmodifiableList(
                new ArrayList<>(activities == null
                        ? Collections.emptyList() : activities));
        this.conditionalNotice = clean(conditionalNotice);
    }

    static ForecastSnapshot empty(String status) {
        return new ForecastSnapshot(status, 0L, 0L, 0, "", 0f,
                Collections.emptyList(), Collections.emptyList(), "");
    }

    static ForecastSnapshot fromJson(JSONObject value) {
        if (value == null) return empty("no_data");
        JSONObject payload = value.optJSONObject("forecast");
        if (payload == null) payload = value;
        ArrayList<Point> points = new ArrayList<>();
        JSONArray pointValues = payload.optJSONArray("points");
        if (pointValues != null) {
            for (int index = 0; index < pointValues.length(); index++) {
                JSONObject point = pointValues.optJSONObject(index);
                if (point == null) continue;
                long atMs = point.optLong("at_ms", 0L);
                float median = finiteFloat(point, "median_mg_dl", Float.NaN);
                float low = finiteFloat(point, "low_mg_dl", Float.NaN);
                float high = finiteFloat(point, "high_mg_dl", Float.NaN);
                if (atMs > 0L && isFinite(median) && isFinite(low)
                        && isFinite(high)) {
                    points.add(new Point(atMs, median, low, high));
                }
            }
        }
        ArrayList<Activity> activities = new ArrayList<>();
        JSONArray activityValues = payload.optJSONArray("activities");
        if (activityValues != null) {
            for (int index = 0; index < activityValues.length(); index++) {
                JSONObject item = activityValues.optJSONObject(index);
                Activity activity = item == null ? null
                        : Activity.fromJson(item);
                if (activity != null) activities.add(activity);
            }
        }
        return new ForecastSnapshot(payload.optString("status", "no_data"),
                payload.optLong("generated_at_ms", 0L),
                payload.optLong("based_on_reading_at_ms", 0L),
                payload.optInt("horizon_minutes", inferHorizon(points)),
                payload.optString("model_version", ""),
                finiteFloat(payload, "confidence", 0f), points, activities,
                payload.optString("conditional_notice", ""));
    }

    boolean isGraphUsable(long nowMs) {
        // A cold-start baseline is still useful when fresh and accompanied by
        // its deliberately wide uncertainty band. Explicit low-confidence,
        // no-data and error states never reach the graph.
        if (!("ready".equalsIgnoreCase(status)
                || "learning".equalsIgnoreCase(status)
                || "cold_start".equalsIgnoreCase(status))) return false;
        if (points.size() < 2 || basedOnReadingAtMs <= 0L
                || confidence <= 0f) return false;
        long age = nowMs - basedOnReadingAtMs;
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        long maximumEnd = basedOnReadingAtMs
                + (MAX_HORIZON_MINUTES + 5L) * 60_000L;
        return age >= -5L * 60L * 1000L && age <= MAX_GRAPH_AGE_MS
                && first.atMs >= basedOnReadingAtMs - 60_000L
                && last.atMs > basedOnReadingAtMs
                && last.atMs <= maximumEnd;
    }

    private static List<Point> immutableSorted(List<Point> source) {
        ArrayList<Point> sorted = new ArrayList<>(source == null
                ? Collections.emptyList() : source);
        sorted.sort(Comparator.comparingLong(point -> point.atMs));
        ArrayList<Point> unique = new ArrayList<>(sorted.size());
        long previous = Long.MIN_VALUE;
        for (Point point : sorted) {
            if (point.atMs == previous) continue;
            unique.add(point);
            previous = point.atMs;
        }
        return Collections.unmodifiableList(unique);
    }

    private static int inferHorizon(List<Point> points) {
        if (points == null || points.size() < 2) return 0;
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        for (Point point : points) {
            minimum = Math.min(minimum, point.atMs);
            maximum = Math.max(maximum, point.atMs);
        }
        long minutes = Math.max(0L, (maximum - minimum) / 60_000L);
        return (int) Math.min(MAX_HORIZON_MINUTES, minutes);
    }

    static float finiteFloat(JSONObject value, String name, float fallback) {
        double number = value.optDouble(name, Double.NaN);
        return Double.isNaN(number) || Double.isInfinite(number)
                ? fallback : (float) number;
    }

    private static Float optionalFloat(JSONObject value, String name) {
        if (value == null || !value.has(name) || value.isNull(name)) {
            return null;
        }
        float parsed = finiteFloat(value, name, Float.NaN);
        return isFinite(parsed) ? parsed : null;
    }

    private static Long optionalLong(JSONObject value, String name) {
        if (value == null || !value.has(name) || value.isNull(name)) {
            return null;
        }
        long parsed = value.optLong(name, Long.MIN_VALUE);
        return parsed > 0L ? parsed : null;
    }

    private static float finiteOrZero(float value) {
        return isFinite(value) ? value : 0f;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    static float clamp01(float value) {
        return isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
