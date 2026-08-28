package tk.glucodata;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/** Immutable identity of one backend-owned unified intake conversation. */
final class IntakeChatSession {
    final String id;
    final String clientSessionId;
    final long createdAtMs;
    final long updatedAtMs;

    private IntakeChatSession(String id, String clientSessionId,
            long createdAtMs, long updatedAtMs) {
        this.id = IntakeEvent.clean(id);
        this.clientSessionId = IntakeEvent.clean(clientSessionId);
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    static IntakeChatSession fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("Missing intake-chat session");
        IntakeChatSession value = new IntakeChatSession(
                requireUuid(json, "id"),
                requireUuid(json, "client_session_id"),
                requirePositiveLong(json, "created_at_ms"),
                requirePositiveLong(json, "updated_at_ms"));
        if (value.updatedAtMs < value.createdAtMs) {
            throw new JSONException("Invalid intake-chat session");
        }
        return value;
    }

    private static String requireUuid(JSONObject json, String key)
            throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof String)) {
            throw new JSONException(key + " must be a UUID string");
        }
        String value = (String) raw;
        if (!value.equals(value.trim())) {
            throw new JSONException(key + " is not canonical");
        }
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

    private static long requirePositiveLong(JSONObject json, String key)
            throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof Number)) {
            throw new JSONException(key + " must be an integer");
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value <= 0.0
                || value != Math.rint(value) || value > Long.MAX_VALUE) {
            throw new JSONException(key + " is invalid");
        }
        return ((Number) raw).longValue();
    }
}
