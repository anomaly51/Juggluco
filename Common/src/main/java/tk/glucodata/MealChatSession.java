package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Android view of one backend-owned meal conversation. */
final class MealChatSession {
    static final class Message {
        final String id;
        final String role;
        final String text;
        final int photoCount;
        final boolean hadAudio;
        final long createdAtMs;

        private Message(String id, String role, String text, int photoCount,
                boolean hadAudio, long createdAtMs) {
            this.id = IntakeEvent.clean(id);
            this.role = IntakeEvent.clean(role);
            this.text = IntakeEvent.clean(text);
            this.photoCount = Math.max(0, photoCount);
            this.hadAudio = hadAudio;
            this.createdAtMs = createdAtMs;
        }

        static Message fromJson(JSONObject json) {
            return new Message(json.optString("id", ""),
                    json.optString("role", ""),
                    json.optString("text", ""),
                    json.optInt("photo_count", 0),
                    json.optBoolean("had_audio", false),
                    json.optLong("created_at_ms", 0L));
        }
    }

    static final class Item {
        final String name;
        final float portionGrams;
        final float carbsGrams;

        private Item(String name, float portionGrams, float carbsGrams) {
            this.name = IntakeEvent.clean(name);
            this.portionGrams = finiteNonNegative(portionGrams);
            this.carbsGrams = finiteNonNegative(carbsGrams);
        }

        static Item fromJson(JSONObject json) {
            return new Item(json.optString("name", ""),
                    (float) json.optDouble("portion_g", 0.0),
                    (float) json.optDouble("carbs_g", 0.0));
        }
    }

    static final class Proposal {
        final String mealName;
        final String mealDescription;
        final float totalPortionGrams;
        final List<Item> items;
        final float estimatedCarbsGrams;
        final float carbsLowGrams;
        final float carbsHighGrams;
        final float confidence;
        final Float absorptionSpeed;
        final Integer absorptionPeakMinutes;
        final Integer absorptionDurationMinutes;
        final Float absorptionConfidence;
        final List<String> warnings;

        private Proposal(String mealName, String mealDescription,
                float totalPortionGrams, List<Item> items,
                float estimatedCarbsGrams, float carbsLowGrams,
                float carbsHighGrams, float confidence, Float absorptionSpeed,
                Integer absorptionPeakMinutes,
                Integer absorptionDurationMinutes,
                Float absorptionConfidence,
                List<String> warnings) {
            this.mealName = IntakeEvent.clean(mealName);
            this.mealDescription = IntakeEvent.clean(mealDescription);
            this.totalPortionGrams = finiteNonNegative(totalPortionGrams);
            this.items = Collections.unmodifiableList(items);
            this.estimatedCarbsGrams = finiteNonNegative(estimatedCarbsGrams);
            this.carbsLowGrams = finiteNonNegative(carbsLowGrams);
            this.carbsHighGrams = finiteNonNegative(carbsHighGrams);
            this.confidence = Math.max(0.0f, Math.min(1.0f, confidence));
            this.absorptionSpeed = optionalUnitValue(absorptionSpeed);
            this.absorptionPeakMinutes = optionalPositive(
                    absorptionPeakMinutes);
            this.absorptionDurationMinutes = optionalPositive(
                    absorptionDurationMinutes);
            this.absorptionConfidence = optionalUnitValue(
                    absorptionConfidence);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        static Proposal fromJson(JSONObject json) {
            if (json == null) return null;
            ArrayList<Item> items = new ArrayList<>();
            JSONArray rawItems = json.optJSONArray("items");
            if (rawItems != null) {
                for (int index = 0; index < rawItems.length(); index++) {
                    JSONObject item = rawItems.optJSONObject(index);
                    if (item != null) items.add(Item.fromJson(item));
                }
            }
            ArrayList<String> warnings = new ArrayList<>();
            JSONArray rawWarnings = json.optJSONArray("warnings");
            if (rawWarnings != null) {
                for (int index = 0; index < rawWarnings.length(); index++) {
                    String warning = IntakeEvent.clean(
                            rawWarnings.optString(index, ""));
                    if (!warning.isEmpty()) warnings.add(warning);
                }
            }
            return new Proposal(json.optString("meal_name", ""),
                    json.optString("meal_description", ""),
                    (float) json.optDouble("total_portion_g", 0.0), items,
                    (float) json.optDouble("estimated_carbs_g", 0.0),
                    (float) json.optDouble("carbs_low_g", 0.0),
                    (float) json.optDouble("carbs_high_g", 0.0),
                    (float) json.optDouble("confidence", 0.0),
                    optionalFloat(json, "absorption_speed"),
                    optionalInteger(json, "absorption_peak_minutes"),
                    optionalInteger(json, "absorption_duration_minutes"),
                    optionalFloat(json, "absorption_confidence"), warnings);
        }

        private static Float optionalFloat(JSONObject json, String name) {
            if (!json.has(name) || json.isNull(name)) return null;
            double value = json.optDouble(name, Double.NaN);
            return Double.isFinite(value) ? (float) value : null;
        }

        private static Integer optionalInteger(JSONObject json, String name) {
            if (!json.has(name) || json.isNull(name)) return null;
            double value = json.optDouble(name, Double.NaN);
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

        private static Integer optionalPositive(Integer value) {
            return value != null && value > 0 ? value : null;
        }
    }

    static final class Turn {
        final String sessionId;
        final Message assistantMessage;
        final Proposal proposal;
        final boolean readyToConfirm;

        private Turn(String sessionId, Message assistantMessage,
                Proposal proposal, boolean readyToConfirm) {
            this.sessionId = IntakeEvent.clean(sessionId);
            this.assistantMessage = assistantMessage;
            this.proposal = proposal;
            this.readyToConfirm = readyToConfirm;
        }

        static Turn fromJson(JSONObject json) throws JSONException {
            JSONObject assistant = json.optJSONObject("assistant_message");
            if (assistant == null) {
                throw new JSONException("Missing assistant message");
            }
            return new Turn(json.optString("session_id", ""),
                    Message.fromJson(assistant),
                    Proposal.fromJson(json.optJSONObject("proposal")),
                    json.optBoolean("ready_to_confirm", false));
        }
    }

    final String id;
    final String clientEventId;
    final long occurredAtMs;
    final String status;
    final List<Message> messages;
    final Proposal proposal;
    final boolean readyToConfirm;
    final String confirmedIntakeId;

    private MealChatSession(String id, String clientEventId,
            long occurredAtMs, String status, List<Message> messages,
            Proposal proposal, boolean readyToConfirm,
            String confirmedIntakeId) {
        this.id = IntakeEvent.clean(id);
        this.clientEventId = IntakeEvent.clean(clientEventId);
        this.occurredAtMs = occurredAtMs;
        this.status = IntakeEvent.clean(status);
        this.messages = Collections.unmodifiableList(messages);
        this.proposal = proposal;
        this.readyToConfirm = readyToConfirm;
        this.confirmedIntakeId = IntakeEvent.clean(confirmedIntakeId);
    }

    static MealChatSession fromJson(JSONObject json) {
        ArrayList<Message> messages = new ArrayList<>();
        JSONArray rawMessages = json.optJSONArray("messages");
        if (rawMessages != null) {
            for (int index = 0; index < rawMessages.length(); index++) {
                JSONObject message = rawMessages.optJSONObject(index);
                if (message != null) messages.add(Message.fromJson(message));
            }
        }
        return new MealChatSession(json.optString("id", ""),
                json.optString("client_event_id", ""),
                json.optLong("occurred_at_ms", 0L),
                json.optString("status", "active"), messages,
                Proposal.fromJson(json.optJSONObject("proposal")),
                json.optBoolean("ready_to_confirm", false),
                json.optString("confirmed_intake_id", ""));
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) && value >= 0.0f ? value : 0.0f;
    }
}
