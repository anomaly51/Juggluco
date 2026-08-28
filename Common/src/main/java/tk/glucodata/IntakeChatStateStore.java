package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** App-private durable identity for one active unified intake conversation. */
final class IntakeChatStateStore {
    private static final String PREFS = "intake_chat_state";
    private static final String KEY_STATE = "state";
    private static final int STATE_SCHEMA_VERSION = 3;
    private static final long STATE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long PENDING_MEDIA_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_SAVED_LINES = 50;

    static final class Line {
        final boolean user;
        final String text;
        final int photoCount;

        Line(boolean user, String text, int photoCount) {
            this.user = user;
            this.text = IntakeEvent.clean(text);
            this.photoCount = Math.max(0, Math.min(24, photoCount));
        }
    }

    static final class Pending {
        final String clientTurnId;
        final long occurredAtMs;
        final String text;
        final File audio;
        final List<File> photos;
        final boolean definitiveFailure;
        final boolean commitMayHaveOccurred;
        final int controlKind;

        Pending(String clientTurnId, long occurredAtMs, String text,
                File audio, List<File> photos) {
            this(clientTurnId, occurredAtMs, text, audio, photos,
                    false, false, 0);
        }

        Pending(String clientTurnId, long occurredAtMs, String text,
                File audio, List<File> photos, boolean definitiveFailure,
                boolean commitMayHaveOccurred) {
            this(clientTurnId, occurredAtMs, text, audio, photos,
                    definitiveFailure, commitMayHaveOccurred, 0);
        }

        Pending(String clientTurnId, long occurredAtMs, String text,
                File audio, List<File> photos, boolean definitiveFailure,
                boolean commitMayHaveOccurred, int controlKind) {
            this.clientTurnId = IntakeEvent.clean(clientTurnId);
            this.occurredAtMs = occurredAtMs;
            this.text = IntakeEvent.clean(text);
            this.audio = audio;
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
            this.definitiveFailure = definitiveFailure;
            this.commitMayHaveOccurred = definitiveFailure
                    && commitMayHaveOccurred;
            this.controlKind = Math.max(0, Math.min(2, controlKind));
        }
    }

    static final class State {
        final String clientSessionId;
        final String sessionId;
        final String lastActionId;
        final IntakeChatTurn lastActionTurn;
        final boolean lastActionDeleted;
        final List<Line> lines;
        final Pending pending;

        State(String clientSessionId, String sessionId, String lastActionId,
                IntakeChatTurn lastActionTurn, List<Line> lines,
                Pending pending) {
            this(clientSessionId, sessionId, lastActionId, lastActionTurn,
                    false, lines, pending);
        }

        State(String clientSessionId, String sessionId, String lastActionId,
                IntakeChatTurn lastActionTurn, boolean lastActionDeleted,
                List<Line> lines, Pending pending) {
            this.clientSessionId = IntakeEvent.clean(clientSessionId);
            this.sessionId = IntakeEvent.clean(sessionId);
            this.lastActionId = IntakeEvent.clean(lastActionId);
            this.lastActionTurn = lastActionTurn;
            this.lastActionDeleted = this.lastActionId.isEmpty()
                    && lastActionTurn != null && lastActionDeleted;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.pending = pending;
        }
    }

    private IntakeChatStateStore() {}

    static State load(Context context, String backendFingerprint) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY_STATE, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(raw);
            long savedAtMs = json.getLong("saved_at_ms");
            long ageMs = System.currentTimeMillis() - savedAtMs;
            if (ageMs < 0L || ageMs > STATE_TTL_MS
                    || !backendFingerprint.equals(
                            json.getString("backend_fingerprint"))) {
                clear(context);
                return null;
            }
            String clientSessionId = json.getString("client_session_id");
            if (IntakeEvent.clean(clientSessionId).isEmpty()) {
                clear(context);
                return null;
            }
            String sessionId = json.optString("session_id", "");
            String lastActionId = json.optString("last_action_id", "");
            boolean lastActionDeleted = lastActionId.isEmpty()
                    && json.optBoolean("last_action_deleted", false);
            IntakeChatTurn actionTurn = null;
            if (json.has("last_action_turn")) {
                JSONObject rawActionTurn = json.getJSONObject(
                        "last_action_turn");
                String storedOutcome = rawActionTurn.optString("outcome", "");
                boolean storedUndo = IntakeChatTurn.OUTCOME_UNDONE.equals(
                        storedOutcome)
                        || IntakeChatTurn.OUTCOME_ALREADY_UNDONE.equals(
                                storedOutcome);
                actionTurn = storedUndo
                        ? IntakeChatTurn.fromUndoJson(rawActionTurn)
                        : IntakeChatTurn.fromJson(rawActionTurn);
                boolean appliedSnapshot = IntakeChatTurn.OUTCOME_APPLIED.equals(
                        actionTurn.outcome);
                boolean matchingActiveAction = !lastActionId.isEmpty()
                        && lastActionId.equals(actionTurn.actionId);
                boolean restoredSnapshot = lastActionId.isEmpty()
                        && !lastActionDeleted && storedUndo
                        && !actionTurn.events.isEmpty();
                boolean validSnapshot = matchingActiveAction
                        && appliedSnapshot
                        || lastActionDeleted && appliedSnapshot
                        || restoredSnapshot;
                if (!validSnapshot) {
                    actionTurn = null;
                    lastActionId = "";
                    lastActionDeleted = false;
                }
            } else if (!lastActionId.isEmpty() || lastActionDeleted) {
                lastActionId = "";
                lastActionDeleted = false;
            }
            ArrayList<Line> migratedLines = new ArrayList<>();
            JSONArray rawLines = json.optJSONArray("lines");
            if (rawLines != null) {
                for (int index = 0; index < rawLines.length(); index++) {
                    JSONObject item = rawLines.getJSONObject(index);
                    String text = IntakeEvent.clean(item.optString("text", ""));
                    if (!text.isEmpty()) {
                        Line line = new Line(item.optBoolean("user", false),
                                text, item.optInt("photo_count", 0));
                        if (!isLegacyLocalLine(line)) migratedLines.add(line);
                    }
                }
            }
            int firstLine = Math.max(0,
                    migratedLines.size() - MAX_SAVED_LINES);
            ArrayList<Line> lines = new ArrayList<>(
                    migratedLines.subList(firstLine, migratedLines.size()));
            Pending pending = readPending(context,
                    json.optJSONObject("pending"), savedAtMs);
            return new State(clientSessionId, sessionId, lastActionId,
                    actionTurn, lastActionDeleted, lines, pending);
        } catch (JSONException | RuntimeException error) {
            clear(context);
            return null;
        }
    }

    static boolean save(Context context, String backendFingerprint,
            State state) {
        try {
            JSONObject json = new JSONObject();
            json.put("schema_version", STATE_SCHEMA_VERSION);
            json.put("saved_at_ms", System.currentTimeMillis());
            json.put("backend_fingerprint", backendFingerprint);
            json.put("client_session_id", state.clientSessionId);
            json.put("session_id", state.sessionId);
            json.put("last_action_id", state.lastActionId);
            json.put("last_action_deleted", state.lastActionDeleted);
            if (state.lastActionTurn != null) {
                json.put("last_action_turn", turnJson(state.lastActionTurn));
            }
            JSONArray lines = new JSONArray();
            ArrayList<Line> cleanLines = new ArrayList<>();
            for (Line line : state.lines) {
                if (!isLegacyLocalLine(line)) cleanLines.add(line);
            }
            int start = Math.max(0, cleanLines.size() - MAX_SAVED_LINES);
            for (int index = start; index < cleanLines.size(); index++) {
                Line line = cleanLines.get(index);
                lines.put(new JSONObject()
                        .put("user", line.user)
                        .put("text", line.text)
                        .put("photo_count", line.photoCount));
            }
            json.put("lines", lines);
            if (state.pending != null) {
                Pending pending = state.pending;
                JSONObject rawPending = new JSONObject()
                        .put("client_turn_id", pending.clientTurnId)
                        .put("occurred_at_ms", pending.occurredAtMs)
                        .put("text", pending.text)
                        .put("definitive_failure",
                                pending.definitiveFailure)
                        .put("commit_may_have_occurred",
                                pending.commitMayHaveOccurred)
                        .put("control_kind", pending.controlKind)
                        .put("audio_path", pending.audio == null ? ""
                                : pending.audio.getAbsolutePath());
                JSONArray photos = new JSONArray();
                for (File photo : pending.photos) {
                    photos.put(photo.getAbsolutePath());
                }
                rawPending.put("photo_paths", photos);
                json.put("pending", rawPending);
            }
            return preferences(context).edit()
                    .putString(KEY_STATE, json.toString()).commit();
        } catch (JSONException | RuntimeException error) {
            return false;
        }
    }

    static void clear(Context context) {
        preferences(context).edit().remove(KEY_STATE).commit();
    }

    /**
     * Returns whether an exact intake turn is still awaiting reconciliation.
     *
     * <p>This deliberately checks the durable turn identity rather than
     * calling {@link #load(Context, String)}. A connection change must be
     * vetoed before the backend fingerprint changes; otherwise loading with
     * the new fingerprint would discard the only idempotency key that can
     * prevent a duplicate medical record.</p>
     */
    static boolean hasPendingTurn(Context context) {
        String raw = preferences(context).getString(KEY_STATE, "");
        if (raw == null || raw.isEmpty()) return false;
        try {
            JSONObject state = new JSONObject(raw);
            long ageMs = System.currentTimeMillis()
                    - state.optLong("saved_at_ms", 0L);
            if (ageMs < 0L || ageMs > PENDING_MEDIA_TTL_MS) return false;
            return readPending(context, state.optJSONObject("pending"),
                    state.optLong("saved_at_ms", 0L)) != null;
        } catch (JSONException | RuntimeException error) {
            return false;
        }
    }

    /**
     * Removes only known app-generated legacy copy from assistant-side rows.
     * User-authored text is never inspected so a reported medical fact cannot
     * disappear merely because it happens to resemble an old error message.
     */
    static boolean isLegacyLocalLine(Line line) {
        if (line == null || line.user) return false;
        String text = IntakeEvent.clean(line.text).toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'');
        if (text.isEmpty()) return false;
        if (text.startsWith("ai log:")) return true;
        if (text.contains("audio has invalid stream metadata")) return true;
        if (text.contains("127.0.0.1:8765")
                || text.contains("localhost:8765")) return true;
        if (text.equals("backend changed. a new private conversation has started.")
                || text.equals("connection settings changed. a new private conversation has started.")
                || text.equals("настройки соединения изменены. начат новый приватный диалог.")) {
            return true;
        }
        return text.startsWith("tell me what happened — for example,")
                || text.startsWith("tell me what you ate, attach any useful")
                || text.startsWith("describe a meal or injection.")
                || text.startsWith("расскажите, что вы съели, добавьте любые")
                || text.startsWith("расскажите о еде или уколе.");
    }

    private static Pending readPending(Context context, JSONObject json,
            long savedAtMs)
            throws JSONException {
        if (json == null || System.currentTimeMillis() - savedAtMs
                > PENDING_MEDIA_TTL_MS) return null;
        String turnId = IntakeEvent.clean(json.getString("client_turn_id"));
        long occurredAtMs = json.getLong("occurred_at_ms");
        if (turnId.isEmpty() || occurredAtMs <= 0L) return null;
        File audio = fileOrNull(context,
                json.optString("audio_path", ""));
        if (!json.optString("audio_path", "").isEmpty() && audio == null) {
            return null;
        }
        ArrayList<File> photos = new ArrayList<>();
        JSONArray rawPhotos = json.optJSONArray("photo_paths");
        if (rawPhotos != null) {
            for (int index = 0; index < rawPhotos.length(); index++) {
                File photo = fileOrNull(context,
                        rawPhotos.getString(index));
                if (photo == null) return null;
                photos.add(photo);
            }
        }
        String text = json.optString("text", "");
        if (IntakeEvent.clean(text).isEmpty() && audio == null
                && photos.isEmpty()) return null;
        boolean definitiveFailure = json.optBoolean(
                "definitive_failure", false);
        return new Pending(turnId, occurredAtMs, text, audio, photos,
                definitiveFailure, definitiveFailure && json.optBoolean(
                        "commit_may_have_occurred", false),
                json.optInt("control_kind", 0));
    }

    private static File fileOrNull(Context context, String path) {
        if (IntakeEvent.clean(path).isEmpty()) return null;
        try {
            File mediaRoot = new File(context.getCacheDir(), "intake-media")
                    .getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            String rootPath = mediaRoot.getPath() + File.separator;
            if (!file.getPath().startsWith(rootPath)) return null;
            return file.isFile() && file.length() > 0L ? file : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static JSONObject turnJson(IntakeChatTurn turn)
            throws JSONException {
        JSONObject json = new JSONObject()
                .put("session_id", turn.sessionId)
                .put("client_turn_id", turn.clientTurnId)
                .put("assistant_message", turn.assistantMessage)
                .put("transcript", turn.transcript)
                .put("outcome", turn.outcome)
                .put("action_id", turn.actionId);
        JSONArray events = new JSONArray();
        for (IntakeEvent event : turn.events) {
            events.put(eventJsonForState(event));
        }
        JSONArray deleted = new JSONArray();
        for (String id : turn.deletedEventIds) deleted.put(id);
        return json.put("events", events).put("deleted_event_ids", deleted);
    }

    /** Recreates the strict authoritative shape expected by IntakeChatTurn. */
    private static JSONObject eventJsonForState(IntakeEvent event)
            throws JSONException {
        JSONObject json = new JSONObject()
                .put("id", event.id)
                .put("client_event_id", event.clientEventId)
                .put("occurred_at_ms", event.occurredAtMs)
                .put("meal_text", event.hasMeal() && !event.mealText.isEmpty()
                        ? event.mealText : JSONObject.NULL)
                .put("carbs_g", event.hasCarbs()
                        ? event.carbsGrams : JSONObject.NULL)
                .put("portion_g", event.portionGrams == null
                        ? JSONObject.NULL : event.portionGrams)
                .put("original_portion_g", event.originalPortionGrams == null
                        ? JSONObject.NULL : event.originalPortionGrams)
                .put("original_carbs_g", event.originalCarbsGrams == null
                        ? JSONObject.NULL : event.originalCarbsGrams)
                .put("carbs_source", event.hasCarbs()
                        ? event.carbsSource : JSONObject.NULL)
                .put("insulin_units", event.hasInsulin()
                        ? event.insulinUnits : JSONObject.NULL)
                .put("insulin_type", event.hasInsulin()
                        ? event.insulinType : JSONObject.NULL)
                .put("insulin_name", event.hasInsulin()
                        ? event.insulinName : JSONObject.NULL)
                .put("analysis_id", event.analysisId.isEmpty()
                        ? JSONObject.NULL : event.analysisId)
                .put("ai_confidence", event.aiConfidence)
                .put("absorption_speed", event.absorptionSpeed == null
                        ? JSONObject.NULL : event.absorptionSpeed)
                .put("absorption_peak_minutes",
                        event.absorptionPeakMinutes == null
                                ? JSONObject.NULL
                                : event.absorptionPeakMinutes)
                .put("absorption_duration_minutes",
                        event.absorptionDurationMinutes == null
                                ? JSONObject.NULL
                                : event.absorptionDurationMinutes)
                .put("absorption_confidence",
                        event.absorptionConfidence == null
                                ? JSONObject.NULL
                                : event.absorptionConfidence)
                // These transport metadata fields are not presentation state.
                // Use a valid immutable snapshot so strict restore validation
                // can still reject malformed event identities and payloads.
                .put("created_at_ms", event.occurredAtMs)
                .put("updated_at_ms", event.occurredAtMs)
                .put("deleted_at_ms", JSONObject.NULL)
                .put("deleted", false)
                .put("sync_version", 1L);
        return json;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
    }
}
