package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Guards the backend-authoritative unified intake-chat transport contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeChatTransportContractTest {
    private static final String SESSION_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final String CLIENT_SESSION_ID =
            "00000000-0000-4000-8000-000000000002";
    private static final String TURN_ID =
            "00000000-0000-4000-8000-000000000003";
    private static final String ACTION_ID =
            "00000000-0000-4000-8000-000000000004";
    private static final String ACTIVE_ID =
            "00000000-0000-4000-8000-000000000005";
    private static final String ACTIVE_CLIENT_ID =
            "00000000-0000-4000-8000-000000000006";
    private static final String DELETED_ID =
            "00000000-0000-4000-8000-000000000007";

    @Test
    public void sessionIdentityUsesTheIdempotentClientUuid() throws Exception {
        IntakeChatSession session = IntakeChatSession.fromJson(
                new JSONObject()
                        .put("id", SESSION_ID)
                        .put("client_session_id", CLIENT_SESSION_ID)
                        .put("created_at_ms", 100L)
                        .put("updated_at_ms", 120L));

        assertEquals(SESSION_ID, session.id);
        assertEquals(CLIENT_SESSION_ID, session.clientSessionId);
        assertEquals(100L, session.createdAtMs);
        assertEquals(120L, session.updatedAtMs);
        assertEquals(session, IntakeApiClient.requireSessionIdentity(session,
                CLIENT_SESSION_ID));

        expectContractFailure(() -> IntakeApiClient.requireSessionIdentity(
                session, TURN_ID));
    }

    @Test
    public void sessionReceiptRejectsCoercedOrMalformedIdentityAndTime()
            throws Exception {
        JSONObject stringTime = new JSONObject()
                .put("id", SESSION_ID)
                .put("client_session_id", CLIENT_SESSION_ID)
                .put("created_at_ms", "100")
                .put("updated_at_ms", 120L);
        expectJsonFailure(() -> IntakeChatSession.fromJson(stringTime),
                "integer");

        JSONObject malformedId = new JSONObject()
                .put("id", "server-session")
                .put("client_session_id", CLIENT_SESSION_ID)
                .put("created_at_ms", 100L)
                .put("updated_at_ms", 120L);
        expectJsonFailure(() -> IntakeChatSession.fromJson(malformedId),
                "valid UUID");
    }

    @Test
    public void turnSeparatesActiveRecordsFromDeletedIds() throws Exception {
        JSONObject active = insulinEventJson(ACTIVE_ID, ACTIVE_CLIENT_ID,
                123L, 5.0, "long", "Tresiba");
        IntakeChatTurn turn = IntakeChatTurn.fromJson(new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Added 5 Tresiba")
                .put("transcript", "5 Tresiba")
                .put("outcome", "applied")
                .put("action_id", ACTION_ID)
                .put("events", new JSONArray().put(active))
                .put("deleted_event_ids",
                        new JSONArray().put(DELETED_ID)));

        assertEquals(SESSION_ID, turn.sessionId);
        assertEquals(TURN_ID, turn.clientTurnId);
        assertEquals("Added 5 Tresiba", turn.assistantMessage);
        assertEquals("5 Tresiba", turn.transcript);
        assertEquals(IntakeChatTurn.OUTCOME_APPLIED, turn.outcome);
        assertEquals(ACTION_ID, turn.actionId);
        assertEquals(1, turn.events.size());
        assertEquals(ACTIVE_ID, turn.events.get(0).id);
        assertEquals(5.0f, turn.events.get(0).insulinUnits, 0.0f);
        assertEquals(1, turn.deletedEventIds.size());
        assertEquals(DELETED_ID, turn.deletedEventIds.get(0));

        assertEquals(turn, IntakeApiClient.requireTurnIdentity(turn,
                SESSION_ID, TURN_ID));
        expectContractFailure(() -> IntakeApiClient.requireTurnIdentity(turn,
                CLIENT_SESSION_ID, TURN_ID));
        expectContractFailure(() -> IntakeApiClient.requireTurnIdentity(turn,
                SESSION_ID, CLIENT_SESSION_ID));

        try {
            turn.deletedEventIds.add("must-fail");
            fail("Turn collections must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void undoResponseNormalizesToTheSameReceiptModel() throws Exception {
        IntakeChatTurn undo = IntakeChatTurn.fromUndoJson(new JSONObject()
                .put("action_id", ACTION_ID)
                .put("outcome", "already_undone")
                .put("events", new JSONArray())
                .put("deleted_event_ids",
                        new JSONArray().put(DELETED_ID)));

        assertEquals("", undo.sessionId);
        assertEquals("", undo.clientTurnId);
        assertEquals(ACTION_ID, undo.actionId);
        assertEquals(IntakeChatTurn.OUTCOME_ALREADY_UNDONE, undo.outcome);
        assertTrue(undo.events.isEmpty());
        assertEquals(DELETED_ID, undo.deletedEventIds.get(0));
        assertEquals(undo, IntakeApiClient.requireUndoIdentity(undo,
                ACTION_ID));
        expectContractFailure(() -> IntakeApiClient.requireUndoIdentity(undo,
                TURN_ID));
    }

    @Test
    public void malformedReceiptCannotBothActivateAndDeleteOneEvent()
            throws Exception {
        JSONObject event = eventJson(ACTIVE_ID, ACTIVE_CLIENT_ID, 123L,
                "Apple");
        JSONObject response = new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Updated")
                .put("transcript", "")
                .put("outcome", "applied")
                .put("action_id", ACTION_ID)
                .put("events", new JSONArray().put(event))
                .put("deleted_event_ids", new JSONArray().put(ACTIVE_ID));

        try {
            IntakeChatTurn.fromJson(response);
            fail("Conflicting authoritative receipt must be rejected");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("tombstone"));
        }
    }

    @Test
    public void authoritativeReceiptRejectsValuesThatWouldBeNormalized()
            throws Exception {
        JSONObject invalidDose = insulinEventJson(ACTIVE_ID,
                ACTIVE_CLIENT_ID, 123L, 5.0, "long", "Tresiba")
                .put("insulin_units", -5.0);
        expectJsonFailure(() -> IntakeChatTurn.fromJson(appliedReceipt(
                invalidDose, new JSONArray())), "insulin_units");

        JSONObject stringDose = insulinEventJson(ACTIVE_ID,
                ACTIVE_CLIENT_ID, 123L, 5.0, "long", "Tresiba")
                .put("insulin_units", "5");
        expectJsonFailure(() -> IntakeChatTurn.fromJson(appliedReceipt(
                stringDose, new JSONArray())), "numeric");

        JSONObject mixedKind = eventJson(ACTIVE_ID, ACTIVE_CLIENT_ID, 123L,
                "Apple")
                .put("insulin_units", 5.0)
                .put("insulin_type", "long")
                .put("insulin_name", "Tresiba");
        expectJsonFailure(() -> IntakeChatTurn.fromJson(appliedReceipt(
                mixedKind, new JSONArray())), "exactly one intake kind");
    }

    @Test
    public void actionOutcomesRequireARealDeltaAndCoherentIdentity()
            throws Exception {
        JSONObject emptyApplied = appliedReceipt(null, new JSONArray());
        expectJsonFailure(() -> IntakeChatTurn.fromJson(emptyApplied),
                "no record delta");

        JSONObject clarificationWithAction = new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Please clarify")
                .put("transcript", "")
                .put("outcome", "clarification")
                .put("action_id", ACTION_ID)
                .put("events", new JSONArray())
                .put("deleted_event_ids", new JSONArray());
        expectJsonFailure(() -> IntakeChatTurn.fromJson(
                clarificationWithAction), "Non-action");

        JSONObject noChangeWithDelta = new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Nothing to undo")
                .put("transcript", "undo")
                .put("outcome", "no_change")
                .put("action_id", JSONObject.NULL)
                .put("events", new JSONArray().put(eventJson(ACTIVE_ID,
                        ACTIVE_CLIENT_ID, 123L, "Apple")))
                .put("deleted_event_ids", new JSONArray());
        expectJsonFailure(() -> IntakeChatTurn.fromJson(noChangeWithDelta),
                "Non-action result changed");
    }

    @Test
    public void completedReceiptMergesEvenWhenUiCancellationSuppressesCallback()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            String eventId = uuid(20);
            IntakeChatTurn receipt = IntakeChatTurn.fromJson(appliedReceipt(
                    eventJson(eventId, uuid(21), 500L, "Apple"),
                    new JSONArray()));
            IntakeApiClient.RequestCancellation cancellation =
                    new IntakeApiClient.RequestCancellation();
            cancellation.cancel();
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();

            invokeDelivery(repository, cancellation, receipt,
                    callback(successes, errors));

            assertEquals(Arrays.asList(eventId), ids(repository.snapshot()));
            assertEquals(0, successes.get());
            assertEquals(0, errors.get());
        } finally {
            resetRepository(context);
        }
    }

    @Test
    public void durableCommitFailureRollsBackAndNeverReportsSuccess()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            SharedPreferences real = context.getSharedPreferences(
                    "intake_backend", Context.MODE_PRIVATE);
            setRepositoryPreferences(repository,
                    failingCommitPreferences(real));
            IntakeChatTurn receipt = IntakeChatTurn.fromJson(appliedReceipt(
                    eventJson(uuid(22), uuid(23), 600L, "Pear"),
                    new JSONArray()));
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();

            invokeDelivery(repository,
                    new IntakeApiClient.RequestCancellation(), receipt,
                    callback(successes, errors));

            assertTrue(repository.snapshot().isEmpty());
            assertEquals(0, successes.get());
            assertEquals(1, errors.get());
        } finally {
            resetRepository(context);
        }
    }

    @Test
    public void freshInstallUsesProductionBackendWithoutACompiledToken()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            assertEquals("https://juggluco-general1.api-api-api.com",
                    repository.backendUrl());
            assertEquals("", repository.backendToken());
        } finally {
            resetRepository(context);
        }
    }

    @Test
    public void explicitlySavedBackendIdentityOverridesTheDefault()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        SharedPreferences preferences = context.getSharedPreferences(
                "intake_backend", Context.MODE_PRIVATE);
        preferences.edit()
                .putString("url", "http://127.0.0.1:8765")
                .putString("token", "local-development-token")
                .commit();
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            assertEquals("http://127.0.0.1:8765", repository.backendUrl());
            assertEquals("local-development-token", repository.backendToken());
        } finally {
            resetRepository(context);
        }
    }

    @Test
    public void backendIdentityChangeWaitsForPendingTurnResolution()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        IntakeChatStateStore.clear(context);
        File mediaDirectory = new File(context.getCacheDir(), "intake-media");
        assertTrue(mediaDirectory.isDirectory() || mediaDirectory.mkdirs());
        File audio = File.createTempFile("meal-voice-", ".m4a",
                mediaDirectory);
        byte[] audioBytes = new byte[]{7, 11, 13, 17};
        Files.write(audio.toPath(), audioBytes);
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            String originalUrl = repository.backendUrl();
            String originalToken = repository.backendToken();
            String fingerprint = java.util.UUID.nameUUIDFromBytes(
                    (originalUrl + '\u0000' + originalToken).getBytes(
                            StandardCharsets.UTF_8)).toString();
            IntakeChatStateStore.Pending pending =
                    new IntakeChatStateStore.Pending(TURN_ID, 123_456L,
                            "3 units", audio,
                            java.util.Collections.emptyList(), true, true);
            IntakeChatStateStore.State state =
                    new IntakeChatStateStore.State(CLIENT_SESSION_ID,
                            SESSION_ID, "", null,
                            java.util.Collections.emptyList(), pending);
            assertTrue(IntakeChatStateStore.save(context, fingerprint, state));
            SharedPreferences chatPreferences = context.getSharedPreferences(
                    "intake_chat_state", Context.MODE_PRIVATE);
            String storedBefore = chatPreferences.getString("state", "");
            AtomicInteger configurationChanges = new AtomicInteger();
            repository.addConfigurationListener(
                    configurationChanges::incrementAndGet);

            // Saving the identical identity remains harmless and usable.
            assertTrue(repository.configure(originalUrl, originalToken));
            assertEquals(0, configurationChanges.get());

            assertFalse(repository.configure("https://example.com",
                    "different-token"));
            assertEquals(originalUrl, repository.backendUrl());
            assertEquals(originalToken, repository.backendToken());
            assertEquals(0, configurationChanges.get());
            assertEquals(storedBefore,
                    chatPreferences.getString("state", ""));
            assertArrayEquals(audioBytes, Files.readAllBytes(audio.toPath()));
            IntakeChatStateStore.State restored = IntakeChatStateStore.load(
                    context, fingerprint);
            assertNotNull(restored);
            assertNotNull(restored.pending);
            assertEquals(TURN_ID, restored.pending.clientTurnId);
            assertEquals(audio.getCanonicalFile(),
                    restored.pending.audio.getCanonicalFile());

            // Only explicit reconciliation unlocks a different identity.
            IntakeChatStateStore.clear(context);
            assertTrue(repository.configure("https://example.com",
                    "different-token"));
            assertEquals("https://example.com", repository.backendUrl());
            assertEquals("different-token", repository.backendToken());
            assertEquals(1, configurationChanges.get());
        } finally {
            IntakeChatStateStore.clear(context);
            audio.delete();
            resetRepository(context);
        }
    }

    @Test
    public void invalidPendingTimestampDoesNotLockBackendSettings()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        IntakeChatStateStore.clear(context);
        try {
            IntakeRepository repository = IntakeRepository.get(context);
            String fingerprint = java.util.UUID.nameUUIDFromBytes(
                    (repository.backendUrl() + '\u0000'
                            + repository.backendToken()).getBytes(
                            StandardCharsets.UTF_8)).toString();
            IntakeChatStateStore.Pending pending =
                    new IntakeChatStateStore.Pending(TURN_ID, 123_456L,
                            "3 units", null,
                            java.util.Collections.emptyList());
            IntakeChatStateStore.State state =
                    new IntakeChatStateStore.State(CLIENT_SESSION_ID,
                            SESSION_ID, "", null,
                            java.util.Collections.emptyList(), pending);
            assertTrue(IntakeChatStateStore.save(context, fingerprint, state));
            SharedPreferences chatPreferences = context.getSharedPreferences(
                    "intake_chat_state", Context.MODE_PRIVATE);
            JSONObject stale = new JSONObject(chatPreferences.getString(
                    "state", ""));
            stale.put("saved_at_ms", System.currentTimeMillis()
                    - 6L * 60L * 60L * 1000L - 1L);
            chatPreferences.edit().putString("state", stale.toString())
                    .commit();
            assertFalse(IntakeChatStateStore.hasPendingTurn(context));

            JSONObject future = new JSONObject(stale.toString());
            future.put("saved_at_ms", System.currentTimeMillis() + 60_000L);
            chatPreferences.edit().putString("state", future.toString())
                    .commit();
            assertFalse(IntakeChatStateStore.hasPendingTurn(context));

            assertTrue(repository.configure("https://example.com",
                    "new-token"));
            assertEquals("https://example.com", repository.backendUrl());
            assertEquals("new-token", repository.backendToken());
        } finally {
            IntakeChatStateStore.clear(context);
            resetRepository(context);
        }
    }

    @Test
    public void authoritativeTurnAndUndoReplaceTheDurableCacheByIdentity()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetRepository(context);
        IntakeRepository repository = IntakeRepository.get(context);

        String oldServerId = uuid(10);
        String sameClientId = uuid(11);
        String deleteServerId = uuid(12);
        String deleteClientId = uuid(13);
        String keepServerId = uuid(14);
        String keepClientId = uuid(15);
        String newServerId = uuid(16);
        IntakeEvent replaced = event(oldServerId, sameClientId, 100L,
                "Old meal");
        IntakeEvent deleted = event(deleteServerId, deleteClientId, 200L,
                "Delete me");
        IntakeEvent untouched = event(keepServerId, keepClientId, 300L,
                "Keep me");
        invokeRepository(repository, "replaceEvents", List.class,
                Arrays.asList(replaced, deleted, untouched));

        JSONObject replacement = eventJson(newServerId, sameClientId,
                400L, "Corrected meal");
        IntakeChatTurn correction = IntakeChatTurn.fromJson(new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Corrected")
                .put("transcript", "No, correct the meal")
                .put("outcome", "applied")
                .put("action_id", ACTION_ID)
                .put("events", new JSONArray().put(replacement))
                .put("deleted_event_ids",
                        new JSONArray().put(deleteServerId)));
        invokeRepository(repository, "mergeIntakeChatTurn",
                IntakeChatTurn.class, correction);

        assertEquals(Arrays.asList(keepServerId, newServerId),
                ids(repository.snapshot()));
        String persisted = context.getSharedPreferences("intake_backend",
                Context.MODE_PRIVATE).getString("event_cache", "");
        assertTrue(persisted.contains(newServerId));
        assertFalse(persisted.contains(oldServerId));
        assertFalse(persisted.contains(deleteServerId));

        IntakeChatTurn undo = IntakeChatTurn.fromUndoJson(new JSONObject()
                .put("action_id", ACTION_ID)
                .put("outcome", "undone")
                .put("events", new JSONArray().put(
                        eventJson(deleteServerId, deleteClientId, 200L,
                                "Delete me")))
                .put("deleted_event_ids",
                        new JSONArray().put(newServerId)));
        invokeRepository(repository, "mergeIntakeChatTurn",
                IntakeChatTurn.class, undo);

        assertEquals(Arrays.asList(deleteServerId, keepServerId),
                ids(repository.snapshot()));
        resetRepository(context);
    }

    @Test
    public void apiUsesTheExactMultipartAndUndoEndpoints() throws Exception {
        String client = source("IntakeApiClient.java");
        String create = between(client,
                "IntakeChatSession createIntakeChatSession",
                "IntakeChatTurn sendIntakeChatTurn");
        String send = between(client,
                "IntakeChatTurn sendIntakeChatTurn",
                "IntakeChatTurn undoIntakeChatAction");
        String undo = between(client,
                "IntakeChatTurn undoIntakeChatAction",
                "IntakeEvent createInsulin");

        assertTrue(create.contains("/v1/intake-chat/sessions"));
        assertTrue(create.contains("client_session_id"));
        assertTrue(create.contains("requireSessionIdentity"));
        assertTrue(send.contains("+ \"/turns\""));
        assertTrue(send.contains("\"client_turn_id\", cleanTurnId"));
        assertTrue(send.contains("\"occurred_at_ms\""));
        assertTrue(send.contains("\"text\", cleanText"));
        assertTrue(send.contains("\"photos\", photo"));
        assertTrue(send.contains("\"audio\", audio"));
        assertTrue(send.contains("RequestCancellation cancellation"));
        assertTrue(send.contains("cancellation.attach(connection)"));
        assertTrue(send.contains("cancellation.throwIfCancelled()"));
        assertTrue(send.contains("\"Idempotency-Key\", cleanTurnId"));
        assertTrue(send.contains("requireTurnIdentity"));
        String completedResponse = between(send,
                "JSONObject response = readJson(connection)",
                "IntakeChatTurn result = IntakeChatTurn.fromJson(response)");
        assertFalse(completedResponse.contains("throwIfCancelled"));
        assertTrue(undo.contains("/v1/intake-chat/actions/"));
        assertTrue(undo.contains("+ \"/undo\""));
        assertTrue(undo.contains("IntakeChatTurn.fromUndoJson"));
        assertTrue(undo.contains("requireUndoIdentity"));
    }

    @Test
    public void repositoryPublishesOnlyAuthoritativeTurnResults()
            throws Exception {
        String repository = source("IntakeRepository.java");
        String send = between(repository, "Cancellable sendIntakeChat(",
                "void undoIntakeChatAction");
        String start = between(repository, "void startIntakeChat(",
                "Cancellable sendIntakeChat(");
        String undo = between(repository, "void undoIntakeChatAction",
                "void startMealChat");
        String delivery = between(repository,
                "private void deliverIntakeChatResult",
                "void startMealChat");
        String merge = between(repository, "private void mergeIntakeChatTurn",
                "int pendingCreateCount");

        assertTrue(repository.contains("void startIntakeChat("));
        assertTrue(start.contains(
                "executeForCurrentBackend(intakeChatExecutor"));
        assertTrue(repository.contains("intakeChatExecutor.submit"));
        assertTrue(undo.contains(
                "executeForCurrentBackend(intakeChatExecutor"));
        assertTrue(send.contains("new ArrayList<>(photos)"));
        assertTrue(send.contains("api.sendIntakeChatTurn"));
        assertEquals(2, occurrences(send, "api.sendIntakeChatTurn("));
        assertTrue(send.contains("ApiContractException definitive"));
        assertTrue(delivery.indexOf("mergeIntakeChatTurn(result)")
                < delivery.indexOf("callback.onSuccess(result)"));
        assertTrue(delivery.indexOf("mergeIntakeChatTurn(result)")
                < delivery.lastIndexOf("cancellation.isCancelled()"));
        assertTrue(repository.contains("api -> api.undoIntakeChatAction(actionId)"));
        assertTrue(merge.contains("turn.deletedEventIds"));
        assertTrue(merge.contains("deletedIds.contains(existing.id)"));
        assertTrue(merge.contains("merged.addAll(turn.events)"));
        assertTrue(merge.contains("persistStateLocked()"));
        assertTrue(merge.contains("throw new java.io.IOException"));
        assertTrue(repository.contains(
                "executeForCurrentBackend(ExecutorService workExecutor"));

        // The established offline/manual path remains independent.
        assertTrue(repository.contains("enqueueCreate(PendingIntakeOperation.insulin"));
        assertTrue(repository.contains("enqueueCreate(PendingIntakeOperation.meal"));
        assertFalse(send.contains("enqueueCreate("));
    }

    private static IntakeEvent event(String id, String clientId, long time,
            String meal) throws Exception {
        return IntakeEvent.fromJson(eventJson(id, clientId, time, meal));
    }

    private static JSONObject eventJson(String id, String clientId, long time,
            String meal) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("client_event_id", clientId)
                .put("occurred_at_ms", time)
                .put("meal_text", meal)
                .put("carbs_g", 15.0)
                .put("portion_g", JSONObject.NULL)
                .put("original_portion_g", JSONObject.NULL)
                .put("original_carbs_g", JSONObject.NULL)
                .put("carbs_source", "manual")
                .put("insulin_units", JSONObject.NULL)
                .put("insulin_type", JSONObject.NULL)
                .put("insulin_name", JSONObject.NULL)
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

    private static JSONObject insulinEventJson(String id, String clientId,
            long time, double units, String type, String name)
            throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("client_event_id", clientId)
                .put("occurred_at_ms", time)
                .put("meal_text", JSONObject.NULL)
                .put("carbs_g", JSONObject.NULL)
                .put("portion_g", JSONObject.NULL)
                .put("original_portion_g", JSONObject.NULL)
                .put("original_carbs_g", JSONObject.NULL)
                .put("carbs_source", JSONObject.NULL)
                .put("insulin_units", units)
                .put("insulin_type", type)
                .put("insulin_name", name)
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

    private static JSONObject appliedReceipt(JSONObject event,
            JSONArray deletedIds) throws JSONException {
        JSONArray events = new JSONArray();
        if (event != null) events.put(event);
        return new JSONObject()
                .put("session_id", SESSION_ID)
                .put("client_turn_id", TURN_ID)
                .put("assistant_message", "Saved")
                .put("transcript", "reported intake")
                .put("outcome", "applied")
                .put("action_id", ACTION_ID)
                .put("events", events)
                .put("deleted_event_ids", deletedIds);
    }

    private static IntakeRepository.Callback<IntakeChatTurn> callback(
            AtomicInteger successes, AtomicInteger errors) {
        return new IntakeRepository.Callback<IntakeChatTurn>() {
            @Override public void onSuccess(IntakeChatTurn value) {
                successes.incrementAndGet();
            }

            @Override public void onError(String message) {
                errors.incrementAndGet();
            }
        };
    }

    private static void invokeDelivery(IntakeRepository repository,
            IntakeApiClient.RequestCancellation cancellation,
            IntakeChatTurn receipt,
            IntakeRepository.Callback<IntakeChatTurn> callback)
            throws Exception {
        Field generation = IntakeRepository.class.getDeclaredField(
                "configurationGeneration");
        generation.setAccessible(true);
        Method method = IntakeRepository.class.getDeclaredMethod(
                "deliverIntakeChatResult", long.class,
                IntakeApiClient.RequestCancellation.class,
                IntakeChatTurn.class, IntakeRepository.Callback.class);
        method.setAccessible(true);
        method.invoke(repository, generation.getLong(repository), cancellation,
                receipt, callback);
    }

    private static void setRepositoryPreferences(IntakeRepository repository,
            SharedPreferences preferences) throws Exception {
        Field field = IntakeRepository.class.getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(repository, preferences);
    }

    private static SharedPreferences failingCommitPreferences(
            SharedPreferences delegate) {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] { SharedPreferences.class },
                (proxy, method, args) -> {
                    if ("edit".equals(method.getName())) {
                        return failingEditor(delegate.edit());
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static SharedPreferences.Editor failingEditor(
            SharedPreferences.Editor delegate) {
        Object[] holder = new Object[1];
        InvocationHandler handler = (proxy, method, args) -> {
            if ("commit".equals(method.getName())) return false;
            if ("apply".equals(method.getName())) return null;
            Object result = method.invoke(delegate, args);
            return SharedPreferences.Editor.class.isAssignableFrom(
                    method.getReturnType()) ? holder[0] : result;
        };
        holder[0] = Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[] { SharedPreferences.Editor.class }, handler);
        return (SharedPreferences.Editor) holder[0];
    }

    private static void expectContractFailure(ThrowingAction action)
            throws Exception {
        try {
            action.run();
            fail("Mismatched response identity must be rejected");
        } catch (IntakeApiClient.ApiContractException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }
    }

    private static void expectJsonFailure(ThrowingAction action,
            String expectedMessage) throws Exception {
        try {
            action.run();
            fail("Malformed authoritative receipt must be rejected");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage()
                    .contains(expectedMessage));
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static String uuid(int value) {
        return String.format(Locale.ROOT,
                "00000000-0000-4000-8000-%012d", value);
    }

    private static List<String> ids(List<IntakeEvent> events) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (IntakeEvent event : events) result.add(event.id);
        return result;
    }

    private static void invokeRepository(IntakeRepository repository,
            String methodName, Class<?> parameterType, Object value)
            throws Exception {
        Method method = IntakeRepository.class.getDeclaredMethod(methodName,
                parameterType);
        method.setAccessible(true);
        method.invoke(repository, value);
    }

    private static void resetRepository(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(
                "intake_backend", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        Field instance = IntakeRepository.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertTrue("Missing source token: " + start, first >= 0);
        assertTrue("Missing source boundary: " + end, last > first);
        return value.substring(first, last);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.isRegularFile(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
