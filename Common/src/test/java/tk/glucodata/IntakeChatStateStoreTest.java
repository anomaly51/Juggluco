package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Behavioral tests for process-safe same-ID intake reconciliation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeChatStateStoreTest {
    private Context context;
    private File mediaDirectory;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        IntakeChatStateStore.clear(context);
        mediaDirectory = new File(context.getCacheDir(), "intake-media");
        assertTrue(mediaDirectory.isDirectory() || mediaDirectory.mkdirs());
    }

    @After
    public void tearDown() {
        IntakeChatStateStore.clear(context);
        File[] files = mediaDirectory.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
    }

    @Test
    public void pendingIdentityAndByteSourcesSurviveRecreation()
            throws Exception {
        File audio = media("meal-voice-", ".m4a", new byte[]{1, 2, 3});
        File photo = media("meal-photo-", ".jpg", new byte[]{4, 5, 6});
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                "turn-id", 123_456L, "I ate soup", audio,
                Collections.singletonList(photo));
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                "client-session", "server-session", "", null,
                Arrays.asList(
                        new IntakeChatStateStore.Line(false, "Hello", 0),
                        new IntakeChatStateStore.Line(true, "I ate soup", 1)),
                pending);

        assertTrue(IntakeChatStateStore.save(context, "backend-a", state));
        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend-a");

        assertNotNull(restored);
        assertEquals("client-session", restored.clientSessionId);
        assertEquals("server-session", restored.sessionId);
        assertEquals(2, restored.lines.size());
        assertNotNull(restored.pending);
        assertEquals("turn-id", restored.pending.clientTurnId);
        assertEquals(123_456L, restored.pending.occurredAtMs);
        assertEquals(audio.getCanonicalFile(),
                restored.pending.audio.getCanonicalFile());
        assertEquals(Collections.singletonList(photo.getCanonicalFile()),
                restored.pending.photos);
    }

    @Test
    public void stateNeverCrossesBackendIdentity() {
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                "client-session", "server-session", "", null,
                Collections.emptyList(), null);
        assertTrue(IntakeChatStateStore.save(context, "backend-a", state));

        assertNull(IntakeChatStateStore.load(context, "backend-b"));
        assertFalse(context.getSharedPreferences("intake_chat_state",
                Context.MODE_PRIVATE).contains("state"));
    }

    @Test
    public void definitiveFailureRecoverySurvivesRecreation()
            throws Exception {
        File audio = media("meal-voice-", ".m4a", new byte[]{1, 2, 3});
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                "turn-id", 123_456L, "", audio,
                Collections.emptyList(), true, true);
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                "client-session", "missing-server-session", "", null,
                Collections.emptyList(), pending);

        assertTrue(IntakeChatStateStore.save(context, "backend", state));
        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");

        assertNotNull(restored);
        assertNotNull(restored.pending);
        assertTrue(restored.pending.definitiveFailure);
        assertTrue(restored.pending.commitMayHaveOccurred);
    }

    @Test
    public void restoredMediaMustRemainInsidePrivateIntakeCache()
            throws Exception {
        File outside = File.createTempFile("outside-intake-", ".m4a",
                context.getCacheDir());
        Files.write(outside.toPath(), new byte[]{7, 8, 9});
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                "turn-id", 123L, "", outside, Collections.emptyList());
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                "client-session", "", "", null,
                Collections.emptyList(), pending);
        assertTrue(IntakeChatStateStore.save(context, "backend", state));

        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");
        assertNotNull(restored);
        assertNull(restored.pending);
        outside.delete();
    }

    @Test
    public void legacyLocalRowsAreMigratedWithoutTouchingMedicalState()
            throws Exception {
        String sessionId = uuid(1);
        String actionId = uuid(3);
        IntakeChatTurn action = appliedInsulinTurn(sessionId, uuid(2),
                actionId, uuid(4), uuid(5));
        File audio = media("meal-voice-", ".m4a", new byte[]{1, 2, 3});
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                "pending-turn", 123_456L, "5 NovoRapid", audio,
                Collections.emptyList(), true, true);
        IntakeChatStateStore.State initial = new IntakeChatStateStore.State(
                uuid(6), sessionId, actionId, action,
                Collections.emptyList(), pending);
        assertTrue(IntakeChatStateStore.save(context, "backend", initial));

        String stored = context.getSharedPreferences("intake_chat_state",
                Context.MODE_PRIVATE).getString("state", "");
        JSONObject legacy = new JSONObject(stored);
        legacy.remove("schema_version");
        legacy.put("lines", new JSONArray()
                .put(line(false, "Tell me what happened — for example, say what you ate."))
                .put(line(false, "AI log: Failed to connect to /127.0.0.1:8765"))
                .put(line(false, "Couldn’t process: audio has invalid stream metadata"))
                .put(line(false, "Backend changed. A new private conversation has started."))
                .put(line(true, "AI log: I injected 5 units", 1))
                .put(line(false, "Уточните точное количество единиц.")));
        context.getSharedPreferences("intake_chat_state", Context.MODE_PRIVATE)
                .edit().putString("state", legacy.toString()).commit();

        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");

        assertNotNull(restored);
        assertEquals(actionId, restored.lastActionId);
        assertNotNull(restored.lastActionTurn);
        assertEquals(actionId, restored.lastActionTurn.actionId);
        assertNotNull(restored.pending);
        assertEquals("pending-turn", restored.pending.clientTurnId);
        assertEquals(audio.getCanonicalFile(),
                restored.pending.audio.getCanonicalFile());
        assertEquals(2, restored.lines.size());
        assertTrue(restored.lines.get(0).user);
        assertEquals("AI log: I injected 5 units",
                restored.lines.get(0).text);
        assertFalse(restored.lines.get(1).user);
        assertEquals("Уточните точное количество единиц.",
                restored.lines.get(1).text);

        assertTrue(IntakeChatStateStore.save(context, "backend", restored));
        JSONObject migrated = new JSONObject(context.getSharedPreferences(
                "intake_chat_state", Context.MODE_PRIVATE)
                .getString("state", ""));
        assertEquals(3, migrated.getInt("schema_version"));
        assertEquals(2, migrated.getJSONArray("lines").length());
        assertEquals(actionId, migrated.getString("last_action_id"));
        assertEquals("pending-turn", migrated.getJSONObject("pending")
                .getString("client_turn_id"));
    }

    @Test
    public void deletedActionCardSurvivesReopen()
            throws Exception {
        String sessionId = uuid(11);
        IntakeChatTurn applied = appliedInsulinTurn(sessionId, uuid(12),
                uuid(13), uuid(14), uuid(15));
        IntakeChatStateStore.State tombstone = new IntakeChatStateStore.State(
                uuid(16), sessionId, "", applied, true,
                Collections.emptyList(), null);

        assertTrue(IntakeChatStateStore.save(context, "backend", tombstone));
        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");

        assertNotNull(restored);
        assertEquals("", restored.lastActionId);
        assertTrue(restored.lastActionDeleted);
        assertNotNull(restored.lastActionTurn);
        assertEquals(applied.actionId, restored.lastActionTurn.actionId);
        assertNull(restored.pending);
    }

    @Test
    public void pendingCorrectionControlSurvivesReopen() throws Exception {
        String sessionId = uuid(21);
        String actionId = uuid(23);
        IntakeChatTurn applied = appliedInsulinTurn(sessionId, uuid(22),
                actionId, uuid(24), uuid(25));
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                "control-turn", 456_789L, "Correct the last entry", null,
                Collections.emptyList(), false, false, 1);
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                uuid(26), sessionId, actionId, applied, false,
                Collections.emptyList(), pending);

        assertTrue(IntakeChatStateStore.save(context, "backend", state));
        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");

        assertNotNull(restored);
        assertNotNull(restored.pending);
        assertEquals(1, restored.pending.controlKind);
        assertEquals("control-turn", restored.pending.clientTurnId);
    }

    @Test
    public void inverseUndoWithRestoredEventsIsNotMigratedToDeleted()
            throws Exception {
        String actionId = uuid(31);
        IntakeChatTurn restoredTurn = IntakeChatTurn.fromUndoJson(
                new JSONObject()
                        .put("action_id", actionId)
                        .put("outcome", "undone")
                        .put("events", new JSONArray().put(insulinEvent(
                                uuid(32), uuid(33))))
                        .put("deleted_event_ids", new JSONArray()));
        IntakeChatStateStore.State state = new IntakeChatStateStore.State(
                uuid(34), uuid(35), "", restoredTurn, false,
                Collections.emptyList(), null);

        assertTrue(IntakeChatStateStore.save(context, "backend", state));
        IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                context, "backend");

        assertNotNull(restored);
        assertFalse(restored.lastActionDeleted);
        assertNotNull(restored.lastActionTurn);
        assertEquals(1, restored.lastActionTurn.events.size());
        assertEquals("NovoRapid",
                restored.lastActionTurn.events.get(0).insulinName);
    }

    private static JSONObject line(boolean user, String text) throws Exception {
        return line(user, text, 0);
    }

    private static JSONObject line(boolean user, String text, int photoCount)
            throws Exception {
        return new JSONObject().put("user", user).put("text", text)
                .put("photo_count", photoCount);
    }

    private static IntakeChatTurn appliedInsulinTurn(String sessionId,
            String clientTurnId, String actionId, String eventId,
            String clientEventId) throws Exception {
        return IntakeChatTurn.fromJson(new JSONObject()
                .put("session_id", sessionId)
                .put("client_turn_id", clientTurnId)
                .put("assistant_message", "Recorded")
                .put("transcript", "5 NovoRapid")
                .put("outcome", "applied")
                .put("action_id", actionId)
                .put("events", new JSONArray().put(insulinEvent(
                        eventId, clientEventId)))
                .put("deleted_event_ids", new JSONArray()));
    }

    private static JSONObject insulinEvent(String eventId,
            String clientEventId) throws Exception {
        long time = 123_456L;
        return new JSONObject()
                .put("id", eventId)
                .put("client_event_id", clientEventId)
                .put("occurred_at_ms", time)
                .put("meal_text", JSONObject.NULL)
                .put("carbs_g", JSONObject.NULL)
                .put("portion_g", JSONObject.NULL)
                .put("original_portion_g", JSONObject.NULL)
                .put("original_carbs_g", JSONObject.NULL)
                .put("carbs_source", JSONObject.NULL)
                .put("insulin_units", 5.0)
                .put("insulin_type", "rapid")
                .put("insulin_name", "NovoRapid")
                .put("analysis_id", JSONObject.NULL)
                .put("ai_confidence", 0.0)
                .put("absorption_speed", JSONObject.NULL)
                .put("absorption_peak_minutes", JSONObject.NULL)
                .put("absorption_duration_minutes", JSONObject.NULL)
                .put("absorption_confidence", JSONObject.NULL)
                .put("created_at_ms", time)
                .put("updated_at_ms", time)
                .put("deleted_at_ms", JSONObject.NULL)
                .put("deleted", false)
                .put("sync_version", 1L);
    }

    private static String uuid(int value) {
        return String.format(java.util.Locale.ROOT,
                "00000000-0000-4000-8000-%012d", value);
    }

    private File media(String prefix, String suffix, byte[] content)
            throws Exception {
        File file = File.createTempFile(prefix, suffix, mediaDirectory);
        Files.write(file.toPath(), content);
        return file;
    }
}
