package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One authoritative result from the unified intake chat.
 *
 * <p>The backend has already applied the action represented by this object.
 * {@link #events} contains affected records that remain active, while
 * {@link #deletedEventIds} contains tombstones created by the same result.</p>
 */
final class IntakeChatTurn {
    private static final int MAX_AFFECTED_RECORDS = 24;
    private static final int MAX_ASSISTANT_CHARS = 4_000;
    private static final int MAX_TRANSCRIPT_CHARS = 8_000;
    static final String OUTCOME_APPLIED = "applied";
    static final String OUTCOME_CLARIFICATION = "clarification";
    static final String OUTCOME_UNDONE = "undone";
    static final String OUTCOME_ALREADY_UNDONE = "already_undone";
    static final String OUTCOME_NO_CHANGE = "no_change";

    final String sessionId;
    final String clientTurnId;
    final String assistantMessage;
    final String transcript;
    final String outcome;
    final String actionId;
    final List<IntakeEvent> events;
    final List<String> deletedEventIds;

    private IntakeChatTurn(String sessionId, String clientTurnId,
            String assistantMessage, String transcript, String outcome,
            String actionId, List<IntakeEvent> events,
            List<String> deletedEventIds) {
        this.sessionId = IntakeEvent.clean(sessionId);
        this.clientTurnId = IntakeEvent.clean(clientTurnId);
        this.assistantMessage = IntakeEvent.clean(assistantMessage);
        this.transcript = IntakeEvent.clean(transcript);
        this.outcome = IntakeEvent.clean(outcome);
        this.actionId = IntakeEvent.clean(actionId);
        this.events = Collections.unmodifiableList(events);
        this.deletedEventIds = Collections.unmodifiableList(deletedEventIds);
    }

    static IntakeChatTurn fromJson(JSONObject json) throws JSONException {
        return parse(json, true);
    }

    /** Undo responses intentionally omit conversation-only fields. */
    static IntakeChatTurn fromUndoJson(JSONObject json) throws JSONException {
        return parse(json, false);
    }

    private static IntakeChatTurn parse(JSONObject json, boolean conversationTurn)
            throws JSONException {
        if (json == null) throw new JSONException("Missing intake-chat result");
        String sessionId = conversationTurn
                ? requireUuid(json, "session_id") : "";
        String clientTurnId = conversationTurn
                ? requireUuid(json, "client_turn_id") : "";
        String assistantMessage = conversationTurn
                ? requireString(json, "assistant_message", false,
                        MAX_ASSISTANT_CHARS) : "";
        String transcript = conversationTurn
                ? requireString(json, "transcript", true,
                        MAX_TRANSCRIPT_CHARS) : "";
        String outcome = requireString(json, "outcome", false, 32);
        String actionId = optionalUuid(json, "action_id");

        if (!validOutcome(outcome, conversationTurn)) {
            throw new JSONException("Invalid intake-chat outcome");
        }
        String cleanOutcome = outcome;
        String cleanActionId = actionId;
        boolean actionRequired = OUTCOME_APPLIED.equals(cleanOutcome)
                || OUTCOME_UNDONE.equals(cleanOutcome)
                || OUTCOME_ALREADY_UNDONE.equals(cleanOutcome);
        if (actionRequired && cleanActionId.isEmpty()) {
            throw new JSONException("Action result has no action ID");
        }
        if (!actionRequired && !cleanActionId.isEmpty()) {
            throw new JSONException("Non-action result has an action ID");
        }
        JSONArray rawEvents = requireArray(json, "events");
        if (rawEvents.length() > MAX_AFFECTED_RECORDS) {
            throw new JSONException("Too many intake-chat events");
        }
        ArrayList<IntakeEvent> events = new ArrayList<>(rawEvents.length());
        Set<String> activeIds = new HashSet<>();
        for (int index = 0; index < rawEvents.length(); index++) {
            JSONObject raw = rawEvents.getJSONObject(index);
            IntakeEvent event = parseAuthoritativeEvent(raw);
            if (!activeIds.add(event.id)) {
                throw new JSONException("Invalid or duplicate intake-chat event");
            }
            events.add(event);
        }

        JSONArray rawDeleted = requireArray(json, "deleted_event_ids");
        if (rawDeleted.length() > MAX_AFFECTED_RECORDS) {
            throw new JSONException("Too many intake-chat tombstones");
        }
        ArrayList<String> deleted = new ArrayList<>(rawDeleted.length());
        Set<String> deletedIds = new HashSet<>();
        for (int index = 0; index < rawDeleted.length(); index++) {
            String id = requireUuid(rawDeleted, index,
                    "deleted_event_ids");
            if (!deletedIds.add(id) || activeIds.contains(id)) {
                throw new JSONException("Invalid intake-chat tombstone");
            }
            deleted.add(id);
        }
        boolean hasDelta = !events.isEmpty() || !deleted.isEmpty();
        if (actionRequired && !hasDelta) {
            throw new JSONException("Action result has no record delta");
        }
        if (OUTCOME_APPLIED.equals(cleanOutcome) && events.isEmpty()) {
            throw new JSONException("Applied result has no active record");
        }
        if (!actionRequired && hasDelta) {
            throw new JSONException("Non-action result changed intake records");
        }

        return new IntakeChatTurn(sessionId, clientTurnId,
                assistantMessage, transcript, outcome, actionId,
                events, deleted);
    }

    private static boolean validOutcome(String value,
            boolean conversationTurn) {
        if (conversationTurn) {
            return OUTCOME_APPLIED.equals(value)
                    || OUTCOME_CLARIFICATION.equals(value)
                    || OUTCOME_UNDONE.equals(value)
                    || OUTCOME_NO_CHANGE.equals(value);
        }
        return OUTCOME_UNDONE.equals(value)
                || OUTCOME_ALREADY_UNDONE.equals(value);
    }

    private static IntakeEvent parseAuthoritativeEvent(JSONObject json)
            throws JSONException {
        String id = requireUuid(json, "id");
        requireUuid(json, "client_event_id");
        long occurredAt = requireLong(json, "occurred_at_ms", 1L,
                Long.MAX_VALUE);
        String mealText = optionalString(json, "meal_text", 4_000);
        Double carbs = optionalNumber(json, "carbs_g", 0.0, 1_000.0);
        Double portion = optionalNumber(json, "portion_g", 0.0, 10_000.0);
        Double originalPortion = optionalNumber(json, "original_portion_g",
                0.0, 10_000.0);
        Double originalCarbs = optionalNumber(json, "original_carbs_g",
                0.0, 1_000.0);
        String carbsSource = optionalString(json, "carbs_source", 64);
        Double insulinUnits = optionalNumber(json, "insulin_units",
                0.0, 500.0);
        String insulinType = optionalString(json, "insulin_type", 80);
        String insulinName = optionalString(json, "insulin_name", 120);
        String analysisId = nullableUuid(json, "analysis_id");
        requireNumber(json, "ai_confidence", 0.0, 1.0);
        Double absorptionSpeed = optionalNumber(json, "absorption_speed",
                0.0, 1.0);
        Long peak = optionalLong(json, "absorption_peak_minutes", 5L, 720L);
        Long duration = optionalLong(json, "absorption_duration_minutes",
                15L, 1_440L);
        Double absorptionConfidence = optionalNumber(json,
                "absorption_confidence", 0.0, 1.0);
        long createdAt = requireLong(json, "created_at_ms", 1L,
                Long.MAX_VALUE);
        long updatedAt = requireLong(json, "updated_at_ms", 1L,
                Long.MAX_VALUE);
        if (updatedAt < createdAt) {
            throw new JSONException("updated_at_ms precedes created_at_ms");
        }
        requireNull(json, "deleted_at_ms");
        requireFalse(json, "deleted");
        requireLong(json, "sync_version", 1L, Long.MAX_VALUE);
        if (json.has("pending_sync")) {
            throw new JSONException(
                    "Authoritative intake-chat event cannot be pending");
        }

        boolean hasMeal = mealText != null || carbs != null;
        boolean hasAnyInsulin = insulinUnits != null || insulinType != null
                || insulinName != null;
        if (hasMeal == hasAnyInsulin) {
            throw new JSONException(
                    "Intake-chat event must contain exactly one intake kind");
        }
        if (hasMeal) {
            if ((carbs == null) != (carbsSource == null)) {
                throw new JSONException(
                        "Meal carbohydrates and source must appear together");
            }
            boolean aiEstimate = "ai_estimate".equals(carbsSource);
            if (aiEstimate != (analysisId != null)) {
                throw new JSONException(
                        "AI meal source and analysis identity are inconsistent");
            }
        } else {
            if (insulinUnits == null || insulinUnits <= 0.0
                    || !("rapid".equals(insulinType)
                    && "NovoRapid".equals(insulinName)
                    || "long".equals(insulinType)
                    && "Tresiba".equals(insulinName))) {
                throw new JSONException("Invalid authoritative insulin dose");
            }
            if (portion != null || originalPortion != null
                    || originalCarbs != null || carbsSource != null
                    || analysisId != null || absorptionSpeed != null
                    || peak != null || duration != null
                    || absorptionConfidence != null) {
                throw new JSONException(
                        "Insulin event contains meal-only content");
            }
        }

        IntakeEvent result = IntakeEvent.fromJson(json);
        if (!id.equals(result.id) || result.occurredAtMs != occurredAt
                || result.pendingSync || !(result.hasMeal() ^ result.hasInsulin())) {
            throw new JSONException("Authoritative intake event was normalized");
        }
        return result;
    }

    private static JSONArray requireArray(JSONObject json, String key)
            throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof JSONArray)) {
            throw new JSONException(key + " must be an array");
        }
        return (JSONArray) raw;
    }

    private static String requireString(JSONObject json, String key,
            boolean allowEmpty, int maxLength) throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof String)) {
            throw new JSONException(key + " must be a string");
        }
        String value = (String) raw;
        if (!value.equals(value.trim()) || (!allowEmpty && value.isEmpty())
                || value.length() > maxLength) {
            throw new JSONException(key + " is invalid");
        }
        return value;
    }

    private static String optionalString(JSONObject json, String key,
            int maxLength) throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        if (json.isNull(key)) return null;
        return requireString(json, key, false, maxLength);
    }

    private static String requireUuid(JSONObject json, String key)
            throws JSONException {
        return validateUuid(requireString(json, key, false, 36), key);
    }

    private static String requireUuid(JSONArray json, int index, String key)
            throws JSONException {
        Object raw = json.get(index);
        if (!(raw instanceof String)) {
            throw new JSONException(key + " must contain UUID strings");
        }
        String value = (String) raw;
        if (!value.equals(value.trim())) {
            throw new JSONException(key + " contains a non-canonical UUID");
        }
        return validateUuid(value, key);
    }

    private static String optionalUuid(JSONObject json, String key)
            throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        if (json.isNull(key)) return "";
        return requireUuid(json, key);
    }

    private static String nullableUuid(JSONObject json, String key)
            throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        if (json.isNull(key)) return null;
        return requireUuid(json, key);
    }

    private static String validateUuid(String value, String key)
            throws JSONException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
        } catch (IllegalArgumentException error) {
            throw new JSONException(key + " is not a valid UUID");
        }
        return value;
    }

    private static double requireNumber(JSONObject json, String key,
            double minimum, double maximum) throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        Object raw = json.get(key);
        if (!(raw instanceof Number)) {
            throw new JSONException(key + " must be numeric");
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new JSONException(key + " is outside its valid range");
        }
        return value;
    }

    private static Double optionalNumber(JSONObject json, String key,
            double minimum, double maximum) throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        if (json.isNull(key)) return null;
        return requireNumber(json, key, minimum, maximum);
    }

    private static long requireLong(JSONObject json, String key, long minimum,
            long maximum) throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        Object raw = json.get(key);
        if (!(raw instanceof Number)) {
            throw new JSONException(key + " must be an integer");
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < minimum || value > maximum) {
            throw new JSONException(key + " is outside its valid range");
        }
        return ((Number) raw).longValue();
    }

    private static Long optionalLong(JSONObject json, String key, long minimum,
            long maximum) throws JSONException {
        if (!json.has(key)) throw new JSONException("Missing " + key);
        if (json.isNull(key)) return null;
        return requireLong(json, key, minimum, maximum);
    }

    private static void requireNull(JSONObject json, String key)
            throws JSONException {
        if (!json.has(key) || !json.isNull(key)) {
            throw new JSONException(key + " must be null for an active event");
        }
    }

    private static void requireFalse(JSONObject json, String key)
            throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof Boolean) || (Boolean) raw) {
            throw new JSONException(key + " must be false");
        }
    }
}
