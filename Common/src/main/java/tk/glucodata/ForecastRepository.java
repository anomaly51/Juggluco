package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronizes local CGM history with the configured backend and projects the
 * latest valid 120-minute forecast into the native graph.
 */
final class ForecastRepository {
    private static final String PREFS = "forecast_sync";
    private static final String LEGACY_HISTORY_CURSOR_MS = "history_cursor_ms";
    private static final String HISTORY_CURSOR_PREFIX = "history_cursor_ms_";
    private static final String SERVER_INSTANCE_PREFIX = "server_instance_id_";
    private static final String LIVE_PENDING_TIMESTAMP_MS =
            "live_pending_timestamp_ms";
    private static final long INITIAL_HISTORY_WINDOW_MS =
            45L * 24L * 60L * 60L * 1000L;
    private static final int HISTORY_BATCH_SIZE = 1_000;
    private static final int HISTORY_QUERY_SIZE = HISTORY_BATCH_SIZE + 1;
    private static final int MAX_HISTORY_BATCHES_PER_PASS = 16;
    private static final long OVERLAP_STABILITY_MS = 6L * 60L * 1000L;
    private static final long PASS_THROTTLE_MS = 15_000L;
    private static final int MAX_NATIVE_ACTIVITIES = 1_024;
    private static final int MAX_NATIVE_ACTIVITY_SAMPLES_PER_EVENT = 256;
    private static final int MAX_NATIVE_ACTIVITY_TOTAL_SAMPLES = 65_536;
    private static final long LIVE_WAKE_LOCK_TIMEOUT_MS = 120_000L;
    private static final long LIVE_HANDOFF_WAKE_LOCK_TIMEOUT_MS = 10_000L;
    private static final long LIVE_RETRY_BASE_MS = 1_000L;
    private static final long LIVE_RETRY_MAX_MS = 60_000L;
    private static final long LIVE_NATIVE_MATCH_TOLERANCE_MS = 2_000L;
    private static final int LIVE_NATIVE_QUERY_SIZE = 8;

    interface Listener {
        void onForecastStateChanged(State state);
    }

    static final class State {
        final ForecastSnapshot forecast;
        final ForecastModelStatus model;
        final boolean loading;
        final String error;
        final long updatedAtMs;

        State(ForecastSnapshot forecast, ForecastModelStatus model,
                boolean loading, String error, long updatedAtMs) {
            this.forecast = forecast == null
                    ? ForecastSnapshot.empty("no_data") : forecast;
            this.model = model == null ? ForecastModelStatus.empty() : model;
            this.loading = loading;
            this.error = error == null ? "" : error.trim();
            this.updatedAtMs = updatedAtMs;
        }
    }

    /**
     * Orders successful /current responses independently from transport
     * failures. A later successful no-data/stale/rollback response is
     * authoritative, while an older in-flight response can never resurrect a
     * warning after a newer success or an unresolved local sensor sample.
     */
    static final class CurrentForecastGate {
        enum Outcome { IGNORED, PUBLISH, INVALIDATE }

        static final class Result {
            final Outcome outcome;
            final ForecastSnapshot forecast;

            Result(Outcome outcome, ForecastSnapshot forecast) {
                this.outcome = outcome;
                this.forecast = forecast;
            }
        }

        private long lastSuccessfulRequestId;
        private long lastAcceptedForecastAnchorMs = Long.MIN_VALUE;
        private long lastAcceptedForecastGeneratedAtMs = Long.MIN_VALUE;
        private long unresolvedReadingAtMs;
        private ForecastSnapshot lastAcceptedForecast;

        Result accept(long requestId, ForecastSnapshot candidate, long nowMs) {
            if (candidate == null || requestId <= lastSuccessfulRequestId) {
                return new Result(Outcome.IGNORED, null);
            }
            lastSuccessfulRequestId = requestId;
            boolean beforeUnresolvedReading = unresolvedReadingAtMs > 0L
                    && candidate.basedOnReadingAtMs < unresolvedReadingAtMs;
            boolean keyRollback = !isForecastKeyMonotonic(
                    lastAcceptedForecastAnchorMs,
                    lastAcceptedForecastGeneratedAtMs,
                    candidate.basedOnReadingAtMs, candidate.generatedAtMs);
            if (beforeUnresolvedReading || keyRollback
                    || !candidate.isAlertFresh(nowMs)) {
                // An authoritative invalidation also resets the key epoch so a
                // backend restore with smaller timestamps can recover on its
                // next fresh response.
                lastAcceptedForecastAnchorMs = Long.MIN_VALUE;
                lastAcceptedForecastGeneratedAtMs = Long.MIN_VALUE;
                lastAcceptedForecast = null;
                ForecastSnapshot replacement = beforeUnresolvedReading
                        || keyRollback
                        ? ForecastSnapshot.empty("stale") : candidate;
                return new Result(Outcome.INVALIDATE, replacement);
            }
            if (unresolvedReadingAtMs > 0L
                    && candidate.basedOnReadingAtMs >= unresolvedReadingAtMs) {
                unresolvedReadingAtMs = 0L;
            }
            lastAcceptedForecastAnchorMs = candidate.basedOnReadingAtMs;
            lastAcceptedForecastGeneratedAtMs = candidate.generatedAtMs;
            lastAcceptedForecast = candidate;
            return new Result(Outcome.PUBLISH, candidate);
        }

        void markUnresolved(long measuredAtMs) {
            unresolvedReadingAtMs = Math.max(unresolvedReadingAtMs,
                    Math.max(0L, measuredAtMs));
            lastAcceptedForecast = null;
        }

        boolean transportErrorInvalidates(long nowMs) {
            return lastAcceptedForecast == null
                    || !lastAcceptedForecast.isAlertFresh(nowMs);
        }

        void reset() {
            lastSuccessfulRequestId = 0L;
            lastAcceptedForecastAnchorMs = Long.MIN_VALUE;
            lastAcceptedForecastGeneratedAtMs = Long.MIN_VALUE;
            unresolvedReadingAtMs = 0L;
            lastAcceptedForecast = null;
        }
    }

    private static volatile ForecastRepository instance;
    private static final ScheduledExecutorService LIVE_ENTRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();
    private static final AtomicLong LIVE_PENDING_MS = new AtomicLong();
    private static final AtomicInteger LIVE_RETRY_ATTEMPT =
            new AtomicInteger();
    private static final Object LIVE_SCHEDULE_LOCK = new Object();
    private static ScheduledFuture<?> liveScheduledFuture;
    private static PowerManager.WakeLock liveScheduledHandoffWakeLock;
    private static long liveScheduledAtElapsedMs = Long.MAX_VALUE;
    private static long liveScheduleToken;
    private static boolean liveDrainRunning;

    private final Context application;
    private final SharedPreferences preferences;
    private final IntakeRepository intakeRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService liveForecastExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object predictiveAlertConfigurationLock = new Object();
    private final AtomicLong operationSequence = new AtomicLong();
    private final AtomicLong forecastPublicationSequence = new AtomicLong();
    private final AtomicLong currentRequestSequence = new AtomicLong();
    private final CurrentForecastGate currentForecastGate =
            new CurrentForecastGate();
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final Runnable configurationListener =
            this::onBackendConfigurationChanged;

    private volatile State state = new State(ForecastSnapshot.empty("no_data"),
            ForecastModelStatus.empty(), false, "", 0L);
    private volatile long configurationGeneration;
    private volatile boolean foreground;
    private volatile boolean previewProjection;
    private long lastPassRequestedMs;

    private ForecastRepository(Context context) {
        application = context.getApplicationContext();
        preferences = application.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE);
        intakeRepository = IntakeRepository.get(application);
        configurationGeneration = intakeRepository
                .registerConfigurationListener(configurationListener)
                .generation;
    }

    static ForecastRepository get(Context context) {
        ForecastRepository current = instance;
        if (current == null) {
            synchronized (ForecastRepository.class) {
                current = instance;
                if (current == null) {
                    current = new ForecastRepository(context);
                    instance = current;
                    current.restoreLivePending();
                }
            }
        }
        return current;
    }

    /**
     * Sensor-thread entry point. Live work is latest-wins and runs on its own
     * executor, so a long history backfill can never queue a fresh warning
     * behind thousands of older samples.
     */
    static void enqueueLiveReading(Context context, long measuredAtMs) {
        measuredAtMs = canonicalReadingTimestamp(measuredAtMs);
        if (context == null || Applic.isWearable || measuredAtMs <= 0L) return;
        Context application = context.getApplicationContext();
        long previous;
        do {
            previous = LIVE_PENDING_MS.get();
            if (measuredAtMs <= previous) break;
        } while (!LIVE_PENDING_MS.compareAndSet(previous, measuredAtMs));
        if (measuredAtMs > previous) {
            // apply() updates the in-process preference immediately and moves
            // the disk write off the sensor callback. The drain synchronously
            // confirms the same value off-thread before doing network I/O, so
            // a process restart can recover it without ever storing a bearer
            // token or glucose body.
            application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(LIVE_PENDING_TIMESTAMP_MS, measuredAtMs)
                    .apply();
            LIVE_RETRY_ATTEMPT.set(0);
        }
        // A repeated callback for the same timestamp is also allowed to pull a
        // delayed retry forward. Idempotent cgm-{timestamp} identities make the
        // duplicate attempt harmless.
        scheduleLiveDrain(application, 0L);
    }

    /** Pulls a persisted/backed-off delivery forward on network recovery. */
    static void retryPendingLiveReading(Context context) {
        if (context == null || Applic.isWearable) return;
        try {
            Context application = context.getApplicationContext();
            if (LIVE_PENDING_MS.get() <= 0L
                    && application.getSharedPreferences(PREFS,
                            Context.MODE_PRIVATE).getLong(
                                    LIVE_PENDING_TIMESTAMP_MS, 0L) <= 0L) {
                return;
            }
            LIVE_RETRY_ATTEMPT.set(0);
            ForecastRepository repository = get(application);
            repository.restoreLivePending();
            if (LIVE_PENDING_MS.get() > 0L) {
                scheduleLiveDrain(application, 0L);
            }
        } catch (Throwable error) {
            if (Log.doLog) Log.e("ForecastRepository",
                    "forecast reconnect retry skipped: "
                            + error.getClass().getSimpleName());
        }
    }

    /** Native stream timestamps have one-second precision on every sensor path. */
    static long canonicalReadingTimestamp(long measuredAtMs) {
        return measuredAtMs <= 0L ? 0L : measuredAtMs / 1_000L * 1_000L;
    }

    /** Deterministic exponential retry capped at one minute. */
    static long liveRetryDelayMs(int failedAttempts) {
        int shift = Math.max(0, Math.min(16, failedAttempts));
        long delay = LIVE_RETRY_BASE_MS << shift;
        return Math.min(delay, LIVE_RETRY_MAX_MS);
    }

    private void restoreLivePending() {
        long stored = preferences.getLong(LIVE_PENDING_TIMESTAMP_MS, 0L);
        if (stored <= 0L) return;
        long previous;
        do {
            previous = LIVE_PENDING_MS.get();
            if (stored <= previous) break;
        } while (!LIVE_PENDING_MS.compareAndSet(previous, stored));
        scheduleLiveDrain(application, 0L);
    }

    private static void scheduleLiveDrain(Context application, long delayMs) {
        long delay = Math.max(0L, delayMs);
        long now = SystemClock.elapsedRealtime();
        long due = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
        synchronized (LIVE_SCHEDULE_LOCK) {
            if (liveDrainRunning) return;
            if (liveScheduledFuture != null && !liveScheduledFuture.isDone()) {
                if (liveScheduledAtElapsedMs <= due) {
                    if (delay == 0L
                            && liveScheduledHandoffWakeLock == null) {
                        liveScheduledHandoffWakeLock = acquireLiveWakeLock(
                                application, "Juggluco::ForecastLiveHandoff",
                                LIVE_HANDOFF_WAKE_LOCK_TIMEOUT_MS);
                    }
                    return;
                }
                if (!liveScheduledFuture.cancel(false)) {
                    if (delay == 0L
                            && liveScheduledHandoffWakeLock == null) {
                        liveScheduledHandoffWakeLock = acquireLiveWakeLock(
                                application, "Juggluco::ForecastLiveHandoff",
                                LIVE_HANDOFF_WAKE_LOCK_TIMEOUT_MS);
                    }
                    return;
                }
                releaseLiveWakeLock(liveScheduledHandoffWakeLock);
                liveScheduledHandoffWakeLock = null;
            }
            long token = ++liveScheduleToken;
            liveScheduledAtElapsedMs = due;
            liveScheduledHandoffWakeLock = delay == 0L
                    ? acquireLiveWakeLock(application,
                            "Juggluco::ForecastLiveHandoff",
                            LIVE_HANDOFF_WAKE_LOCK_TIMEOUT_MS) : null;
            try {
                liveScheduledFuture = LIVE_ENTRY_EXECUTOR.schedule(
                        () -> startLiveDrain(application, token), delay,
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                liveScheduledFuture = null;
                liveScheduledAtElapsedMs = Long.MAX_VALUE;
                releaseLiveWakeLock(liveScheduledHandoffWakeLock);
                liveScheduledHandoffWakeLock = null;
                if (Log.doLog) Log.e("ForecastRepository",
                        "forecast live scheduling skipped: "
                                + error.getClass().getSimpleName());
            }
        }
    }

    private static void startLiveDrain(Context application, long token) {
        PowerManager.WakeLock handoffWakeLock;
        synchronized (LIVE_SCHEDULE_LOCK) {
            if (token != liveScheduleToken) return;
            liveScheduledFuture = null;
            liveScheduledAtElapsedMs = Long.MAX_VALUE;
            handoffWakeLock = liveScheduledHandoffWakeLock;
            liveScheduledHandoffWakeLock = null;
            liveDrainRunning = true;
        }
        // Every immediate attempt and every delayed retry gets a fresh bounded
        // wake lock. A backoff never holds a wake lock while it is waiting.
        PowerManager.WakeLock wakeLock = acquireLiveWakeLock(application,
                "Juggluco::ForecastLive", LIVE_WAKE_LOCK_TIMEOUT_MS);
        releaseLiveWakeLock(handoffWakeLock);
        long retryDelayMs = -1L;
        try {
            retryDelayMs = drainLiveReadings(application);
        } catch (Throwable error) {
            retryDelayMs = nextLiveRetryDelayMs();
            if (Log.doLog) Log.e("ForecastRepository",
                    "forecast live drain skipped: "
                            + error.getClass().getSimpleName());
        } finally {
            // Publish the idle state and, when a callback raced this drain,
            // acquire/schedule its immediate handoff while the full attempt
            // wake lock is still held. This closes the last suspend window
            // without holding a lock during an intentional delayed backoff.
            synchronized (LIVE_SCHEDULE_LOCK) {
                liveDrainRunning = false;
                if (LIVE_PENDING_MS.get() > 0L) {
                    scheduleLiveDrain(application,
                            retryDelayMs < 0L ? 0L : retryDelayMs);
                }
            }
            releaseLiveWakeLock(wakeLock);
        }
    }

    private static PowerManager.WakeLock acquireLiveWakeLock(
            Context application, String tag, long timeoutMs) {
        try {
            PowerManager manager = (PowerManager) application.getSystemService(
                    Context.POWER_SERVICE);
            if (manager == null) return null;
            PowerManager.WakeLock wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, tag);
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(timeoutMs);
            return wakeLock;
        } catch (RuntimeException error) {
            if (Log.doLog) Log.e("ForecastRepository",
                    "forecast wake lock unavailable: "
                            + error.getClass().getSimpleName());
            return null;
        }
    }

    private static void releaseLiveWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException error) {
            if (Log.doLog) Log.e("ForecastRepository",
                    "forecast wake lock release skipped: "
                            + error.getClass().getSimpleName());
        }
    }

    private static long drainLiveReadings(Context application) {
        ForecastRepository repository = ForecastRepository.get(application);
        long measuredAtMs = LIVE_PENDING_MS.get();
        if (measuredAtMs <= 0L) return -1L;
        IntakeRepository.BackendIdentity identity =
                repository.intakeRepository.backendIdentity();
        if (identity.generation != repository.configurationGeneration) {
            return nextLiveRetryDelayMs();
        }
        if (!repository.persistLivePending(measuredAtMs)) {
            return nextLiveRetryDelayMs();
        }
        LiveUpload uploaded = repository.recordLiveReading(measuredAtMs,
                identity);
        if (uploaded == null) return nextLiveRetryDelayMs();

        final boolean[] retryImmediately = {false};
        boolean acknowledged = repository.intakeRepository
                .runIfCurrentBackend(identity, () -> {
            // configure() holds the same monitor. It cannot change endpoint or
            // credentials between this identity check and the durable clear.
            LIVE_RETRY_ATTEMPT.set(0);
            if (!LIVE_PENDING_MS.compareAndSet(measuredAtMs, 0L)) {
                repository.persistLivePending(LIVE_PENDING_MS.get());
                retryImmediately[0] = true;
                return;
            }
            repository.clearPersistedLivePending(measuredAtMs);
            long racedPending = LIVE_PENDING_MS.get();
            if (racedPending > 0L) {
                repository.persistLivePending(racedPending);
                retryImmediately[0] = true;
            }
        });
        if (!acknowledged) {
            // The POST belonged to the old immutable identity. Keep the sample
            // pending so the newly configured backend receives its own copy.
            LIVE_RETRY_ATTEMPT.set(0);
            return 0L;
        }
        repository.scheduleLiveForecastFetch(uploaded);
        return retryImmediately[0] ? 0L : -1L;
    }

    private static long nextLiveRetryDelayMs() {
        int attempt = LIVE_RETRY_ATTEMPT.getAndUpdate(
                value -> value >= 30 ? 30 : value + 1);
        return liveRetryDelayMs(attempt);
    }

    private boolean persistLivePending(long measuredAtMs) {
        if (measuredAtMs <= 0L) return false;
        long stored = preferences.getLong(LIVE_PENDING_TIMESTAMP_MS, 0L);
        if (stored > measuredAtMs) return true;
        // commit() is deliberately retained for an equal in-memory value: it
        // waits behind an enqueue-time apply(), confirming durability before
        // the POST begins while remaining off the sensor callback.
        return preferences.edit().putLong(LIVE_PENDING_TIMESTAMP_MS,
                measuredAtMs).commit();
    }

    private boolean clearPersistedLivePending(long uploadedAtMs) {
        long stored = preferences.getLong(LIVE_PENDING_TIMESTAMP_MS, 0L);
        if (stored > uploadedAtMs) return true;
        return preferences.edit().remove(LIVE_PENDING_TIMESTAMP_MS).commit();
    }

    State snapshot() {
        return state;
    }

    void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onForecastStateChanged(state);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void onForeground() {
        foreground = true;
        refresh(true, true);
    }

    void onBackground() {
        foreground = false;
    }

    void refreshFromDashboard() {
        if (foreground) refresh(true, false);
    }

    void refreshNow() {
        refresh(true, true);
    }

    /** Confirmed backend intakes must affect the current prediction immediately. */
    void refreshAfterConfirmedIntake() {
        if (foreground) refresh(false, true);
    }

    private static final class LiveUpload {
        final ForecastApiClient api;
        final long generation;
        final long successVersionAtStart;

        LiveUpload(ForecastApiClient api, long generation,
                long successVersionAtStart) {
            this.api = api;
            this.generation = generation;
            this.successVersionAtStart = successVersionAtStart;
        }
    }

    /** Performs only the time-critical POST on the dedicated live executor. */
    LiveUpload recordLiveReading(long measuredAtMs,
            IntakeRepository.BackendIdentity identity) {
        if (Applic.isWearable || measuredAtMs <= 0L || identity == null) {
            return null;
        }
        final long generation = identity.generation;
        final long successVersionAtStart = forecastPublicationSequence.get();
        final ForecastApiClient api;
        try {
            // During a sensor handover the native timestamp winner can change
            // from the older sensor to the newer one. Let history publish the
            // stabilized winner instead of racing two live payloads under the
            // same immutable cgm-{timestamp} identity.
            if (activeSensorCount() > 1) {
                invalidateForUnresolvedReading(generation, measuredAtMs);
                return null;
            }
            // doglucose() can expose a display-calibrated value. The backend
            // identity must instead use the immutable raw sample that history
            // backfill will send later. If native storage has not committed a
            // nearby canonical row yet, keep the pending timestamp for a
            // retry rather than uploading a conflicting fallback.
            ForecastReading reading = nearestNativeReading(measuredAtMs);
            if (reading == null) {
                invalidateForUnresolvedReading(generation, measuredAtMs);
                return null;
            }
            if (generation != configurationGeneration) return null;
            api = client(identity);
            api.uploadReadings(Collections.singletonList(reading));
        } catch (Exception error) {
            publishLiveError(generation, successVersionAtStart, error);
            return null;
        }

        return new LiveUpload(api, generation, successVersionAtStart);
    }

    /** Forecast reads are never allowed to hold up ACK or the next live POST. */
    private void scheduleLiveForecastFetch(LiveUpload uploaded) {
        if (uploaded == null) return;
        try {
            liveForecastExecutor.execute(() -> {
                if (uploaded.generation != configurationGeneration) return;
                try {
                    fetchAndPublish(uploaded.api, uploaded.generation, 0L);
                } catch (Exception error) {
                    publishLiveError(uploaded.generation,
                            uploaded.successVersionAtStart, error);
                }
            });
        } catch (RuntimeException error) {
            publishLiveError(uploaded.generation,
                    uploaded.successVersionAtStart, error);
        }
    }

    private static ForecastReading nearestNativeReading(long measuredAtMs) {
        final long[] raw;
        try {
            // Java captures the callback time immediately before a native
            // parser that stores time(NULL). Crossing a second boundary in
            // that JNI call can therefore move the immutable row by one
            // second. Query a deliberately narrow window and upload the row's
            // actual native timestamp rather than inventing Java precision.
            long afterMs = Math.max(0L, measuredAtMs
                    - LIVE_NATIVE_MATCH_TOLERANCE_MS - 1L);
            raw = Natives.forecastReadings(afterMs, LIVE_NATIVE_QUERY_SIZE);
        } catch (UnsatisfiedLinkError error) {
            return null;
        }
        return nearestReading(decodeNativeReadings(raw), measuredAtMs,
                LIVE_NATIVE_MATCH_TOLERANCE_MS);
    }

    /**
     * Sensor overlap or a not-yet-committed immutable row makes the current
     * sample ambiguous. Clear delivery immediately and erect an anchor barrier
     * so an older in-flight /current response cannot restore it.
     */
    private void invalidateForUnresolvedReading(long generation,
            long measuredAtMs) {
        final long publicationId;
        synchronized (predictiveAlertConfigurationLock) {
            if (generation != configurationGeneration) return;
            currentForecastGate.markUnresolved(measuredAtMs);
            publicationId = forecastPublicationSequence.incrementAndGet();
            try {
                PredictiveAlertCoordinator.get(application)
                        .onForecastUnavailable();
            } catch (Throwable ignored) {
                // The native actual alarm path remains independent.
            }
        }
        State previous = state;
        publishForecast(generation, publicationId,
                new State(ForecastSnapshot.empty("stale"), previous.model,
                        false, "", System.currentTimeMillis()));
    }

    static ForecastReading exactReading(List<ForecastReading> readings,
            long measuredAtMs) {
        if (readings == null) return null;
        for (ForecastReading reading : readings) {
            if (reading != null && reading.measuredAtMs == measuredAtMs) {
                return reading;
            }
        }
        return null;
    }

    /** Finds the immutable native row nearest a pre-JNI callback timestamp. */
    static ForecastReading nearestReading(List<ForecastReading> readings,
            long measuredAtMs, long toleranceMs) {
        if (readings == null || measuredAtMs <= 0L || toleranceMs < 0L) {
            return null;
        }
        ForecastReading nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (ForecastReading reading : readings) {
            if (reading == null) continue;
            long distance = Math.abs(reading.measuredAtMs - measuredAtMs);
            if (distance > toleranceMs) continue;
            if (distance < nearestDistance || (distance == nearestDistance
                    && nearest != null
                    && reading.measuredAtMs > nearest.measuredAtMs)) {
                nearest = reading;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void refresh(boolean backfill, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (!force && now - lastPassRequestedMs < PASS_THROTTLE_MS) return;
            lastPassRequestedMs = now;
        }
        final IntakeRepository.BackendIdentity identity =
                intakeRepository.backendIdentity();
        final long generation = identity.generation;
        if (generation != configurationGeneration) return;
        final long operationId = operationSequence.incrementAndGet();
        final long successVersionAtStart = forecastPublicationSequence.get();
        final ForecastApiClient api;
        try {
            api = client(identity);
        } catch (IllegalArgumentException error) {
            publishRefreshError(generation, operationId,
                    successVersionAtStart, error);
            return;
        }
        executor.execute(() -> {
            if (generation != configurationGeneration) return;
            publishLoading(generation, operationId);
            try {
                // History upload always completes. Forecast responses from this
                // executor and the live executor are ordered by their payload
                // keys, never by which request happened to start last.
                if (backfill) uploadNativeHistory(api, generation, identity);
                if (generation != configurationGeneration) return;
                fetchAndPublish(api, generation, operationId);
            } catch (Exception error) {
                publishRefreshError(generation, operationId,
                        successVersionAtStart, error);
            }
        });
    }

    private void uploadNativeHistory(ForecastApiClient api, long generation,
            IntakeRepository.BackendIdentity identity) throws Exception {
        ForecastModelStatus initialRemote = api.forecastStatus();
        if (generation != configurationGeneration) return;
        synchronizeServerInstance(generation, identity,
                initialRemote.serverInstanceId);
        if (generation != configurationGeneration) return;
        long afterMs = historyCursor(generation, identity);
        boolean resetAttempted = false;
        for (int batch = 0; batch < MAX_HISTORY_BATCHES_PER_PASS; batch++) {
            if (generation != configurationGeneration) return;
            final long[] raw;
            try {
                raw = Natives.forecastReadings(afterMs, HISTORY_QUERY_SIZE);
            } catch (UnsatisfiedLinkError error) {
                return;
            }
            if (generation != configurationGeneration) return;
            if (raw == null || raw.length < 3) {
                // JNI can be temporarily empty while native sensor state is
                // still loading. Never turn that transient state into a
                // permanent high-water mark. If the same configured backend
                // was reset, its empty status is an explicit signal to replay
                // the bounded local history from the beginning.
                long initial = initialHistoryCursor();
                if (!resetAttempted && afterMs > initial + 60_000L) {
                    ForecastModelStatus remote = api.forecastStatus();
                    if (generation != configurationGeneration) return;
                    if (remote.readingCount == 0L) {
                        clearHistoryCursor(generation, identity);
                        afterMs = initialHistoryCursor();
                        resetAttempted = true;
                        batch--;
                        continue;
                    }
                }
                // An empty JNI result can be transient while native sensor
                // state is loading, so it is never a training boundary. The
                // 1001-row lookahead below proves exact-multiple completion
                // on the preceding non-empty batch without relying on empty.
                return;
            }
            long cutoffMs = stableHistoryCutoff(System.currentTimeMillis(),
                    activeSensorCount());
            HistoryUpload upload = prepareHistoryUpload(raw, afterMs,
                    cutoffMs);
            if (!upload.hasStableRows || upload.cursorMs <= afterMs) return;
            api.uploadReadings(upload.readings, upload.complete);
            if (generation != configurationGeneration) return;
            afterMs = upload.cursorMs;
            storeHistoryCursor(generation, identity, afterMs);
            if (upload.complete) return;
        }
    }

    static final class HistoryUpload {
        final ArrayList<ForecastReading> readings;
        final long cursorMs;
        final boolean complete;
        final boolean hasStableRows;

        HistoryUpload(ArrayList<ForecastReading> readings, long cursorMs,
                boolean complete, boolean hasStableRows) {
            this.readings = readings;
            this.cursorMs = cursorMs;
            this.complete = complete;
            this.hasStableRows = hasStableRows;
        }
    }

    /** Builds a <=1000-row upload while retaining one deterministic lookahead. */
    static HistoryUpload prepareHistoryUpload(long[] raw, long afterMs,
            long stableCutoffMs) {
        ArrayList<ForecastReading> readings = new ArrayList<>();
        long cursorMs = afterMs;
        int stableRows = 0;
        boolean moreStableRows = false;
        if (raw != null) {
            int usable = raw.length - raw.length % 3;
            for (int index = 0; index < usable; index += 3) {
                long measuredAtMs = raw[index];
                if (measuredAtMs <= afterMs
                        || measuredAtMs > stableCutoffMs) continue;
                stableRows++;
                if (stableRows > HISTORY_BATCH_SIZE) {
                    moreStableRows = true;
                    break;
                }
                cursorMs = Math.max(cursorMs, measuredAtMs);
                long glucoseLong = raw[index + 1];
                if (glucoseLong <= 0L
                        || glucoseLong > Integer.MAX_VALUE) continue;
                float trend = Float.intBitsToFloat((int) raw[index + 2]);
                try {
                    readings.add(ForecastReading.historical(measuredAtMs,
                            (int) glucoseLong, trend));
                } catch (IllegalArgumentException ignored) {
                    // A corrupt local row is consumed but never sent. Valid
                    // neighbours still reach the backend in the same batch.
                }
            }
        }
        return new HistoryUpload(readings, cursorMs, !moreStableRows,
                stableRows > 0);
    }

    static long stableHistoryCutoff(long nowMs, int activeSensorCount) {
        return activeSensorCount > 1
                ? Math.max(0L, nowMs - OVERLAP_STABILITY_MS)
                : Long.MAX_VALUE;
    }

    private static int activeSensorCount() {
        try {
            String[] active = Natives.activeSensors();
            return active == null ? 0 : active.length;
        } catch (UnsatisfiedLinkError error) {
            return 0;
        }
    }

    static ArrayList<ForecastReading> decodeNativeReadings(long[] raw) {
        ArrayList<ForecastReading> readings = new ArrayList<>();
        if (raw == null) return readings;
        int usable = raw.length - raw.length % 3;
        for (int index = 0; index < usable; index += 3) {
            long measuredAtMs = raw[index];
            long glucoseLong = raw[index + 1];
            if (glucoseLong <= 0L || glucoseLong > Integer.MAX_VALUE) continue;
            float trend = Float.intBitsToFloat((int) raw[index + 2]);
            try {
                readings.add(ForecastReading.historical(measuredAtMs,
                        (int) glucoseLong, trend));
            } catch (IllegalArgumentException ignored) {
                // Skip a corrupt native history row without losing the batch.
            }
        }
        return readings;
    }

    private void fetchAndPublish(ForecastApiClient api, long generation,
            long refreshOperationId) throws Exception {
        long currentRequestId = currentRequestSequence.incrementAndGet();
        ForecastSnapshot forecast = api.currentForecast();
        // Delivery does not depend on the slower diagnostics endpoint. Post a
        // valid, checksum-gated assessment as soon as /current returns.
        AcceptedForecast accepted = acceptForecast(generation,
                currentRequestId, forecast);
        if (accepted == null) {
            publishRefreshComplete(generation, refreshOperationId);
            return;
        }
        ForecastModelStatus model;
        try {
            model = api.forecastStatus();
        } catch (Exception statusError) {
            // Current forecast is the complete, checksum-gated delivery
            // contract. A secondary diagnostics failure must not discard a
            // valid time-critical assessment.
            model = state.model;
            if (Log.doLog) Log.e("ForecastRepository",
                    "forecast status unavailable: "
                            + statusError.getClass().getSimpleName());
        }
        publishForecast(generation, accepted.publicationId,
                new State(accepted.forecast, model, false, "",
                System.currentTimeMillis()));
    }

    private static final class AcceptedForecast {
        final long publicationId;
        final ForecastSnapshot forecast;

        AcceptedForecast(long publicationId, ForecastSnapshot forecast) {
            this.publicationId = publicationId;
            this.forecast = forecast;
        }
    }

    /**
     * Accepts non-decreasing lexicographic (reading anchor, generation time)
     * keys. Equal responses may refresh diagnostics; an older response can
     * never displace a forecast already accepted from the other executor.
     */
    private AcceptedForecast acceptForecast(long generation,
            long currentRequestId, ForecastSnapshot forecast) {
        if (forecast == null) return null;
        synchronized (predictiveAlertConfigurationLock) {
            if (generation != configurationGeneration) return null;
            CurrentForecastGate.Result result = currentForecastGate.accept(
                    currentRequestId, forecast, System.currentTimeMillis());
            if (result.outcome == CurrentForecastGate.Outcome.IGNORED) {
                return null;
            }
            long publicationId = forecastPublicationSequence.incrementAndGet();
            try {
                if (result.outcome == CurrentForecastGate.Outcome.INVALIDATE) {
                    PredictiveAlertCoordinator.get(application)
                            .onForecastUnavailable();
                } else {
                    PredictiveAlertCoordinator.get(application).onForecast(
                            result.forecast, System.currentTimeMillis());
                }
            } catch (Throwable error) {
                // A notification problem must never interfere with graph refresh,
                // CGM ingestion, or the independent native low/high alarms.
                if (Log.doLog) Log.e("ForecastRepository",
                        "predictive alert skipped: "
                                + error.getClass().getSimpleName());
            }
            return new AcceptedForecast(publicationId, result.forecast);
        }
    }

    static boolean isForecastKeyMonotonic(long previousAnchorMs,
            long previousGeneratedAtMs, long candidateAnchorMs,
            long candidateGeneratedAtMs) {
        return candidateAnchorMs > previousAnchorMs
                || (candidateAnchorMs == previousAnchorMs
                && candidateGeneratedAtMs >= previousGeneratedAtMs);
    }

    private static ForecastApiClient client(
            IntakeRepository.BackendIdentity identity) {
        return new ForecastApiClient(identity.url, identity.token);
    }

    private long historyCursor(long generation,
            IntakeRepository.BackendIdentity identity) {
        if (generation != configurationGeneration) return 0L;
        String key = historyCursorKey(identity);
        if (generation != configurationGeneration) return 0L;
        long stored = preferences.getLong(key, 0L);
        return stored > 0L ? stored : initialHistoryCursor();
    }

    private static long initialHistoryCursor() {
        return Math.max(0L,
                System.currentTimeMillis() - INITIAL_HISTORY_WINDOW_MS);
    }

    private void storeHistoryCursor(long generation,
            IntakeRepository.BackendIdentity identity, long value) {
        if (generation != configurationGeneration) return;
        String key = historyCursorKey(identity);
        if (generation != configurationGeneration) return;
        preferences.edit().putLong(key,
                Math.max(0L, value)).apply();
    }

    private void clearHistoryCursor(long generation,
            IntakeRepository.BackendIdentity identity) {
        if (generation != configurationGeneration) return;
        String key = historyCursorKey(identity);
        if (generation != configurationGeneration) return;
        preferences.edit().remove(key).apply();
    }

    private void synchronizeServerInstance(long generation,
            IntakeRepository.BackendIdentity identity,
            String remoteInstanceId) {
        if (generation != configurationGeneration) return;
        String remote = remoteInstanceId == null
                ? "" : remoteInstanceId.trim();
        if (remote.isEmpty()) return; // Compatibility with an older backend.
        String key = serverInstanceKey(identity);
        if (generation != configurationGeneration) return;
        String stored = preferences.getString(key, "");
        if (!serverInstanceChanged(stored, remote)) return;
        clearHistoryCursor(generation, identity);
        if (generation != configurationGeneration) return;
        preferences.edit().putString(key, remote).apply();
    }

    static boolean serverInstanceChanged(String stored, String remote) {
        String normalized = remote == null ? "" : remote.trim();
        return !normalized.isEmpty()
                && !normalized.equals(stored == null ? "" : stored.trim());
    }

    /** A token is never stored in the preference key; only its SHA-256 digest is. */
    private static String historyCursorKey(
            IntakeRepository.BackendIdentity identity) {
        return HISTORY_CURSOR_PREFIX + backendPreferenceSuffix(identity);
    }

    private static String serverInstanceKey(
            IntakeRepository.BackendIdentity identity) {
        return SERVER_INSTANCE_PREFIX + backendPreferenceSuffix(identity);
    }

    private static String backendPreferenceSuffix(
            IntakeRepository.BackendIdentity backend) {
        String identity = backend.url + "\n" + backend.token;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder suffix = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                suffix.append(String.format(java.util.Locale.ROOT, "%02x",
                        digest[index] & 0xff));
            }
            return suffix.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory on Android; keep a non-secret fallback for
            // unusual unit-test runtimes.
            return Integer.toHexString(identity.hashCode());
        }
    }

    private boolean isCurrentOperation(long generation, long operationId) {
        return generation == configurationGeneration
                && operationId == operationSequence.get();
    }

    private void publishLoading(long generation, long operationId) {
        main.post(() -> {
            if (!isCurrentOperation(generation, operationId)) return;
            State previous = state;
            commitState(new State(previous.forecast, previous.model,
                    true, "", previous.updatedAtMs), false);
        });
    }

    private void publishRefreshComplete(long generation, long operationId) {
        if (operationId <= 0L) return;
        main.post(() -> {
            if (!isCurrentOperation(generation, operationId)) return;
            State previous = state;
            commitState(new State(previous.forecast, previous.model,
                    false, "", previous.updatedAtMs), false);
        });
    }

    /** History/foreground failures are diagnostic and preserve live alerts. */
    private void publishRefreshError(long generation, long operationId,
            long successVersionAtStart, Exception error) {
        final String message = errorMessage(error);
        main.post(() -> {
            if (!isCurrentOperation(generation, operationId)
                    || forecastPublicationSequence.get()
                    != successVersionAtStart) return;
            State previous = state;
            commitState(new State(previous.forecast, previous.model,
                    false, message, previous.updatedAtMs), false);
        });
    }

    /**
     * A failed live upload/current pass may invalidate predictive delivery, but
     * never after a newer forecast has succeeded or configuration has changed.
     */
    private void publishLiveError(long generation,
            long successVersionAtStart, Exception error) {
        final String message = errorMessage(error);
        boolean invalidateDelivery = false;
        synchronized (predictiveAlertConfigurationLock) {
            if (generation != configurationGeneration
                    || forecastPublicationSequence.get()
                    != successVersionAtStart) return;
            invalidateDelivery = currentForecastGate.transportErrorInvalidates(
                    System.currentTimeMillis());
            if (invalidateDelivery) {
                try {
                    PredictiveAlertCoordinator.get(application)
                            .onForecastUnavailable();
                } catch (Throwable ignored) {
                    // Native low/high alarms and error publication stay independent.
                }
            }
        }
        main.post(() -> {
            if (generation != configurationGeneration
                    || forecastPublicationSequence.get()
                    != successVersionAtStart) return;
            State previous = state;
            commitState(new State(previous.forecast, previous.model,
                    false, message, previous.updatedAtMs), true);
        });
    }

    private static String errorMessage(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "Backend unavailable"
                    : error.getClass().getSimpleName();
        }
        return message;
    }

    private void publishForecast(long generation, long publicationId,
            State replacement) {
        main.post(() -> {
            if (generation != configurationGeneration
                    || publicationId != forecastPublicationSequence.get()) return;
            commitState(replacement, true);
        });
    }

    private void commitState(State replacement, boolean updateGraph) {
        state = replacement;
        if (updateGraph && !previewProjection) {
            publishGraphOrClear(replacement);
        }
        for (Listener listener : listeners) {
            listener.onForecastStateChanged(replacement);
        }
    }

    private void onBackendConfigurationChanged() {
        IntakeRepository.BackendIdentity identity =
                intakeRepository.backendIdentity();
        synchronized (predictiveAlertConfigurationLock) {
            configurationGeneration = identity.generation;
            operationSequence.incrementAndGet();
            forecastPublicationSequence.incrementAndGet();
            currentForecastGate.reset();
            try {
                PredictiveAlertCoordinator.get(application)
                        .onBackendConfigurationChanged();
            } catch (Throwable ignored) {
                // Configuration reset remains best-effort for the additive alert
                // layer and cannot block the existing forecast repository.
            }
        }
        preferences.edit().remove(LEGACY_HISTORY_CURSOR_MS).apply();
        state = new State(ForecastSnapshot.empty("no_data"),
                ForecastModelStatus.empty(), false, "", 0L);
        if (!previewProjection) clearNativeForecast();
        for (Listener listener : listeners) {
            listener.onForecastStateChanged(state);
        }
        // A request already in flight belongs to the captured old backend and
        // cannot clear this timestamp after the generation change. Retry it
        // immediately with the explicitly selected identity.
        long storedPending = preferences.getLong(
                LIVE_PENDING_TIMESTAMP_MS, 0L);
        long previous;
        do {
            previous = LIVE_PENDING_MS.get();
            if (storedPending <= previous) break;
        } while (!LIVE_PENDING_MS.compareAndSet(previous, storedPending));
        LIVE_RETRY_ATTEMPT.set(0);
        if (LIVE_PENDING_MS.get() > 0L) {
            scheduleLiveDrain(application, 0L);
        }
        if (foreground) refresh(true, true);
    }

    /** Keeps the deterministic graph preview isolated from backend state. */
    void showDebugPreview(long nowMs, float currentMgDl) {
        if (!BuildConfig.DEBUG) return;
        previewProjection = true;
        int count = ForecastSnapshot.MAX_HORIZON_MINUTES / 5;
        long[] times = new long[count];
        float[] median = new float[count];
        float[] low = new float[count];
        float[] high = new float[count];
        for (int index = 0; index < count; index++) {
            int minutes = (index + 1) * 5;
            times[index] = nowMs + minutes * 60_000L;
            float rise = 20f * (1f - (float) Math.exp(-minutes / 35f));
            float settle = Math.max(0f, minutes - 55f) * .22f;
            median[index] = currentMgDl + rise - settle;
            float spread = 5f + minutes * .12f;
            low[index] = median[index] - spread;
            high[index] = median[index] + spread;
        }
        try {
            Natives.setForecast(times, median, low, high, .72f);
            Natives.setForecastActivities(
                    new int[]{ForecastSnapshot.Activity.KIND_MEAL,
                            ForecastSnapshot.Activity.KIND_RAPID,
                            ForecastSnapshot.Activity.KIND_LONG},
                    new long[]{nowMs - 35L * 60_000L,
                            nowMs - 20L * 60_000L,
                            nowMs - 5L * 60L * 60_000L},
                    new long[]{nowMs + 10L * 60_000L,
                            nowMs + 40L * 60_000L,
                            nowMs + 2L * 60L * 60_000L},
                    new long[]{nowMs + 2L * 60L * 60_000L,
                            nowMs + 3L * 60L * 60_000L,
                            nowMs + 24L * 60L * 60_000L},
                    new float[]{.82f, .74f, .42f},
                    new float[]{.70f, .76f, .62f});
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /** Leaves preview mode and restores only a fresh, sufficient real result. */
    void restoreGraphProjection() {
        previewProjection = false;
        publishGraphOrClear(state);
    }

    private static void publishGraphOrClear(State value) {
        ForecastSnapshot forecast = value.forecast;
        // A transport error is diagnostic, not an authoritative revocation of
        // a still-fresh accepted forecast. no_data/stale/rollback paths replace
        // the snapshot itself and therefore still clear here.
        if (!forecast.isGraphUsable(System.currentTimeMillis())) {
            clearNativeForecast();
            return;
        }
        int pointCount = forecast.points.size();
        long[] times = new long[pointCount];
        float[] median = new float[pointCount];
        float[] low = new float[pointCount];
        float[] high = new float[pointCount];
        for (int index = 0; index < pointCount; index++) {
            ForecastSnapshot.Point point = forecast.points.get(index);
            times[index] = point.atMs;
            median[index] = point.medianMgDl;
            low[index] = point.lowMgDl;
            high[index] = point.highMgDl;
        }
        NativeActivityProjection activities = nativeActivityProjection(forecast);
        try {
            Natives.setForecast(times, median, low, high,
                    forecast.confidence);
        } catch (UnsatisfiedLinkError ignored) {
        }
        try {
            Natives.setForecastActivitiesRangedSampled(activities.kinds,
                    activities.identityHashes, activities.startsMs,
                    activities.onsetsMs, activities.peaksMs,
                    activities.peakLowsMs, activities.peakHighsMs,
                    activities.endsMs, activities.endLowsMs,
                    activities.endHighsMs, activities.strengths,
                    activities.confidences,
                    activities.attributionConfidences,
                    activities.overlapCounts, activities.sampleCounts,
                    activities.sampleTimesMs, activities.sampleLevels);
        } catch (UnsatisfiedLinkError ignored) {
            try {
                Natives.setForecastActivitiesSampled(activities.kinds,
                        activities.startsMs, activities.peaksMs,
                        activities.endsMs, activities.strengths,
                        activities.confidences, activities.sampleCounts,
                        activities.sampleTimesMs, activities.sampleLevels);
            } catch (UnsatisfiedLinkError sampledIgnored) {
                // An APK/process with the oldest bridge still receives the
                // summary projection; ranges and sampled curves are omitted.
                try {
                    Natives.setForecastActivities(activities.kinds,
                            activities.startsMs, activities.peaksMs,
                            activities.endsMs, activities.strengths,
                            activities.confidences);
                } catch (UnsatisfiedLinkError alsoIgnored) {
                }
            }
        }
    }

    /** Flattened, bounded JNI payload kept package-visible for contract tests. */
    static final class NativeActivityProjection {
        final int[] kinds;
        final int[] identityHashes;
        final long[] startsMs;
        final long[] onsetsMs;
        final long[] peaksMs;
        final long[] peakLowsMs;
        final long[] peakHighsMs;
        final long[] endsMs;
        final long[] endLowsMs;
        final long[] endHighsMs;
        final float[] strengths;
        final float[] confidences;
        final float[] attributionConfidences;
        final int[] overlapCounts;
        final int[] sampleCounts;
        final long[] sampleTimesMs;
        final float[] sampleLevels;

        NativeActivityProjection(int[] kinds, int[] identityHashes,
                long[] startsMs, long[] onsetsMs, long[] peaksMs,
                long[] peakLowsMs, long[] peakHighsMs, long[] endsMs,
                long[] endLowsMs, long[] endHighsMs, float[] strengths,
                float[] confidences, float[] attributionConfidences,
                int[] overlapCounts, int[] sampleCounts, long[] sampleTimesMs,
                float[] sampleLevels) {
            this.kinds = kinds;
            this.identityHashes = identityHashes;
            this.startsMs = startsMs;
            this.onsetsMs = onsetsMs;
            this.peaksMs = peaksMs;
            this.peakLowsMs = peakLowsMs;
            this.peakHighsMs = peakHighsMs;
            this.endsMs = endsMs;
            this.endLowsMs = endLowsMs;
            this.endHighsMs = endHighsMs;
            this.strengths = strengths;
            this.confidences = confidences;
            this.attributionConfidences = attributionConfidences;
            this.overlapCounts = overlapCounts;
            this.sampleCounts = sampleCounts;
            this.sampleTimesMs = sampleTimesMs;
            this.sampleLevels = sampleLevels;
        }
    }

    static NativeActivityProjection nativeActivityProjection(
            ForecastSnapshot forecast) {
        int activityCount = forecast == null ? 0 : Math.min(
                forecast.activities.size(), MAX_NATIVE_ACTIVITIES);
        int[] kinds = new int[activityCount];
        int[] identities = new int[activityCount];
        long[] starts = new long[activityCount];
        long[] onsets = new long[activityCount];
        long[] peaks = new long[activityCount];
        long[] peakLows = new long[activityCount];
        long[] peakHighs = new long[activityCount];
        long[] ends = new long[activityCount];
        long[] endLows = new long[activityCount];
        long[] endHighs = new long[activityCount];
        float[] strengths = new float[activityCount];
        float[] confidences = new float[activityCount];
        float[] attributionConfidences = new float[activityCount];
        int[] overlapCounts = new int[activityCount];
        int[] sampleCounts = new int[activityCount];
        ArrayList<ArrayList<ForecastSnapshot.ActivityPoint>> samples =
                new ArrayList<>(activityCount);
        int totalSamples = 0;
        long anchorMs = forecast == null ? 0L
                : forecast.basedOnReadingAtMs;
        for (int index = 0; index < activityCount; index++) {
            ForecastSnapshot.Activity activity = forecast.activities.get(index);
            kinds[index] = activity.kind;
            identities[index] = stableActivityIdentity(activity);
            starts[index] = activity.startMs;
            onsets[index] = activity.onsetMs == null ? 0L
                    : activity.onsetMs;
            peaks[index] = activity.peakMs;
            peakLows[index] = activity.peakLowMs == null ? 0L
                    : activity.peakLowMs;
            peakHighs[index] = activity.peakHighMs == null ? 0L
                    : activity.peakHighMs;
            ends[index] = activity.endMs;
            endLows[index] = activity.endLowMs == null ? 0L
                    : activity.endLowMs;
            endHighs[index] = activity.endHighMs == null ? 0L
                    : activity.endHighMs;
            strengths[index] = activity.strength;
            confidences[index] = activity.confidence;
            attributionConfidences[index] =
                    activity.attributionConfidence == null ? -1f
                            : activity.attributionConfidence;
            overlapCounts[index] = activity.overlapCount;
            ArrayList<ForecastSnapshot.ActivityPoint> accepted =
                    new ArrayList<>();
            long previousSecond = Long.MIN_VALUE;
            if (anchorMs > 0L) {
                for (ForecastSnapshot.ActivityPoint point : activity.points) {
                    long second = point.atMs / 1_000L;
                    if (point.atMs < anchorMs || point.atMs <= 0L
                            || second <= previousSecond
                            || !Float.isFinite(point.activity)
                            || accepted.size()
                            >= MAX_NATIVE_ACTIVITY_SAMPLES_PER_EVENT) {
                        continue;
                    }
                    accepted.add(point);
                    previousSecond = second;
                }
            }
            if (accepted.size() < 2
                    || totalSamples + accepted.size()
                    > MAX_NATIVE_ACTIVITY_TOTAL_SAMPLES) {
                accepted.clear();
            }
            sampleCounts[index] = accepted.size();
            totalSamples += accepted.size();
            samples.add(accepted);
        }
        long[] sampleTimes = new long[totalSamples];
        float[] sampleLevels = new float[totalSamples];
        int sampleIndex = 0;
        for (ArrayList<ForecastSnapshot.ActivityPoint> activitySamples
                : samples) {
            for (ForecastSnapshot.ActivityPoint point : activitySamples) {
                sampleTimes[sampleIndex] = point.atMs;
                sampleLevels[sampleIndex] = ForecastSnapshot.clamp01(
                        point.activity);
                sampleIndex++;
            }
        }
        return new NativeActivityProjection(kinds, identities, starts, onsets,
                peaks, peakLows, peakHighs, ends, endLows, endHighs,
                strengths, confidences, attributionConfidences, overlapCounts,
                sampleCounts, sampleTimes, sampleLevels);
    }

    private static int stableActivityIdentity(
            ForecastSnapshot.Activity activity) {
        String source = activity.eventId.isEmpty()
                ? activity.kind + "|" + activity.label + "|"
                        + activity.startMs + "|" + activity.amount
                : activity.eventId;
        int hash = source.hashCode();
        return hash == 0 ? 1 : hash;
    }

    static void clearNativeForecast() {
        try {
            Natives.setForecast(new long[0], new float[0], new float[0],
                    new float[0], 0f);
            Natives.setForecastActivities(new int[0], new long[0],
                    new long[0], new long[0], new float[0], new float[0]);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }
}
