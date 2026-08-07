package tk.glucodata;

import org.json.JSONException;
import org.json.JSONObject;

/** One immutable, idempotent create command in the phone-owned durable outbox. */
final class PendingIntakeOperation {
    private static final String INSULIN = "insulin";
    private static final String MEAL = "meal";

    final String kind;
    final String clientEventId;
    final long occurredAtMs;
    final String mealText;
    final float carbsGrams;
    final Float portionGrams;
    final float insulinUnits;
    final String insulinName;

    private PendingIntakeOperation(String kind, String clientEventId,
            long occurredAtMs, String mealText, float carbsGrams,
            Float portionGrams, float insulinUnits, String insulinName) {
        this.kind = kind;
        this.clientEventId = IntakeEvent.clean(clientEventId);
        this.occurredAtMs = occurredAtMs;
        this.mealText = IntakeEvent.clean(mealText);
        this.carbsGrams = carbsGrams;
        this.portionGrams = portionGrams;
        this.insulinUnits = insulinUnits;
        this.insulinName = IntakeEvent.clean(insulinName);
    }

    static PendingIntakeOperation insulin(IntakeDraft draft) {
        return new PendingIntakeOperation(INSULIN, draft.clientEventId,
                draft.occurredAtMs, "", 0.0f, null,
                draft.insulinUnits, draft.insulinName);
    }

    static PendingIntakeOperation meal(String clientEventId,
            long occurredAtMs, String mealText, float carbsGrams,
            Float portionGrams) {
        return new PendingIntakeOperation(MEAL, clientEventId, occurredAtMs,
                mealText, carbsGrams, portionGrams, 0.0f, "");
    }

    static PendingIntakeOperation fromJson(JSONObject json)
            throws JSONException {
        String kind = json.getString("kind");
        String clientId = json.getString("client_event_id");
        long occurred = json.getLong("occurred_at_ms");
        Float portion = json.has("portion_g") && !json.isNull("portion_g")
                ? (float) json.getDouble("portion_g") : null;
        PendingIntakeOperation value = new PendingIntakeOperation(kind,
                clientId, occurred, json.optString("meal_text", ""),
                (float) json.optDouble("carbs_g", 0.0), portion,
                (float) json.optDouble("insulin_units", 0.0),
                json.optString("insulin_name", ""));
        if ((!INSULIN.equals(kind) && !MEAL.equals(kind))
                || value.clientEventId.isEmpty() || occurred <= 0L) {
            throw new JSONException("Invalid pending intake command");
        }
        return value;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("kind", kind);
        json.put("client_event_id", clientEventId);
        json.put("occurred_at_ms", occurredAtMs);
        if (MEAL.equals(kind)) {
            json.put("meal_text", mealText);
            json.put("carbs_g", carbsGrams);
            if (portionGrams != null) json.put("portion_g", portionGrams);
        } else {
            json.put("insulin_units", insulinUnits);
            json.put("insulin_name", insulinName);
        }
        return json;
    }

    IntakeEvent localEvent() throws JSONException {
        JSONObject json = toJson();
        json.put("id", "local:" + clientEventId);
        json.put("pending_sync", true);
        if (MEAL.equals(kind)) {
            json.put("carbs_source", "manual");
            if (portionGrams != null) {
                json.put("original_portion_g", portionGrams);
                json.put("original_carbs_g", carbsGrams);
            }
        } else {
            json.put("insulin_type", "NovoRapid".equals(insulinName)
                    ? "rapid" : "long");
        }
        return IntakeEvent.fromJson(json);
    }

    IntakeEvent upload(IntakeApiClient api) throws Exception {
        if (MEAL.equals(kind)) {
            return api.createManualMeal(clientEventId, occurredAtMs,
                    mealText, carbsGrams, portionGrams);
        }
        IntakeDraft draft = new IntakeDraft();
        draft.clientEventId = clientEventId;
        draft.occurredAtMs = occurredAtMs;
        draft.insulinUnits = insulinUnits;
        draft.insulinName = insulinName;
        return api.createInsulin(draft);
    }
}
