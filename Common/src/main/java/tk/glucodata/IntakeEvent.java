/*
 * This file is part of Juggluco.
 *
 * The redesigned intake flow deliberately keeps its data contract separate
 * from the legacy NumberView/Numdata editor. The localhost backend is the
 * source of truth; this class is only the immutable presentation model used by
 * the phone UI and graph overlay.
 */
package tk.glucodata;

import org.json.JSONException;
import org.json.JSONObject;

public final class IntakeEvent {
    public final String id;
    public final String clientEventId;
    /** True while the durable phone outbox is waiting for backend confirmation. */
    public final boolean pendingSync;
    public final long occurredAtMs;
    public final String mealText;
    public final float carbsGrams;
    /** True when the backend explicitly supplied {@code carbs_g}, including 0 g. */
    public final boolean carbsPresent;
    /** Actual consumed mass plus the immutable analyzed full-meal baseline. */
    public final Float portionGrams;
    public final Float originalPortionGrams;
    public final Float originalCarbsGrams;
    public final String carbsSource;
    public final float insulinUnits;
    public final String insulinType;
    public final String insulinName;
    public final String analysisId;
    public final float aiConfidence;
    /** Optional backend estimate in the continuous 0..1 domain. */
    public final Float absorptionSpeed;
    public final Integer absorptionPeakMinutes;
    public final Integer absorptionDurationMinutes;
    public final Float absorptionConfidence;

    IntakeEvent(String id, long occurredAtMs, String mealText,
            float carbsGrams, String carbsSource, float insulinUnits,
            String insulinType, String insulinName, String analysisId,
            float aiConfidence) {
        this(id, occurredAtMs, mealText, carbsGrams,
                carbsGrams > 0.0f, carbsSource,
                insulinUnits, insulinType, insulinName, analysisId,
                aiConfidence, null, null, null, null,
                null, null, null, "", false);
    }

    IntakeEvent(String id, long occurredAtMs, String mealText,
            float carbsGrams, String carbsSource, float insulinUnits,
            String insulinType, String insulinName, String analysisId,
            float aiConfidence, Float absorptionSpeed,
            Integer absorptionPeakMinutes, Integer absorptionDurationMinutes,
            Float absorptionConfidence) {
        this(id, occurredAtMs, mealText, carbsGrams, carbsGrams > 0.0f,
                carbsSource, insulinUnits, insulinType, insulinName,
                analysisId, aiConfidence, absorptionSpeed,
                absorptionPeakMinutes, absorptionDurationMinutes,
                absorptionConfidence, null, null, null, "", false);
    }

    private IntakeEvent(String id, long occurredAtMs, String mealText,
            float carbsGrams, boolean carbsPresent, String carbsSource,
            float insulinUnits, String insulinType, String insulinName,
            String analysisId, float aiConfidence, Float absorptionSpeed,
            Integer absorptionPeakMinutes, Integer absorptionDurationMinutes,
            Float absorptionConfidence, Float portionGrams,
            Float originalPortionGrams, Float originalCarbsGrams,
            String clientEventId, boolean pendingSync) {
        this.id = clean(id);
        this.clientEventId = clean(clientEventId);
        this.pendingSync = pendingSync;
        this.occurredAtMs = occurredAtMs;
        this.mealText = clean(mealText);
        this.carbsGrams = nonNegative(carbsGrams);
        this.carbsPresent = carbsPresent;
        this.portionGrams = optionalNonNegative(portionGrams);
        this.originalPortionGrams = optionalNonNegative(
                originalPortionGrams);
        this.originalCarbsGrams = optionalNonNegative(originalCarbsGrams);
        this.carbsSource = clean(carbsSource);
        this.insulinUnits = nonNegative(insulinUnits);
        this.insulinType = clean(insulinType);
        this.insulinName = clean(insulinName);
        this.analysisId = clean(analysisId);
        this.aiConfidence = Math.max(0.0f, Math.min(1.0f, aiConfidence));
        this.absorptionSpeed = optionalUnitValue(absorptionSpeed);
        this.absorptionPeakMinutes = optionalPositive(
                absorptionPeakMinutes);
        this.absorptionDurationMinutes = optionalPositive(
                absorptionDurationMinutes);
        this.absorptionConfidence = optionalUnitValue(absorptionConfidence);
    }

    static IntakeEvent fromJson(JSONObject json) throws JSONException {
        boolean carbsPresent=json.has("carbs_g")&&!json.isNull("carbs_g");
        return new IntakeEvent(
                nullableString(json, "id",
                        nullableString(json, "client_event_id", "")),
                json.optLong("occurred_at_ms", 0L),
                nullableString(json, "meal_text", ""),
                (float) json.optDouble("carbs_g", 0.0),
                carbsPresent,
                nullableString(json, "carbs_source", "manual"),
                (float) json.optDouble("insulin_units", 0.0),
                nullableString(json, "insulin_type", ""),
                nullableString(json, "insulin_name", ""),
                nullableString(json, "analysis_id", ""),
                (float) json.optDouble("ai_confidence", 0.0),
                optionalFloat(json, "absorption_speed"),
                optionalInteger(json, "absorption_peak_minutes"),
                optionalInteger(json, "absorption_duration_minutes"),
                optionalFloat(json, "absorption_confidence"),
                optionalFloat(json, "portion_g"),
                optionalFloat(json, "original_portion_g"),
                optionalFloat(json, "original_carbs_g"),
                nullableString(json, "client_event_id", ""),
                json.optBoolean("pending_sync", false));
    }

    private static String nullableString(JSONObject json, String key,
            String fallback) {
        return json.has(key) && !json.isNull(key)
                ? json.optString(key, fallback) : fallback;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        if (!clientEventId.isEmpty()) {
            json.put("client_event_id", clientEventId);
        }
        if (pendingSync) json.put("pending_sync", true);
        json.put("occurred_at_ms", occurredAtMs);
        json.put("meal_text", mealText);
        if (carbsPresent) {
            json.put("carbs_g", carbsGrams);
        }
        if (portionGrams != null) {
            json.put("portion_g", portionGrams);
        }
        if (originalPortionGrams != null) {
            json.put("original_portion_g", originalPortionGrams);
        }
        if (originalCarbsGrams != null) {
            json.put("original_carbs_g", originalCarbsGrams);
        }
        json.put("carbs_source", carbsSource);
        json.put("insulin_units", insulinUnits);
        json.put("insulin_type", insulinType);
        json.put("insulin_name", insulinName);
        json.put("analysis_id", analysisId);
        json.put("ai_confidence", aiConfidence);
        if (absorptionSpeed != null) {
            json.put("absorption_speed", absorptionSpeed);
        }
        if (absorptionPeakMinutes != null) {
            json.put("absorption_peak_minutes", absorptionPeakMinutes);
        }
        if (absorptionDurationMinutes != null) {
            json.put("absorption_duration_minutes", absorptionDurationMinutes);
        }
        if (absorptionConfidence != null) {
            json.put("absorption_confidence", absorptionConfidence);
        }
        return json;
    }

    public boolean hasMeal() {
        return carbsPresent || !mealText.isEmpty();
    }

    /** Distinguishes an explicit zero-carbohydrate meal from no carb field. */
    public boolean hasCarbs() {
        return carbsPresent;
    }

    public boolean hasInsulin() {
        return insulinUnits > 0.0f;
    }

    public boolean hasAbsorptionSpeed() {
        return absorptionSpeed != null;
    }

    public boolean hasEditablePortion() {
        return !pendingSync && hasMeal() && portionGrams != null
                && originalPortionGrams != null
                && originalPortionGrams > 0.0f
                && originalCarbsGrams != null;
    }

    public float consumedFraction() {
        if (!hasEditablePortion()) return 1.0f;
        return Math.max(0.0f, Math.min(1.0f,
                portionGrams / originalPortionGrams));
    }

    public String insulinDisplayName() {
        if (!insulinName.isEmpty()) {
            return insulinName;
        }
        return insulinType;
    }

    private static float nonNegative(float value) {
        return Float.isFinite(value) && value > 0.0f ? value : 0.0f;
    }

    private static Float optionalFloat(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) return null;
        double value = json.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? (float) value : null;
    }

    private static Integer optionalInteger(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) return null;
        double value = json.optDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return (int) value;
    }

    private static Float optionalUnitValue(Float value) {
        return value != null && Float.isFinite(value)
                && value >= 0.0f && value <= 1.0f ? value : null;
    }

    private static Float optionalNonNegative(Float value) {
        return value != null && Float.isFinite(value) && value >= 0.0f
                ? value : null;
    }

    private static Integer optionalPositive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
