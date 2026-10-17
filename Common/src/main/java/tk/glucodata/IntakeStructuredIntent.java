package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * Strict, non-LLM transport for facts entered through the compact intake dock.
 *
 * <p>The JSON is created once and then persisted byte-for-byte alongside the
 * turn id.  This is important: a transport retry must not mint a new operation
 * id or subtly change a medical write that may already have committed.</p>
 */
final class IntakeStructuredIntent {
    static final int VERSION = 1;
    static final String ACTION_ADD = "add";
    static final String ACTION_UPDATE = "update";
    static final String ENTITY_INSULIN = "insulin";
    static final String ENTITY_MEAL = "meal";
    private static final int MAX_WIRE_CHARS = 12_000;

    private IntakeStructuredIntent() {}

    static String insulin(String action, String targetEventId,
            long occurredAtMs, String insulinName, float insulinUnits) {
        return insulin(action, targetEventId, occurredAtMs, insulinName,
                insulinUnits, UUID.randomUUID().toString());
    }

    static String insulin(String action, String targetEventId,
            long occurredAtMs, String insulinName, float insulinUnits,
            String clientOperationId) {
        requireAction(action, targetEventId);
        requireUuid(clientOperationId, "client_operation_id");
        requireTime(occurredAtMs);
        String cleanName = IntakeEvent.clean(insulinName);
        if (!("NovoRapid".equals(cleanName) || "Tresiba".equals(cleanName))) {
            throw new IllegalArgumentException("Unsupported insulin product");
        }
        if (!Float.isFinite(insulinUnits) || insulinUnits <= 0.0f
                || insulinUnits > 500.0f) {
            throw new IllegalArgumentException("Invalid insulin dose");
        }
        try {
            JSONObject operation = baseOperation(action, ENTITY_INSULIN,
                    targetEventId, occurredAtMs, clientOperationId)
                    .put("insulin_name", cleanName)
                    .put("insulin_units", insulinUnits)
                    .put("meal_text", JSONObject.NULL)
                    .put("carbs_g", JSONObject.NULL)
                    .put("portion_g", JSONObject.NULL);
            return envelope(operation).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String meal(String action, String targetEventId,
            long occurredAtMs, String mealText, float carbsGrams) {
        return meal(action, targetEventId, occurredAtMs, mealText,
                Float.valueOf(carbsGrams), UUID.randomUUID().toString());
    }

    static String meal(String action, String targetEventId,
            long occurredAtMs, String mealText, Float carbsGrams) {
        return meal(action, targetEventId, occurredAtMs, mealText,
                carbsGrams, UUID.randomUUID().toString());
    }

    static String meal(String action, String targetEventId,
            long occurredAtMs, String mealText, float carbsGrams,
            String clientOperationId) {
        return meal(action, targetEventId, occurredAtMs, mealText,
                Float.valueOf(carbsGrams), clientOperationId);
    }

    static String meal(String action, String targetEventId,
            long occurredAtMs, String mealText, Float carbsGrams,
            String clientOperationId) {
        requireAction(action, targetEventId);
        requireUuid(clientOperationId, "client_operation_id");
        requireTime(occurredAtMs);
        String cleanMeal = IntakeEvent.clean(mealText);
        if (cleanMeal.length() > 4_000
                || ACTION_ADD.equals(action) && cleanMeal.isEmpty()
                && carbsGrams == null) {
            throw new IllegalArgumentException("Invalid meal description");
        }
        if (carbsGrams != null && (!Float.isFinite(carbsGrams)
                || carbsGrams < 0.0f || carbsGrams > 1_000.0f)) {
            throw new IllegalArgumentException("Invalid carbohydrate amount");
        }
        try {
            JSONObject operation = baseOperation(action, ENTITY_MEAL,
                    targetEventId, occurredAtMs, clientOperationId)
                    .put("insulin_name", JSONObject.NULL)
                    .put("insulin_units", JSONObject.NULL)
                    .put("meal_text", cleanMeal.isEmpty()
                            ? JSONObject.NULL : cleanMeal)
                    .put("carbs_g", carbsGrams == null
                            ? JSONObject.NULL : carbsGrams)
                    .put("portion_g", JSONObject.NULL);
            return envelope(operation).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Defensive validation at the HTTP boundary; generated payloads are tiny. */
    static boolean isValidWirePayload(String raw) {
        String clean = IntakeEvent.clean(raw);
        if (clean.isEmpty() || clean.length() > MAX_WIRE_CHARS) return false;
        try {
            JSONObject envelope = new JSONObject(clean);
            if (envelope.length() != 2 || envelope.getInt("version") != VERSION) {
                return false;
            }
            JSONArray operations = envelope.getJSONArray("operations");
            if (operations.length() != 1) return false;
            JSONObject operation = operations.getJSONObject(0);
            if (operation.length() != 10) return false;
            String action = operation.getString("action");
            String entity = operation.getString("entity");
            requireUuid(operation.getString("client_operation_id"),
                    "client_operation_id");
            boolean update = ACTION_UPDATE.equals(action);
            if (!(ACTION_ADD.equals(action) || update)) return false;
            if (update) {
                requireUuid(operation.getString("target_event_id"),
                        "target_event_id");
            } else if (!operation.isNull("target_event_id")) {
                return false;
            }
            requireTime(operation.getLong("occurred_at_ms"));
            if (ENTITY_INSULIN.equals(entity)) {
                String name = operation.getString("insulin_name");
                double units = operation.getDouble("insulin_units");
                return ("NovoRapid".equals(name) || "Tresiba".equals(name))
                        && Double.isFinite(units) && units > 0.0
                        && units <= 500.0
                        && operation.isNull("meal_text")
                        && operation.isNull("carbs_g")
                        && operation.isNull("portion_g");
            }
            if (ENTITY_MEAL.equals(entity)) {
                boolean hasMeal = !operation.isNull("meal_text");
                boolean hasCarbs = !operation.isNull("carbs_g");
                String meal = hasMeal ? IntakeEvent.clean(
                        operation.getString("meal_text")) : "";
                double carbs = hasCarbs
                        ? operation.getDouble("carbs_g") : 0.0;
                return (!hasMeal || !meal.isEmpty()
                        && meal.length() <= 4_000)
                        && (!hasCarbs || Double.isFinite(carbs)
                        && carbs >= 0.0 && carbs <= 1_000.0)
                        && (update || hasMeal || hasCarbs)
                        && operation.isNull("insulin_name")
                        && operation.isNull("insulin_units")
                        && operation.isNull("portion_g");
            }
            return false;
        } catch (JSONException | IllegalArgumentException error) {
            return false;
        }
    }

    private static JSONObject baseOperation(String action, String entity,
            String targetEventId, long occurredAtMs,
            String clientOperationId) throws JSONException {
        return new JSONObject()
                .put("client_operation_id", clientOperationId)
                .put("action", action)
                .put("entity", entity)
                .put("target_event_id", ACTION_UPDATE.equals(action)
                        ? targetEventId : JSONObject.NULL)
                .put("occurred_at_ms", occurredAtMs);
    }

    private static JSONObject envelope(JSONObject operation)
            throws JSONException {
        return new JSONObject()
                .put("version", VERSION)
                .put("operations", new JSONArray().put(operation));
    }

    private static void requireAction(String action, String targetEventId) {
        if (!(ACTION_ADD.equals(action) || ACTION_UPDATE.equals(action))) {
            throw new IllegalArgumentException("Unsupported action");
        }
        String cleanTarget = IntakeEvent.clean(targetEventId);
        if (ACTION_UPDATE.equals(action)) {
            requireUuid(cleanTarget, "target_event_id");
        } else if (!cleanTarget.isEmpty()) {
            throw new IllegalArgumentException("Add cannot have a target");
        }
    }

    private static void requireTime(long occurredAtMs) {
        // Allow a tiny clock/tap boundary, but never a scheduled future dose.
        if (occurredAtMs <= 0L
                || occurredAtMs > System.currentTimeMillis() + 2_000L) {
            throw new IllegalArgumentException("Invalid occurrence time");
        }
    }

    private static void requireUuid(String raw, String field) {
        String clean = IntakeEvent.clean(raw).toLowerCase(Locale.ROOT);
        try {
            UUID parsed = UUID.fromString(clean);
            if (!parsed.toString().equals(clean)) {
                throw new IllegalArgumentException(field + " is non-canonical");
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " is invalid", error);
        }
    }
}
