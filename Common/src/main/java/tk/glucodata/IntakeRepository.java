package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-first intake repository.
 *
 * <p>Structured meal and insulin creates are committed to app-private phone
 * storage before the UI reports success. A durable idempotent outbox then sends
 * them to the configured backend and replaces the local graph item with the
 * server-confirmed item without creating a duplicate.</p>
 */
public final class IntakeRepository {
    public static final String DEFAULT_BACKEND_URL =
            "https://juggluco-general1.api-api-api.com";
    private static final String PREFS = "intake_backend";
    private static final String KEY_URL = "url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CACHE = "event_cache";
    private static final String KEY_PENDING = "pending_creates";
    private static final int MAX_CACHE_ITEMS = 500;
    private static final long SYNC_RETRY_MS = 30_000L;
    private static final String CONFIGURATION_CHANGED =
            "Backend configuration changed. Try again.";
    private static final String INTAKE_CHAT_SAVE_FAILED =
            "Could not save the confirmed intake-chat result.";

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);

        /**
         * A server/contract rejection whose retry outcome is no longer
         * ambiguous. Callers that do not need recovery UI keep the ordinary
         * error behavior.
         */
        default void onDefinitiveError(String message,
                boolean commitMayHaveOccurred) {
            onError(message);
        }
    }

    public interface Listener {
        void onIntakeEventsChanged(List<IntakeEvent> events);
    }

    interface Cancellable {
        void cancel();
    }

    private static volatile IntakeRepository instance;

    private final Context applicationContext;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService transcriptionExecutor =
            Executors.newSingleThreadExecutor();
    /** Serializes conversation turns without blocking durable outbox replay. */
    private final ExecutorService intakeChatExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> configurationListeners =
            new CopyOnWriteArrayList<>();
    private volatile List<IntakeEvent> events;
    private final ArrayList<PendingIntakeOperation> pendingCreates;
    private volatile Map<Integer, IntakeEvent> renderedEvents =
            Collections.emptyMap();
    private final Map<String, Integer> knownRenderKeys = new HashMap<>();
    private final Set<Integer> allocatedRenderKeys = new HashSet<>();
    private int nextRenderKey = 1;
    private volatile long configurationGeneration;
    private boolean syncScheduled;

    private IntakeRepository(Context context) {
        applicationContext = context.getApplicationContext();
        preferences = applicationContext.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        pendingCreates = readPendingCreates();
        events = Collections.unmodifiableList(readCache());
        mergeMissingPendingIntoCache();
        if (!pendingCreates.isEmpty()) schedulePendingSync(1_000L);
    }

    public static IntakeRepository get(Context context) {
        IntakeRepository current = instance;
        if (current == null) {
            synchronized (IntakeRepository.class) {
                current = instance;
                if (current == null) {
                    current = new IntakeRepository(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    public String backendUrl() {
        return preferences.getString(KEY_URL, DEFAULT_BACKEND_URL);
    }

    public String backendToken() {
        return preferences.getString(KEY_TOKEN, "");
    }

    public boolean configure(String url, String token) {
        String normalized = normalizeBackendUrl(url);
        String cleanToken = IntakeEvent.clean(token);
        boolean backendChanged = !normalized.equals(backendUrl())
                || !cleanToken.equals(backendToken());
        // A pending chat turn is bound to its original endpoint, credentials,
        // server session and client-turn id. Changing identity here would make
        // an exact retry impossible and could let the same medical fact be
        // recorded twice on a later fresh session.
        if (backendChanged
                && IntakeChatStateStore.hasPendingTurn(applicationContext)) {
            return false;
        }
        preferences.edit().putString(KEY_URL, normalized)
                .putString(KEY_TOKEN, cleanToken).apply();
        if (backendChanged) {
            configurationGeneration++;
            // Cached events belong to one backend identity. Never show health
            // data from the previous service/account under new credentials.
            replaceEvents(pendingLocalEvents());
            notifyConfigurationChanged();
            schedulePendingSync(250L);
        }
        return true;
    }

    public static String normalizeBackendUrl(String input) {
        String value = IntakeEvent.clean(input);
        if (value.isEmpty()) {
            return DEFAULT_BACKEND_URL;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null) {
                throw new IllegalArgumentException("Use a valid http(s) backend URL");
            }
            if ("http".equalsIgnoreCase(scheme)
                    && !isLocalDevelopmentHost(uri.getHost())) {
                throw new IllegalArgumentException(
                        "Use HTTPS for a non-local backend URL");
            }
            return value;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Use a valid http(s) backend URL");
        }
    }

    private static boolean isLocalDevelopmentHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "10.0.2.2".equals(host)
                || isPrivateIpv4(host);
    }

    /** Allow cleartext only for RFC1918 addresses that cannot be routed on the internet. */
    private static boolean isPrivateIpv4(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty()) return false;
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16
                        && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    public List<IntakeEvent> snapshot() {
        return events;
    }

    public IntakeEvent findByRenderKey(int key) {
        return key == 0 ? null : renderedEvents.get(key);
    }

    /**
     * Assigns collision-free native marker keys and atomically publishes the
     * exact key-to-event snapshot used by the graph. Existing backend IDs keep
     * their key for this process, so a refresh cannot make a still-visible old
     * marker briefly resolve to an unrelated medical event.
     */
    synchronized int[] assignRenderKeys(List<IntakeEvent> projection) {
        int[] keys=new int[projection.size()];
        Map<Integer, IntakeEvent> active=new HashMap<>();
        for(int index=0;index<projection.size();index++) {
            IntakeEvent event=projection.get(index);
            Integer key=event.id.isEmpty()?null:knownRenderKeys.get(event.id);
            // Duplicate IDs are invalid backend data, but still receive unique
            // keys so taps can never silently select the other list item.
            if(key==null||active.containsKey(key)) {
                key=allocateRenderKey();
                if(!event.id.isEmpty()&&!knownRenderKeys.containsKey(event.id)) {
                    knownRenderKeys.put(event.id,key);
                }
            }
            keys[index]=key;
            active.put(key,event);
        }
        renderedEvents=Collections.unmodifiableMap(active);
        return keys;
    }

    private int allocateRenderKey() {
        while(true) {
            int candidate=nextRenderKey;
            nextRenderKey=candidate==Integer.MAX_VALUE
                    ?Integer.MIN_VALUE:candidate+1;
            if(candidate!=0&&allocatedRenderKeys.add(candidate)) {
                return candidate;
            }
        }
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
            listener.onIntakeEventsChanged(events);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void addConfigurationListener(Runnable listener) {
        if (listener != null) {
            configurationListeners.addIfAbsent(listener);
        }
    }

    void removeConfigurationListener(Runnable listener) {
        configurationListeners.remove(listener);
    }

    private void notifyConfigurationChanged() {
        Runnable dispatch = () -> {
            for (Runnable listener : configurationListeners) {
                listener.run();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run();
        } else {
            main.post(dispatch);
        }
    }

    public void health(Callback<JSONObject> callback) {
        executeForCurrentBackend(new Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject value) {
                schedulePendingSync(0L);
                if (callback != null) callback.onSuccess(value);
            }

            @Override public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> {
            JSONObject health = api.health();
            if (health.optBoolean("auth_configured", false)) {
                // Health is intentionally public. Verify the configured Android
                // bearer token against one harmless protected read as well.
                api.list(0L, 0L);
            }
            return health;
        });
    }

    public void refresh(long fromMs, long toMs, Callback<List<IntakeEvent>> callback) {
        executeForCurrentBackend(new Callback<List<IntakeEvent>>() {
            @Override
            public void onSuccess(List<IntakeEvent> fresh) {
                // This callback runs on main only after the request generation
                // has been checked, so configure() cannot interleave an old
                // backend result with the new backend's cache.
                reconcileFreshEvents(fresh);
                if (callback != null) callback.onSuccess(events);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.list(fromMs, toMs));
    }

    void createInsulin(IntakeDraft draft, Callback<IntakeEvent> callback) {
        enqueueCreate(PendingIntakeOperation.insulin(draft), callback);
    }

    void createManualMeal(String clientEventId, long occurredAtMs,
            String mealText, float carbsGrams, Float portionGrams,
            Callback<IntakeEvent> callback) {
        enqueueCreate(PendingIntakeOperation.meal(clientEventId,
                occurredAtMs, mealText, carbsGrams, portionGrams), callback);
    }

    void startIntakeChat(String clientSessionId,
            Callback<IntakeChatSession> callback) {
        executeForCurrentBackend(intakeChatExecutor, callback,
                api -> api.createIntakeChatSession(clientSessionId));
    }

    /**
     * Sends one idempotent, backend-authoritative unified intake turn.
     *
     * <p>Cancellation disconnects the current transport and suppresses its UI
     * callback. If a complete authoritative receipt already arrived, it is
     * still durably merged. The caller can safely retry with the same
     * {@code clientTurnId}; the backend returns the already stored action result
     * if it committed before the connection was interrupted.</p>
     */
    Cancellable sendIntakeChat(String sessionId, String clientTurnId,
            long occurredAtMs, String text, File audio, List<File> photos,
            Callback<IntakeChatTurn> callback) {
        final long generation = configurationGeneration;
        final IntakeApiClient api = client();
        // The composer clears its attachment model after a completed turn.
        // Snapshot it before the work is queued so another UI action cannot
        // mutate the multipart payload while this serialized executor waits.
        final List<File> photoSnapshot = photos == null
                ? Collections.emptyList() : new ArrayList<>(photos);
        final IntakeApiClient.RequestCancellation cancellation =
                new IntakeApiClient.RequestCancellation();
        intakeChatExecutor.submit(() -> {
            boolean hadAmbiguousAttempt = false;
            try {
                IntakeChatTurn result;
                try {
                    result = api.sendIntakeChatTurn(sessionId, clientTurnId,
                            occurredAtMs, text, audio, photoSnapshot,
                            cancellation);
                } catch (IntakeApiClient.ApiException
                        | IntakeApiClient.ApiContractException definitive) {
                    throw definitive;
                } catch (Exception ambiguousTransportFailure) {
                    // A response can be lost after the backend committed. One
                    // exact retry with the same turn ID and immutable logical
                    // payload resolves that ambiguity through the backend's
                    // durable idempotency cache instead of creating a duplicate.
                    if (cancellation.isCancelled()) {
                        throw ambiguousTransportFailure;
                    }
                    hadAmbiguousAttempt = true;
                    result = api.sendIntakeChatTurn(sessionId, clientTurnId,
                            occurredAtMs, text, audio, photoSnapshot,
                            cancellation);
                }
                final IntakeChatTurn receipt = result;
                main.post(() -> deliverIntakeChatResult(generation,
                        cancellation, receipt, callback));
            } catch (Exception error) {
                boolean definitive = error instanceof IntakeApiClient.ApiException
                        || error instanceof IntakeApiClient.ApiContractException;
                boolean commitMayHaveOccurred =
                        hadAmbiguousAttempt
                        || error instanceof IntakeApiClient.ApiContractException;
                if (error instanceof IntakeApiClient.ApiException) {
                    int status = ((IntakeApiClient.ApiException) error)
                            .statusCode;
                    commitMayHaveOccurred = hadAmbiguousAttempt
                            || status == 409 || status >= 500;
                }
                String message = IntakeEvent.clean(error.getMessage());
                if (message.isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String finalMessage = message;
                final boolean finalDefinitive = definitive;
                final boolean finalCommitMayHaveOccurred =
                        commitMayHaveOccurred;
                if (callback != null) {
                    main.post(() -> {
                        if (cancellation.isCancelled()) return;
                        String delivered = generation
                                != configurationGeneration
                                ? CONFIGURATION_CHANGED : finalMessage;
                        if (generation == configurationGeneration
                                && finalDefinitive) {
                            callback.onDefinitiveError(delivered,
                                    finalCommitMayHaveOccurred);
                        } else {
                            callback.onError(delivered);
                        }
                    });
                }
            }
        });
        return cancellation::cancel;
    }

    void undoIntakeChatAction(String actionId,
            Callback<IntakeChatTurn> callback) {
        executeForCurrentBackend(intakeChatExecutor,
                new Callback<IntakeChatTurn>() {
            @Override public void onSuccess(IntakeChatTurn result) {
                try {
                    mergeIntakeChatTurn(result);
                    if (callback != null) callback.onSuccess(result);
                } catch (java.io.IOException error) {
                    if (callback != null) {
                        callback.onError(INTAKE_CHAT_SAVE_FAILED);
                    }
                }
            }

            @Override public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.undoIntakeChatAction(actionId));
    }

    private void deliverIntakeChatResult(long generation,
            IntakeApiClient.RequestCancellation cancellation,
            IntakeChatTurn result, Callback<IntakeChatTurn> callback) {
        if (generation != configurationGeneration) {
            if (callback != null && !cancellation.isCancelled()) {
                callback.onError(CONFIGURATION_CHANGED);
            }
            return;
        }
        try {
            // Merge before checking UI cancellation. A close/back action must
            // not hide a medical record that the backend already committed.
            mergeIntakeChatTurn(result);
        } catch (java.io.IOException error) {
            if (callback != null && !cancellation.isCancelled()) {
                callback.onError(INTAKE_CHAT_SAVE_FAILED);
            }
            return;
        }
        if (callback != null && !cancellation.isCancelled()) {
            callback.onSuccess(result);
        }
    }

    void startMealChat(String clientEventId, long occurredAtMs,
            Callback<MealChatSession> callback) {
        executeForCurrentBackend(callback, api -> api.createMealChatSession(
                clientEventId, occurredAtMs));
    }

    void updateMealChatTime(String sessionId, long occurredAtMs,
            Callback<MealChatSession> callback) {
        executeForCurrentBackend(callback, api -> api.updateMealChatTime(
                sessionId, occurredAtMs));
    }

    void getMealChatSession(String sessionId,
            Callback<MealChatSession> callback) {
        executeForCurrentBackend(callback,
                api -> api.getMealChatSession(sessionId));
    }

    /**
     * Resolves an ambiguous time write without guessing client-side state.
     * PUT is idempotent, so one retry is safe after a lost response. If both
     * responses are lost or rejected, the final GET returns the backend's
     * authoritative timestamp. All three requests use one captured backend
     * configuration and one generation check.
     */
    void resolveMealChatTime(String sessionId, long occurredAtMs,
            Callback<MealChatSession> callback) {
        executeForCurrentBackend(callback, api -> {
            try {
                return api.updateMealChatTime(sessionId, occurredAtMs);
            } catch (Exception firstFailure) {
                try {
                    return api.updateMealChatTime(sessionId, occurredAtMs);
                } catch (Exception retryFailure) {
                    return api.getMealChatSession(sessionId);
                }
            }
        });
    }

    void sendMealChat(String sessionId, String text, List<File> photos,
            Callback<MealChatSession.Turn> callback) {
        executeForCurrentBackend(callback, api -> api.sendMealChatMessage(
                sessionId, text, photos));
    }

    Cancellable transcribeAudio(File audio, Callback<String> callback) {
        final long generation = configurationGeneration;
        final IntakeApiClient api = client();
        final IntakeApiClient.RequestCancellation cancellation =
                new IntakeApiClient.RequestCancellation();
        transcriptionExecutor.submit(() -> {
            try {
                String result = api.transcribeAudio(audio, cancellation);
                if (callback != null) {
                    main.post(() -> {
                        if (cancellation.isCancelled()) return;
                        if (generation != configurationGeneration) {
                            callback.onError(CONFIGURATION_CHANGED);
                        } else {
                            callback.onSuccess(result);
                        }
                    });
                }
            } catch (Exception error) {
                String message = IntakeEvent.clean(error.getMessage());
                if (message.isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String finalMessage = message;
                if (callback != null) {
                    main.post(() -> {
                        if (cancellation.isCancelled()) return;
                        callback.onError(generation != configurationGeneration
                                ? CONFIGURATION_CHANGED : finalMessage);
                    });
                }
            } finally {
                // This worker is the sole lifetime owner while upload is in
                // flight. Keeping it alive after UI cancellation avoids a
                // delete/read race; cancellation checks stop the upload and
                // cleanup runs even when the callback is suppressed.
                if (audio != null) {
                    try {
                        if (audio.isFile() && !audio.delete()) {
                            audio.deleteOnExit();
                        }
                    } catch (SecurityException ignored) {}
                }
            }
        });
        return cancellation::cancel;
    }

    void confirmMealChat(String sessionId, Callback<IntakeEvent> callback) {
        executeForCurrentBackend(new Callback<IntakeEvent>() {
            @Override
            public void onSuccess(IntakeEvent confirmed) {
                // A meal appears on the graph only after the backend has
                // accepted the user's explicit confirmation and the backend
                // configuration is still the one that accepted it.
                mergeConfirmedEvent(confirmed);
                if (callback != null) callback.onSuccess(confirmed);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.confirmMealChatSession(sessionId));
    }

    void deleteEvent(IntakeEvent event, Callback<Boolean> callback) {
        if (event == null || event.id.isEmpty()) {
            if (callback != null) callback.onError("Intake event ID is missing");
            return;
        }
        final String eventId = event.id;
        executeForCurrentBackend(new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean deleted) {
                removeConfirmedEvent(eventId);
                if (callback != null) callback.onSuccess(Boolean.TRUE);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> {
            api.deleteIntake(eventId);
            return Boolean.TRUE;
        });
    }

    void updateMealPortion(IntakeEvent event, float portionGrams,
            Callback<IntakeEvent> callback) {
        if (event == null || event.id.isEmpty()
                || !event.hasEditablePortion()) {
            if (callback != null) callback.onError(
                    "Meal portion is not editable");
            return;
        }
        executeForCurrentBackend(new Callback<IntakeEvent>() {
            @Override
            public void onSuccess(IntakeEvent updated) {
                mergeConfirmedEvent(updated);
                if (callback != null) callback.onSuccess(updated);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.updateMealPortion(event.id, portionGrams));
    }

    private void mergeConfirmedEvent(IntakeEvent confirmed) {
        ArrayList<IntakeEvent> merged = new ArrayList<>(events);
        for (int index = merged.size() - 1; index >= 0; index--) {
            if (merged.get(index).id.equals(confirmed.id)) {
                merged.remove(index);
            }
        }
        merged.add(confirmed);
        replaceEvents(merged);
    }

    /** Applies one chat receipt to the phone cache as a single durable update. */
    private void mergeIntakeChatTurn(IntakeChatTurn turn)
            throws java.io.IOException {
        if (turn == null || (turn.events.isEmpty()
                && turn.deletedEventIds.isEmpty())) return;
        Set<String> deletedIds = new HashSet<>(turn.deletedEventIds);
        Set<String> replacementIds = new HashSet<>();
        Set<String> replacementClientIds = new HashSet<>();
        for (IntakeEvent event : turn.events) {
            replacementIds.add(event.id);
            if (!event.clientEventId.isEmpty()) {
                replacementClientIds.add(event.clientEventId);
            }
        }

        synchronized (this) {
            List<IntakeEvent> previousEvents = events;
            ArrayList<PendingIntakeOperation> previousPending =
                    new ArrayList<>(pendingCreates);
            ArrayList<IntakeEvent> merged = new ArrayList<>(events.size()
                    + turn.events.size());
            for (IntakeEvent existing : events) {
                boolean replacedByClientId = !existing.clientEventId.isEmpty()
                        && replacementClientIds.contains(existing.clientEventId);
                if (!deletedIds.contains(existing.id)
                        && !replacementIds.contains(existing.id)
                        && !replacedByClientId) {
                    merged.add(existing);
                }
            }
            merged.addAll(turn.events);
            // A returned authoritative record also acknowledges an equivalent
            // local create if a previous manual flow used the same client ID.
            pendingCreates.removeIf(operation ->
                    replacementClientIds.contains(operation.clientEventId));
            setSortedEvents(merged);
            if (!persistStateLocked()) {
                pendingCreates.clear();
                pendingCreates.addAll(previousPending);
                events = previousEvents;
                throw new java.io.IOException(INTAKE_CHAT_SAVE_FAILED);
            }
        }
        notifyEventListeners();
    }

    int pendingCreateCount() {
        synchronized (this) {
            return pendingCreates.size();
        }
    }

    private void enqueueCreate(PendingIntakeOperation operation,
            Callback<IntakeEvent> callback) {
        final IntakeEvent local;
        try {
            local = operation.localEvent();
        } catch (JSONException error) {
            if (callback != null) callback.onError("Could not create local record");
            return;
        }
        boolean stored;
        synchronized (this) {
            for (PendingIntakeOperation item : pendingCreates) {
                if (item.clientEventId.equals(operation.clientEventId)) {
                    if (callback != null) callback.onSuccess(itemLocalEvent(item));
                    schedulePendingSync(0L);
                    return;
                }
            }
            pendingCreates.add(operation);
            ArrayList<IntakeEvent> merged = new ArrayList<>(events);
            merged.add(local);
            setSortedEvents(merged);
            stored = persistStateLocked();
            if (!stored) {
                pendingCreates.remove(operation);
                merged.remove(local);
                setSortedEvents(merged);
            }
        }
        if (!stored) {
            if (callback != null) callback.onError(
                    "Could not save the record on this phone");
            return;
        }
        notifyEventListeners();
        if (callback != null) callback.onSuccess(local);
        schedulePendingSync(0L);
    }

    private IntakeEvent itemLocalEvent(PendingIntakeOperation item) {
        try {
            return item.localEvent();
        } catch (JSONException impossible) {
            return null;
        }
    }

    private void schedulePendingSync(long delayMs) {
        synchronized (this) {
            if (pendingCreates.isEmpty() || syncScheduled) return;
            syncScheduled = true;
        }
        main.postDelayed(() -> {
            synchronized (IntakeRepository.this) {
                syncScheduled = false;
                if (pendingCreates.isEmpty()) return;
            }
            executor.execute(this::synchronizePendingCreates);
        }, Math.max(0L, delayMs));
    }

    private void synchronizePendingCreates() {
        final long generation = configurationGeneration;
        final IntakeApiClient api = client();
        while (generation == configurationGeneration) {
            PendingIntakeOperation operation;
            synchronized (this) {
                if (pendingCreates.isEmpty()) return;
                operation = pendingCreates.get(0);
            }
            try {
                IntakeEvent confirmed = operation.upload(api);
                if (generation != configurationGeneration) return;
                acknowledgeCreate(operation, confirmed);
            } catch (Exception error) {
                // HTTP validation/auth errors and network failures both remain
                // in the durable outbox. A later health check or timed retry can
                // safely repeat the idempotent command.
                schedulePendingSync(SYNC_RETRY_MS);
                return;
            }
        }
    }

    private void acknowledgeCreate(PendingIntakeOperation operation,
            IntakeEvent confirmed) {
        synchronized (this) {
            List<IntakeEvent> previousEvents = events;
            boolean removed = pendingCreates.remove(operation);
            if (!removed) return;
            ArrayList<IntakeEvent> merged = new ArrayList<>(events.size());
            for (IntakeEvent event : events) {
                if (!event.clientEventId.equals(operation.clientEventId)
                        && !event.id.equals(confirmed.id)) {
                    merged.add(event);
                }
            }
            merged.add(confirmed);
            setSortedEvents(merged);
            // commit(), not apply(): never discard an outbox command until the
            // confirmed replacement and updated queue are durable together.
            if (!persistStateLocked()) {
                pendingCreates.add(0, operation);
                events = previousEvents;
                schedulePendingSync(SYNC_RETRY_MS);
                return;
            }
        }
        notifyEventListeners();
    }

    private void reconcileFreshEvents(List<IntakeEvent> fresh) {
        synchronized (this) {
            ArrayList<PendingIntakeOperation> previousPending =
                    new ArrayList<>(pendingCreates);
            List<IntakeEvent> previousEvents = events;
            Set<String> confirmedClientIds = new HashSet<>();
            for (IntakeEvent event : fresh) {
                if (!event.clientEventId.isEmpty()) {
                    confirmedClientIds.add(event.clientEventId);
                }
            }
            pendingCreates.removeIf(item ->
                    confirmedClientIds.contains(item.clientEventId));
            ArrayList<IntakeEvent> merged = new ArrayList<>(fresh);
            for (PendingIntakeOperation operation : pendingCreates) {
                IntakeEvent local = itemLocalEvent(operation);
                if (local != null) merged.add(local);
            }
            setSortedEvents(merged);
            if (!persistStateLocked()) {
                pendingCreates.clear();
                pendingCreates.addAll(previousPending);
                events = previousEvents;
            }
        }
        notifyEventListeners();
        schedulePendingSync(0L);
    }

    private void removeConfirmedEvent(String eventId) {
        ArrayList<IntakeEvent> remaining = new ArrayList<>(events.size());
        for (IntakeEvent item : events) {
            if (!item.id.equals(eventId)) remaining.add(item);
        }
        replaceEvents(remaining);
    }

    private IntakeApiClient client() {
        return new IntakeApiClient(backendUrl(), backendToken());
    }

    private interface BackendWork<T> {
        T run(IntakeApiClient api) throws Exception;
    }

    private <T> void executeForCurrentBackend(Callback<T> callback,
            BackendWork<T> work) {
        executeForCurrentBackend(executor, callback, work);
    }

    private <T> void executeForCurrentBackend(ExecutorService workExecutor,
            Callback<T> callback, BackendWork<T> work) {
        final long generation = configurationGeneration;
        final IntakeApiClient api = client();
        workExecutor.execute(() -> {
            try {
                T result = work.run(api);
                if (callback != null) {
                    main.post(() -> {
                        if (generation != configurationGeneration) {
                            callback.onError(CONFIGURATION_CHANGED);
                        } else {
                            callback.onSuccess(result);
                        }
                    });
                }
            } catch (Exception error) {
                String message = IntakeEvent.clean(error.getMessage());
                if (message.isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String finalMessage = message;
                if (callback != null) {
                    main.post(() -> callback.onError(
                            generation != configurationGeneration
                                    ? CONFIGURATION_CHANGED
                                    : finalMessage));
                }
            }
        });
    }

    private void replaceEvents(List<IntakeEvent> replacement) {
        ArrayList<IntakeEvent> sorted = new ArrayList<>(replacement);
        setSortedEvents(sorted);
        writeCache(events);
        notifyEventListeners();
    }

    private void setSortedEvents(List<IntakeEvent> replacement) {
        ArrayList<IntakeEvent> sorted = new ArrayList<>(replacement);
        sorted.sort(Comparator.comparingLong(event -> event.occurredAtMs));
        if (sorted.size() > MAX_CACHE_ITEMS) {
            ArrayList<IntakeEvent> pending = new ArrayList<>();
            ArrayList<IntakeEvent> confirmed = new ArrayList<>();
            for (IntakeEvent event : sorted) {
                (event.pendingSync ? pending : confirmed).add(event);
            }
            int confirmedLimit = Math.max(0, MAX_CACHE_ITEMS - pending.size());
            int confirmedStart = Math.max(0,
                    confirmed.size() - confirmedLimit);
            sorted = new ArrayList<>(confirmed.subList(
                    confirmedStart, confirmed.size()));
            sorted.addAll(pending);
            sorted.sort(Comparator.comparingLong(event -> event.occurredAtMs));
        }
        events = Collections.unmodifiableList(sorted);
    }

    private void notifyEventListeners() {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onIntakeEventsChanged(events);
            }
        });
    }

    private ArrayList<PendingIntakeOperation> readPendingCreates() {
        ArrayList<PendingIntakeOperation> result = new ArrayList<>();
        String raw = preferences.getString(KEY_PENDING, "");
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONArray json = new JSONArray(raw);
            for (int index = 0; index < json.length(); index++) {
                JSONObject item = json.optJSONObject(index);
                if (item != null) result.add(PendingIntakeOperation.fromJson(item));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_PENDING).apply();
        }
        return result;
    }

    private List<IntakeEvent> pendingLocalEvents() {
        ArrayList<IntakeEvent> result = new ArrayList<>();
        synchronized (this) {
            for (PendingIntakeOperation operation : pendingCreates) {
                IntakeEvent event = itemLocalEvent(operation);
                if (event != null) result.add(event);
            }
        }
        return result;
    }

    private synchronized void mergeMissingPendingIntoCache() {
        Set<String> cachedClientIds = new HashSet<>();
        ArrayList<IntakeEvent> merged = new ArrayList<>(events);
        for (IntakeEvent event : events) {
            cachedClientIds.add(event.clientEventId);
        }
        for (PendingIntakeOperation operation : pendingCreates) {
            if (!cachedClientIds.contains(operation.clientEventId)) {
                IntakeEvent local = itemLocalEvent(operation);
                if (local != null) merged.add(local);
            }
        }
        setSortedEvents(merged);
    }

    private boolean persistStateLocked() {
        JSONArray queue = new JSONArray();
        JSONArray cache = new JSONArray();
        try {
            for (PendingIntakeOperation operation : pendingCreates) {
                queue.put(operation.toJson());
            }
            for (IntakeEvent event : events) cache.put(event.toJson());
        } catch (JSONException error) {
            return false;
        }
        return preferences.edit()
                .putString(KEY_PENDING, queue.toString())
                .putString(KEY_CACHE, cache.toString())
                .commit();
    }

    private ArrayList<IntakeEvent> readCache() {
        ArrayList<IntakeEvent> cached = new ArrayList<>();
        String raw = preferences.getString(KEY_CACHE, "");
        if (raw == null || raw.isEmpty()) {
            return cached;
        }
        try {
            JSONArray json = new JSONArray(raw);
            for (int index = 0; index < json.length(); index++) {
                JSONObject item = json.optJSONObject(index);
                if (item != null) {
                    cached.add(IntakeEvent.fromJson(item));
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_CACHE).apply();
        }
        return cached;
    }

    private void writeCache(List<IntakeEvent> values) {
        JSONArray json = new JSONArray();
        try {
            for (IntakeEvent event : values) {
                json.put(event.toJson());
            }
            preferences.edit().putString(KEY_CACHE, json.toString()).apply();
        } catch (JSONException ignored) {
            // Every field is a finite primitive/string, so this is defensive only.
        }
    }
}
