package tk.glucodata;

/**
 * Pure policy layer for calibrated forecast threshold crossings.
 *
 * <p>The backend decides whether a model has enough prospective alert evidence.
 * This class only applies the user's local horizon/sensitivity preferences and
 * never converts the forecast into a treatment amount.</p>
 */
final class ForecastRiskEvaluator {
    static final int SENSITIVITY_EARLY = 0;
    static final int SENSITIVITY_BALANCED = 1;
    static final int SENSITIVITY_FEWER = 2;

    enum Direction { NONE, LOW, HIGH }

    static final class Policy {
        final boolean enabled;
        final boolean lowEnabled;
        final boolean highEnabled;
        final int lowHorizonMinutes;
        final int highHorizonMinutes;
        final int lowSensitivity;
        final int highSensitivity;
        /** Compatibility alias for callers that still provide one value. */
        final int sensitivity;

        Policy(boolean enabled, boolean lowEnabled, boolean highEnabled,
                int lowHorizonMinutes, int highHorizonMinutes,
                int sensitivity) {
            this(enabled, lowEnabled, highEnabled, lowHorizonMinutes,
                    highHorizonMinutes, sensitivity, sensitivity);
        }

        Policy(boolean enabled, boolean lowEnabled, boolean highEnabled,
                int lowHorizonMinutes, int highHorizonMinutes,
                int lowSensitivity, int highSensitivity) {
            this.enabled = enabled;
            this.lowEnabled = lowEnabled;
            this.highEnabled = highEnabled;
            this.lowHorizonMinutes = clampHorizon(lowHorizonMinutes);
            this.highHorizonMinutes = clampHorizon(highHorizonMinutes);
            this.lowSensitivity = clampSensitivity(lowSensitivity);
            this.highSensitivity = clampSensitivity(highSensitivity);
            this.sensitivity = this.lowSensitivity;
        }
    }

    static final class Decision {
        final Direction direction;
        final String evidence;
        final long anchorMs;
        final long crossingAtMs;
        final int leadMinutes;
        final float currentMgDl;
        final float predictedMedianMgDl;
        final float intervalEdgeMgDl;
        final float targetMgDl;
        final String suppressionReason;

        private Decision(Direction direction, String evidence, long anchorMs,
                long crossingAtMs, int leadMinutes, float currentMgDl,
                float predictedMedianMgDl, float intervalEdgeMgDl,
                float targetMgDl, String suppressionReason) {
            this.direction = direction;
            this.evidence = evidence == null ? "" : evidence;
            this.anchorMs = anchorMs;
            this.crossingAtMs = crossingAtMs;
            this.leadMinutes = leadMinutes;
            this.currentMgDl = currentMgDl;
            this.predictedMedianMgDl = predictedMedianMgDl;
            this.intervalEdgeMgDl = intervalEdgeMgDl;
            this.targetMgDl = targetMgDl;
            this.suppressionReason = suppressionReason == null
                    ? "" : suppressionReason;
        }

        static Decision none(String reason) {
            return new Decision(Direction.NONE, "", 0L, 0L, 0,
                    0f, 0f, 0f, 0f, reason);
        }

        boolean shouldNotify() {
            return direction != Direction.NONE;
        }
    }

    private ForecastRiskEvaluator() {}

    static Decision evaluate(ForecastSnapshot forecast, Policy policy,
            long nowMs) {
        if (policy == null || !policy.enabled) {
            return Decision.none("disabled");
        }
        if (forecast == null || !forecast.isAlertFresh(nowMs)) {
            return Decision.none("stale_or_missing_forecast");
        }
        // A graph may remain useful while the model is warming up, but live
        // delivery is intentionally stricter: only a completed ready forecast
        // can become a notification.
        if (!"ready".equalsIgnoreCase(forecast.status)) {
            return Decision.none("model_not_ready");
        }
        ForecastSnapshot.AlertAssessment assessment =
                forecast.alertAssessment;
        if (assessment == null || !assessment.deliveryEligible
                || !"eligible".equals(assessment.monitoringStatus)) {
            return Decision.none("model_not_alert_approved");
        }
        float current = forecast.basedOnGlucoseMgDl;
        // Current-threshold alarms remain local and independent. Predictive
        // notifications are only for a currently in-target reading.
        if (current < assessment.targetLowMgDl
                || current > assessment.targetHighMgDl) {
            return Decision.none("current_outside_personal_target");
        }

        ForecastSnapshot.ThresholdCrossing low = policy.lowEnabled
                ? accepted(assessment.lowPossible, assessment.lowLikely,
                        assessment.low, Direction.LOW,
                        policy.lowHorizonMinutes, policy.lowSensitivity,
                        forecast.basedOnReadingAtMs, nowMs) : null;
        ForecastSnapshot.ThresholdCrossing high = policy.highEnabled
                ? accepted(assessment.highPossible, assessment.highLikely,
                        assessment.high, Direction.HIGH,
                        policy.highHorizonMinutes, policy.highSensitivity,
                        forecast.basedOnReadingAtMs, nowMs) : null;
        if (low == null && high == null) {
            return Decision.none("no_crossing_in_selected_horizon");
        }

        ForecastSnapshot.ThresholdCrossing selected;
        Direction direction;
        if (prefer(low, high)) {
            selected = low;
            direction = Direction.LOW;
        } else {
            selected = high;
            direction = Direction.HIGH;
        }
        float target = direction == Direction.LOW
                ? assessment.targetLowMgDl : assessment.targetHighMgDl;
        return new Decision(direction, selected.evidence,
                forecast.basedOnReadingAtMs, selected.crossingAtMs,
                remainingLeadMinutes(selected, nowMs), current,
                selected.predictedMedianMgDl, selected.intervalEdgeMgDl,
                target, "");
    }

    private static ForecastSnapshot.ThresholdCrossing accepted(
            ForecastSnapshot.ThresholdCrossing possible,
            ForecastSnapshot.ThresholdCrossing likely,
            ForecastSnapshot.ThresholdCrossing legacy, Direction direction,
            int horizon, int sensitivity, long anchorMs, long nowMs) {
        possible = valid(possible, direction, "possible", horizon, anchorMs,
                nowMs);
        likely = valid(likely, direction, "likely", horizon, anchorMs, nowMs);
        if (possible == null && likely == null && legacy != null) {
            if ("possible".equals(legacy.evidence)) {
                possible = valid(legacy, direction, "possible", horizon,
                        anchorMs, nowMs);
            } else if ("likely".equals(legacy.evidence)) {
                likely = valid(legacy, direction, "likely", horizon,
                        anchorMs, nowMs);
            }
        }
        if (sensitivity != SENSITIVITY_EARLY) {
            if (likely == null || (sensitivity == SENSITIVITY_FEWER
                    && remainingLeadMinutes(likely, nowMs) > 30)) return null;
            return likely;
        }
        if (possible == null) return likely;
        if (likely == null) return possible;
        if (likely.crossingAtMs <= possible.crossingAtMs) return likely;
        return possible;
    }

    private static ForecastSnapshot.ThresholdCrossing valid(
            ForecastSnapshot.ThresholdCrossing crossing, Direction direction,
            String evidence, int horizon, long anchorMs, long nowMs) {
        int remainingLead = remainingLeadMinutes(crossing, nowMs);
        if (crossing == null || !evidence.equals(crossing.evidence)
                || !direction.name().equalsIgnoreCase(crossing.direction)
                || crossing.leadMinutes <= 0
                || remainingLead <= 0 || remainingLead > horizon
                || crossing.crossingAtMs != anchorMs
                + crossing.leadMinutes * 60_000L) return null;
        return crossing;
    }

    private static boolean prefer(ForecastSnapshot.ThresholdCrossing low,
            ForecastSnapshot.ThresholdCrossing high) {
        if (low == null) return false;
        if (high == null) return true;
        if (low.crossingAtMs != high.crossingAtMs) {
            return low.crossingAtMs < high.crossingAtMs;
        }
        boolean lowLikely = "likely".equals(low.evidence);
        boolean highLikely = "likely".equals(high.evidence);
        if (lowLikely != highLikely) return lowLikely;
        // When an extremely wide band points both ways, low takes priority.
        return true;
    }

    private static int clampHorizon(int value) {
        return Math.max(15, Math.min(60, value));
    }

    private static int clampSensitivity(int value) {
        return Math.max(SENSITIVITY_EARLY,
                Math.min(SENSITIVITY_FEWER, value));
    }

    private static int remainingLeadMinutes(
            ForecastSnapshot.ThresholdCrossing crossing, long nowMs) {
        if (crossing == null || crossing.crossingAtMs <= nowMs) return 0;
        long remainingMs = crossing.crossingAtMs - nowMs;
        return (int) Math.min(Integer.MAX_VALUE,
                (remainingMs + 59_999L) / 60_000L);
    }
}
