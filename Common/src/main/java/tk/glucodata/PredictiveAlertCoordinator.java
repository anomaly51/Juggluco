package tk.glucodata;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Process-wide episode/cooldown coordinator for forecast notifications.
 * Actual glucose threshold alarms continue to run through the native alarm path.
 */
final class PredictiveAlertCoordinator {
    private static final float REARM_HYSTERESIS_MG_DL = 5.4f; // 0.3 mmol/L
    private static final int REARM_POINTS = 3;
    private static final long DELIVERY_CONFIRM_DELAY_MS = 750L;
    private static volatile PredictiveAlertCoordinator instance;

    private final Context application;
    private final PredictiveAlertPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RuntimeDeliveryLatch runtimeDelivery =
            new RuntimeDeliveryLatch();
    private long deliveryProofSequence;
    private ForecastSnapshot lastForecast;

    private PredictiveAlertCoordinator(Context context) {
        application = context.getApplicationContext();
        preferences = new PredictiveAlertPreferences(application);
        PredictiveAlertNotifier.ensureChannels(application);
        // Never inherit a stale runtime handoff after process/native startup.
        preferences.setLegacyPredictionSuppression(false, false);
    }

    static PredictiveAlertCoordinator get(Context context) {
        PredictiveAlertCoordinator current = instance;
        if (current == null) {
            synchronized (PredictiveAlertCoordinator.class) {
                current = instance;
                if (current == null) {
                    current = new PredictiveAlertCoordinator(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    synchronized void onForecast(ForecastSnapshot forecast, long nowMs) {
        lastForecast = forecast;
        PredictiveAlertPreferences.Snapshot settings = preferences.snapshot();
        LegacySuppression replacement = updateLegacySuppression(settings,
                forecast, nowMs);
        if (!replacement.anyReplacementOperational()) {
            // Model/backend/readiness state is not evidence that a previously
            // delivered risk resolved. Native early warnings fail open above,
            // while an acknowledged/snoozed shared alarm session keeps owning
            // its own lifecycle.
            return;
        }
        float current = forecast.basedOnGlucoseMgDl;
        if (current < forecast.alertAssessment.targetLowMgDl
                || current > forecast.alertAssessment.targetHighMgDl) {
            // Crossing the predictive target is not the same as the native
            // actual-low/high threshold firing. Keep any delivered prediction
            // alive until explicit user action, safe resolution, or the shared
            // controller's actual-alarm handoff.
            preferences.setLegacyPredictionSuppression(false, false);
            return;
        }

        ForecastRiskEvaluator.Policy policy = new ForecastRiskEvaluator.Policy(
                true, replacement.low, replacement.high,
                settings.lowHorizonMinutes, settings.highHorizonMinutes,
                settings.lowSensitivity, settings.highSensitivity);
        ForecastRiskEvaluator.Decision decision = ForecastRiskEvaluator.evaluate(
                forecast, policy, nowMs);
        if (!decision.shouldNotify()) {
            if (canResolveEpisode(forecast, nowMs)) {
                clearEpisodeAndNotifications();
            }
            return;
        }

        String direction = decision.direction == ForecastRiskEvaluator.Direction.LOW
                ? PredictiveAlertPreferences.DIRECTION_LOW
                : PredictiveAlertPreferences.DIRECTION_HIGH;
        if (preferences.snoozeBlocks(direction, nowMs)) return;
        if (isEvidenceDowngrade(preferences.activeEpisodeDirection(),
                preferences.activeEvidence(), direction,
                decision.evidence)) return;
        boolean snoozeReplay = preferences.snoozeReplayDue(direction,
                decision.anchorMs, nowMs);
        boolean evidenceUpgrade = isEvidenceUpgrade(
                preferences.activeEpisodeDirection(),
                preferences.activeEvidence(), direction, decision.evidence);
        boolean bypassEpisodeGates = snoozeReplay || evidenceUpgrade;
        if (!bypassEpisodeGates
                && decision.anchorMs <= preferences.activeAnchorMs()
                && direction.equals(preferences.activeEpisodeDirection())) {
            return;
        }
        long cooldownMs = cooldownMinutesForDirection(settings, direction)
                * 60_000L;
        long lastAlertAt = preferences.lastAlertAt(direction);
        if (!bypassEpisodeGates && lastAlertAt > 0L
                && nowMs - lastAlertAt < cooldownMs) return;

        boolean shown = false;
        try {
            shown = PredictiveAlertNotifier.show(application, decision,
                    forecast);
        } catch (Throwable error) {
            Log.stack("PredictiveAlerts", "show", error);
        }
        if (shown) {
            ForecastRiskEvaluator.Direction opposite =
                    decision.direction == ForecastRiskEvaluator.Direction.LOW
                            ? ForecastRiskEvaluator.Direction.HIGH
                            : ForecastRiskEvaluator.Direction.LOW;
            PredictiveAlertNotifier.cancel(application, opposite);
            preferences.recordAlert(direction, nowMs, decision.anchorMs,
                    decision.evidence);
            long notificationExpiresAtMs = PredictiveAlertNotifier.activeUntilMs(
                    decision.direction);
            long forecastExpiresAtMs = decision.anchorMs
                    + ForecastSnapshot.MAX_ALERT_AGE_MS;
            long expiresAtMs = Math.min(notificationExpiresAtMs,
                    forecastExpiresAtMs);
            // NotificationManager.enqueueNotification() is asynchronous:
            // getActiveNotifications() may not include it immediately. Keep
            // legacy enabled until a later main-loop turn confirms that the
            // exact notification is actually active.
            clearRuntimeDeliveryProof();
            preferences.setLegacyPredictionSuppression(false, false);
            scheduleDeliveryConfirmation(direction, decision.direction,
                    expiresAtMs);
        } else {
            // Permission/channel state may change between the earlier
            // postability check and notify(). Never leave either legacy path
            // suppressed after a failed replacement delivery.
            clearRuntimeDeliveryProof();
            clearEpisodeAndNotifications();
        }
    }

    synchronized void onBackendConfigurationChanged() {
        // A warning from the previous server must not remain visible or be
        // replayed after switching configuration.
        lastForecast = null;
        clearRuntimeDeliveryProof();
        preferences.setLegacyPredictionSuppression(false, false);
        PredictiveAlertNotifier.cancelAll(application);
        preferences.clearDeliveryState();
    }

    synchronized void onForecastUnavailable() {
        lastForecast = null;
        clearRuntimeDeliveryProof();
        // Transport/model unavailability fails open for the native legacy
        // path, but it is not proof that an already-delivered risk resolved.
        preferences.setLegacyPredictionSuppression(false, false);
    }

    synchronized void onSettingsChanged() {
        PredictiveAlertPreferences.Snapshot settings = preferences.snapshot();
        updateLegacySuppression(settings, lastForecast,
                System.currentTimeMillis());
        String active = preferences.activeEpisodeDirection();
        boolean activeDirectionDisabled =
                PredictiveAlertPreferences.DIRECTION_LOW.equals(active)
                        ? !settings.lowEnabled
                        : PredictiveAlertPreferences.DIRECTION_HIGH.equals(active)
                        && !settings.highEnabled;
        if (!settings.enabled || activeDirectionDisabled) {
            clearRuntimeDeliveryProof();
            PredictiveAlertNotifier.cancelAll(application);
            preferences.clearEpisode();
            preferences.clearSnooze();
        }
    }

    /**
     * Computes the only state in which the native early-warning path may be
     * handed to predictive delivery. Each direction is independent.
     */
    static LegacySuppression legacySuppressionFor(
            PredictiveAlertPreferences.Snapshot settings,
            ForecastSnapshot forecast, long nowMs,
            boolean localCalibrationActive, boolean lowPostable,
            boolean highPostable) {
        ForecastSnapshot.AlertAssessment assessment = forecast == null
                ? null : forecast.alertAssessment;
        boolean approvedFreshReplacement = settings != null
                && settings.enabled
                && !localCalibrationActive
                && forecast != null
                && "ready".equalsIgnoreCase(forecast.status)
                && forecast.isAlertFresh(nowMs)
                && assessment != null
                && assessment.deliveryEligible
                && "eligible".equals(assessment.monitoringStatus)
                && assessment.suppressedReasons.isEmpty()
                && forecast.basedOnGlucoseMgDl != null
                && Float.isFinite(forecast.basedOnGlucoseMgDl)
                && forecast.basedOnGlucoseMgDl >= assessment.targetLowMgDl
                && forecast.basedOnGlucoseMgDl <= assessment.targetHighMgDl;
        if (!approvedFreshReplacement) return LegacySuppression.NONE;
        return new LegacySuppression(
                settings.lowEnabled && lowPostable,
                settings.highEnabled && highPostable);
    }

    /** Local calibration and native-load uncertainty both fail closed. */
    static boolean localCalibrationActive() {
        if (!Applic.Nativesloaded) return true;
        try {
            // A calibrated stream/scan layer is built independently from the
            // calibration master switch. Both native states use a different
            // display scale than the raw backend assessment.
            return calibrationActive(Natives.getDoCalibrate(),
                    Natives.getPredictiveCalibrationActive());
        } catch (Throwable error) {
            Log.stack("PredictiveAlerts", "localCalibrationActive", error);
            return true;
        }
    }

    static boolean calibrationActive(boolean doCalibrate,
            boolean calibratedDisplayVisible) {
        return doCalibrate || calibratedDisplayVisible;
    }

    private LegacySuppression updateLegacySuppression(
            PredictiveAlertPreferences.Snapshot settings,
            ForecastSnapshot forecast, long nowMs) {
        // Clear first so an exception while probing Android notification state
        // cannot leave a previous forecast handoff latched.
        preferences.setLegacyPredictionSuppression(false, false);
        try {
            boolean lowPostable = settings != null && settings.lowEnabled
                    && PredictiveAlertNotifier.channelEnabled(application,
                    PredictiveAlertNotifier.LOW_CHANNEL_ID);
            boolean highPostable = settings != null && settings.highEnabled
                    && PredictiveAlertNotifier.channelEnabled(application,
                    PredictiveAlertNotifier.HIGH_CHANNEL_ID);
            LegacySuppression result = legacySuppressionFor(settings, forecast,
                    nowMs, localCalibrationActive(), lowPostable, highPostable);
            // Readiness alone must never mask the next native alarm: native
            // evaluates a new CGM value before Java can refresh the forecast.
            // Only an already-delivered warning may own its active direction.
            String deliveredDirection = runtimeDelivery.directionIfActive(
                    nowMs, notificationActive(runtimeDelivery.direction(),
                    nowMs));
            LegacySuppression active = activeHandoff(result,
                    deliveredDirection);
            if (!active.anyReplacementOperational()) runtimeDelivery.clear();
            preferences.setLegacyPredictionSuppression(active.low, active.high,
                    runtimeDelivery.expiresAtMs());
            return result;
        } catch (Throwable error) {
            Log.stack("PredictiveAlerts", "updateLegacySuppression", error);
            return LegacySuppression.NONE;
        }
    }

    static final class LegacySuppression {
        static final LegacySuppression NONE =
                new LegacySuppression(false, false);

        final boolean low;
        final boolean high;

        LegacySuppression(boolean low, boolean high) {
            this.low = low;
            this.high = high;
        }

        boolean anyReplacementOperational() {
            return low || high;
        }
    }

    static LegacySuppression activeHandoff(LegacySuppression operational,
            String deliveredDirection) {
        if (operational == null) return LegacySuppression.NONE;
        return new LegacySuppression(
                operational.low && PredictiveAlertPreferences.DIRECTION_LOW
                        .equals(deliveredDirection),
                operational.high && PredictiveAlertPreferences.DIRECTION_HIGH
                        .equals(deliveredDirection));
    }

    static boolean isEvidenceUpgrade(String activeDirection,
            String activeEvidence, String nextDirection, String nextEvidence) {
        return nextDirection != null && nextDirection.equals(activeDirection)
                && PredictiveAlertPreferences.EVIDENCE_POSSIBLE.equals(
                activeEvidence)
                && PredictiveAlertPreferences.EVIDENCE_LIKELY.equals(
                nextEvidence);
    }

    static boolean isEvidenceDowngrade(String activeDirection,
            String activeEvidence, String nextDirection, String nextEvidence) {
        return nextDirection != null && nextDirection.equals(activeDirection)
                && PredictiveAlertPreferences.EVIDENCE_LIKELY.equals(
                activeEvidence)
                && PredictiveAlertPreferences.EVIDENCE_POSSIBLE.equals(
                nextEvidence);
    }

    static int cooldownMinutesForDirection(
            PredictiveAlertPreferences.Snapshot settings, String direction) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings are required");
        }
        if (PredictiveAlertPreferences.DIRECTION_LOW.equals(direction)) {
            return settings.lowCooldownMinutes;
        }
        if (PredictiveAlertPreferences.DIRECTION_HIGH.equals(direction)) {
            return settings.highCooldownMinutes;
        }
        throw new IllegalArgumentException("Unknown alert direction: "
                + direction);
    }

    private boolean notificationActive(String direction, long nowMs) {
        ForecastRiskEvaluator.Direction value =
                PredictiveAlertPreferences.DIRECTION_LOW.equals(direction)
                        ? ForecastRiskEvaluator.Direction.LOW
                        : PredictiveAlertPreferences.DIRECTION_HIGH.equals(direction)
                        ? ForecastRiskEvaluator.Direction.HIGH : null;
        return value != null && PredictiveAlertNotifier.notificationActive(
                application, value, nowMs);
    }

    private void scheduleDeliveryConfirmation(String direction,
            ForecastRiskEvaluator.Direction notificationDirection,
            long expiresAtMs) {
        final long proofId = ++deliveryProofSequence;
        main.postDelayed(() -> confirmDelivery(proofId, direction,
                notificationDirection, expiresAtMs),
                DELIVERY_CONFIRM_DELAY_MS);
    }

    private synchronized void confirmDelivery(long proofId, String direction,
            ForecastRiskEvaluator.Direction notificationDirection,
            long expiresAtMs) {
        long nowMs = System.currentTimeMillis();
        if (proofId != deliveryProofSequence || expiresAtMs <= nowMs
                || !PredictiveAlertNotifier.notificationActive(application,
                notificationDirection, nowMs)) {
            return;
        }
        runtimeDelivery.record(direction, expiresAtMs);
        // Re-check model freshness, calibration, permission and the exact
        // channel before handing this direction to the confirmed notification.
        updateLegacySuppression(preferences.snapshot(), lastForecast, nowMs);
    }

    private void clearRuntimeDeliveryProof() {
        deliveryProofSequence++;
        runtimeDelivery.clear();
    }

    /** Process-local proof that this process successfully posted a warning. */
    static final class RuntimeDeliveryLatch {
        private String direction = "";
        private long expiresAtMs;

        void record(String deliveredDirection, long deliveredExpiresAtMs) {
            direction = PredictiveAlertPreferences.DIRECTION_LOW.equals(
                    deliveredDirection)
                    || PredictiveAlertPreferences.DIRECTION_HIGH.equals(
                    deliveredDirection) ? deliveredDirection : "";
            expiresAtMs = direction.isEmpty() ? 0L
                    : Math.max(0L, deliveredExpiresAtMs);
        }

        String directionIfActive(long nowMs, boolean notificationActive) {
            if (direction.isEmpty() || expiresAtMs <= nowMs
                    || !notificationActive) {
                clear();
                return "";
            }
            return direction;
        }

        String direction() {
            return direction;
        }

        long expiresAtMs() {
            return expiresAtMs;
        }

        void clear() {
            direction = "";
            expiresAtMs = 0L;
        }
    }

    private boolean canResolveEpisode(ForecastSnapshot forecast, long nowMs) {
        String active = preferences.activeEpisodeDirection();
        if (active == null || active.isEmpty()) return false;
        if (forecast == null || !forecast.isAlertFresh(nowMs)
                || forecast.alertAssessment == null
                || !forecast.alertAssessment.deliveryEligible) {
            return false;
        }
        List<ForecastSnapshot.Point> points = forecast.points;
        if (points.size() < REARM_POINTS) return false;
        float lowSafe = forecast.alertAssessment.targetLowMgDl
                + REARM_HYSTERESIS_MG_DL;
        float highSafe = forecast.alertAssessment.targetHighMgDl
                - REARM_HYSTERESIS_MG_DL;
        for (int index = 0; index < REARM_POINTS; index++) {
            float median = points.get(index).medianMgDl;
            if (PredictiveAlertPreferences.DIRECTION_LOW.equals(active)) {
                if (median <= lowSafe) return false;
            } else if (PredictiveAlertPreferences.DIRECTION_HIGH.equals(active)) {
                if (median >= highSafe) return false;
            } else {
                return true;
            }
        }
        return true;
    }

    private void clearEpisodeAndNotifications() {
        clearRuntimeDeliveryProof();
        preferences.setLegacyPredictionSuppression(false, false);
        PredictiveAlertNotifier.cancelAll(application);
        preferences.clearEpisode();
        preferences.clearSnooze();
    }
}
