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
 * Backend-first intake repository.
 *
 * <p>Writes are never queued into the legacy native record database. A create is
 * considered successful only after the backend confirms it. The small local JSON
 * cache is read-only fallback presentation data for the graph.</p>
 */
public final class IntakeRepository {
    public static final String DEFAULT_BACKEND_URL = "http://127.0.0.1:8765";
    private static final String PREFS = "intake_backend";
    private static final String KEY_URL = "url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CACHE = "event_cache";
    private static final int MAX_CACHE_ITEMS = 500;
    private static final String CONFIGURATION_CHANGED =
            "Backend configuration changed. Try again.";

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    public interface Listener {
        void onIntakeEventsChanged(List<IntakeEvent> events);
    }

    interface Cancellable {
        void cancel();
    }

    private static volatile IntakeRepository instance;

    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService transcriptionExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> configurationListeners =
            new CopyOnWriteArrayList<>();
    private volatile List<IntakeEvent> events;
    private volatile Map<Integer, IntakeEvent> renderedEvents =
            Collections.emptyMap();
    private final Map<String, Integer> knownRenderKeys = new HashMap<>();
    private final Set<Integer> allocatedRenderKeys = new HashSet<>();
    private int nextRenderKey = 1;
    private volatile long configurationGeneration;

    private IntakeRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        events = Collections.unmodifiableList(readCache());
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

    public void configure(String url, String token) {
        String normalized = normalizeBackendUrl(url);
        String cleanToken = IntakeEvent.clean(token);
        boolean backendChanged = !normalized.equals(backendUrl())
                || !cleanToken.equals(backendToken());
        preferences.edit().putString(KEY_URL, normalized)
                .putString(KEY_TOKEN, cleanToken).apply();
        if (backendChanged) {
            configurationGeneration++;
            // Cached events belong to one backend identity. Never show health
            // data from the previous service/account under new credentials.
            replaceEvents(Collections.emptyList());
            notifyConfigurationChanged();
        }
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
                || "10.0.2.2".equals(host);
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
        executeForCurrentBackend(callback, api -> {
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
                replaceEvents(fresh);
                if (callback != null) callback.onSuccess(events);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.list(fromMs, toMs));
    }

    void createInsulin(IntakeDraft draft, Callback<IntakeEvent> callback) {
        executeForCurrentBackend(new Callback<IntakeEvent>() {
            @Override
            public void onSuccess(IntakeEvent created) {
                mergeConfirmedEvent(created);
                if (callback != null) callback.onSuccess(created);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        }, api -> api.createInsulin(draft));
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
        final long generation = configurationGeneration;
        final IntakeApiClient api = client();
        executor.execute(() -> {
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
        sorted.sort(Comparator.comparingLong(event -> event.occurredAtMs));
        if (sorted.size() > MAX_CACHE_ITEMS) {
            sorted = new ArrayList<>(sorted.subList(
                    sorted.size() - MAX_CACHE_ITEMS, sorted.size()));
        }
        events = Collections.unmodifiableList(sorted);
        writeCache(sorted);
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onIntakeEventsChanged(events);
            }
        });
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
