package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PredictiveAlertEngineTest {
    @Test
    public void likelyLowWithinHorizonProducesDecision() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot forecast = forecast(now, 110f, true,
                crossing("low", "likely", now + 20 * 60_000L,
                        20, 73f, 61f), null);

        ForecastRiskEvaluator.Decision result = ForecastRiskEvaluator.evaluate(
                forecast, policy(ForecastRiskEvaluator.SENSITIVITY_BALANCED,
                        20, 30), now);

        assertTrue(result.shouldNotify());
        assertTrue(PredictiveAlertNotifier.usesCriticalDelivery(result));
        assertEquals(ForecastRiskEvaluator.Direction.LOW, result.direction);
        assertEquals(20, result.leadMinutes);
        assertEquals(75.6f, result.targetMgDl, .001f);
    }

    @Test
    public void possibleCrossingNeedsEarlySensitivity() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot forecast = forecast(now, 115f, true,
                crossing("low", "possible", now + 15 * 60_000L,
                        15, 90f, 72f), null);

        assertFalse(ForecastRiskEvaluator.evaluate(forecast,
                policy(ForecastRiskEvaluator.SENSITIVITY_BALANCED, 20, 30),
                now).shouldNotify());
        ForecastRiskEvaluator.Decision early = ForecastRiskEvaluator.evaluate(
                forecast,
                policy(ForecastRiskEvaluator.SENSITIVITY_EARLY, 20, 30),
                now);
        assertTrue(early.shouldNotify());
        assertFalse(PredictiveAlertNotifier.usesCriticalDelivery(early));
    }

    @Test
    public void onlyPossibleToLikelyInSameDirectionBypassesEpisodeCooldown() {
        assertTrue(PredictiveAlertCoordinator.isEvidenceUpgrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY));
        assertFalse(PredictiveAlertCoordinator.isEvidenceUpgrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY));
        assertFalse(PredictiveAlertCoordinator.isEvidenceUpgrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE,
                PredictiveAlertPreferences.DIRECTION_HIGH,
                PredictiveAlertPreferences.EVIDENCE_LIKELY));
        assertFalse(PredictiveAlertCoordinator.isEvidenceUpgrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE));
    }

    @Test
    public void likelyToPossibleDowngradeBlocksOnlyTheSameDirection() {
        assertTrue(PredictiveAlertCoordinator.isEvidenceDowngrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE));
        assertFalse(PredictiveAlertCoordinator.isEvidenceDowngrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY,
                PredictiveAlertPreferences.DIRECTION_HIGH,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE));
        assertFalse(PredictiveAlertCoordinator.isEvidenceDowngrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY));
        assertFalse(PredictiveAlertCoordinator.isEvidenceDowngrade(
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY,
                PredictiveAlertPreferences.DIRECTION_LOW,
                PredictiveAlertPreferences.EVIDENCE_LIKELY));
    }

    @Test
    public void earlyAndBalancedCanChooseDifferentCrossings() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot.ThresholdCrossing possible = crossing(
                "low", "possible", now + 10 * 60_000L,
                10, 90f, 72f);
        ForecastSnapshot.ThresholdCrossing likely = crossing(
                "low", "likely", now + 50 * 60_000L,
                50, 73f, 61f);
        ForecastSnapshot base = forecast(now, 115f, true, likely, null);
        ForecastSnapshot.AlertAssessment detailed =
                new ForecastSnapshot.AlertAssessment(
                        "eligible", true, 75.6f, 162f, 4.2f, 9f,
                        Collections.emptyList(), likely, null,
                        possible, likely, null, null);
        ForecastSnapshot forecast = new ForecastSnapshot("ready", now, now,
                115f, 120, "model", .7f, base.points,
                Collections.emptyList(), "research only", detailed);

        ForecastRiskEvaluator.Decision early = ForecastRiskEvaluator.evaluate(
                forecast, policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                        20, 30), now);
        ForecastRiskEvaluator.Decision balanced =
                ForecastRiskEvaluator.evaluate(forecast,
                        policy(ForecastRiskEvaluator.SENSITIVITY_BALANCED,
                                60, 30), now);

        assertTrue(early.shouldNotify());
        assertEquals("possible", early.evidence);
        assertEquals(10, early.leadMinutes);
        assertTrue(balanced.shouldNotify());
        assertEquals("likely", balanced.evidence);
        assertEquals(50, balanced.leadMinutes);
        assertFalse(ForecastRiskEvaluator.evaluate(forecast,
                policy(ForecastRiskEvaluator.SENSITIVITY_BALANCED, 20, 30),
                now).shouldNotify());
    }

    @Test
    public void shadowAndStaleForecastsFailClosed() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot shadow = forecast(now, 110f, false,
                crossing("low", "likely", now + 15 * 60_000L,
                        15, 72f, 60f), null);
        assertEquals("model_not_alert_approved",
                ForecastRiskEvaluator.evaluate(shadow,
                        policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                                20, 30), now).suppressionReason);

        ForecastSnapshot stale = forecast(
                now - ForecastSnapshot.MAX_ALERT_AGE_MS - 1L,
                110f, true,
                crossing("low", "likely", now + 15 * 60_000L,
                        15, 72f, 60f), null);
        assertEquals("stale_or_missing_forecast",
                ForecastRiskEvaluator.evaluate(stale,
                        policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                                20, 30), now).suppressionReason);
    }

    @Test
    public void legacyHandoffIsDirectionalOperationalAndCalibrationSafe() {
        long now = 1_800_000_000_000L;
        PredictiveAlertPreferences.Snapshot settings =
                new PredictiveAlertPreferences.Snapshot(true, true, true,
                        20, 30,
                        PredictiveAlertPreferences.SENSITIVITY_BALANCED, 60);
        ForecastSnapshot ready = forecast(now, 110f, true,
                crossing("low", "likely", now + 15 * 60_000L,
                        15, 72f, 60f),
                crossing("high", "likely", now + 25 * 60_000L,
                        25, 170f, 180f));

        PredictiveAlertCoordinator.LegacySuppression both =
                PredictiveAlertCoordinator.legacySuppressionFor(settings,
                        ready, now, false, true, true);
        assertTrue(both.low);
        assertTrue(both.high);
        PredictiveAlertCoordinator.LegacySuppression beforeDelivery =
                PredictiveAlertCoordinator.activeHandoff(both, "");
        assertFalse(beforeDelivery.anyReplacementOperational());
        PredictiveAlertCoordinator.LegacySuppression deliveredLow =
                PredictiveAlertCoordinator.activeHandoff(both,
                        PredictiveAlertPreferences.DIRECTION_LOW);
        assertTrue(deliveredLow.low);
        assertFalse(deliveredLow.high);
        PredictiveAlertCoordinator.LegacySuppression deliveredHigh =
                PredictiveAlertCoordinator.activeHandoff(both,
                        PredictiveAlertPreferences.DIRECTION_HIGH);
        assertFalse(deliveredHigh.low);
        assertTrue(deliveredHigh.high);

        PredictiveAlertCoordinator.LegacySuppression highBlocked =
                PredictiveAlertCoordinator.legacySuppressionFor(settings,
                        ready, now, false, true, false);
        assertTrue(highBlocked.low);
        assertFalse(highBlocked.high);

        PredictiveAlertPreferences.Snapshot lowDisabled =
                new PredictiveAlertPreferences.Snapshot(true, false, true,
                        20, 30,
                        PredictiveAlertPreferences.SENSITIVITY_BALANCED, 60);
        PredictiveAlertCoordinator.LegacySuppression highOnly =
                PredictiveAlertCoordinator.legacySuppressionFor(lowDisabled,
                        ready, now, false, true, true);
        assertFalse(highOnly.low);
        assertTrue(highOnly.high);

        PredictiveAlertCoordinator.LegacySuppression calibrated =
                PredictiveAlertCoordinator.legacySuppressionFor(settings,
                        ready, now, true, true, true);
        assertFalse(calibrated.anyReplacementOperational());

        ForecastSnapshot coldStart = new ForecastSnapshot("cold_start",
                ready.generatedAtMs, ready.basedOnReadingAtMs,
                ready.basedOnGlucoseMgDl, ready.horizonMinutes,
                ready.modelVersion, ready.confidence, ready.points,
                ready.activities, ready.conditionalNotice,
                ready.alertAssessment);
        assertFalse(PredictiveAlertCoordinator.legacySuppressionFor(settings,
                coldStart, now, false, true, true)
                .anyReplacementOperational());
        assertFalse(PredictiveAlertCoordinator.legacySuppressionFor(settings,
                forecast(now, 110f, false, null, null), now,
                false, true, true).anyReplacementOperational());
        assertFalse(PredictiveAlertCoordinator.legacySuppressionFor(settings,
                ready, now + ForecastSnapshot.MAX_ALERT_AGE_MS + 1L,
                false, true, true).anyReplacementOperational());
        assertFalse(PredictiveAlertCoordinator.legacySuppressionFor(settings,
                forecast(now, 70f, true, null, null), now,
                false, true, true).anyReplacementOperational());
        assertFalse(PredictiveAlertCoordinator.legacySuppressionFor(settings,
                forecast(now, Float.NaN, true, null, null), now,
                false, true, true).anyReplacementOperational());
    }

    @Test
    public void calibrationGateFailsClosedForMasterOrCalibratedDisplay() {
        assertFalse(PredictiveAlertCoordinator.calibrationActive(false, false));
        assertTrue(PredictiveAlertCoordinator.calibrationActive(true, false));
        assertTrue(PredictiveAlertCoordinator.calibrationActive(false, true));
        assertTrue(PredictiveAlertCoordinator.calibrationActive(true, true));
    }

    @Test
    public void runtimeDeliveryProofNeverComesFromPersistedEpisode() {
        Application application = RuntimeEnvironment.getApplication();
        PredictiveAlertPreferences stored =
                new PredictiveAlertPreferences(application);
        long now = 1_800_000_000_000L;
        stored.recordAlert(PredictiveAlertPreferences.DIRECTION_LOW,
                now, now);

        PredictiveAlertCoordinator.RuntimeDeliveryLatch recreated =
                new PredictiveAlertCoordinator.RuntimeDeliveryLatch();

        assertEquals(PredictiveAlertPreferences.DIRECTION_LOW,
                stored.activeEpisodeDirection());
        assertEquals("", recreated.directionIfActive(now, true));
        assertEquals(now, stored.lastAlertAt(
                PredictiveAlertPreferences.DIRECTION_LOW));

        recreated.record(PredictiveAlertPreferences.DIRECTION_LOW,
                now + 60_000L);
        assertEquals(PredictiveAlertPreferences.DIRECTION_LOW,
                recreated.directionIfActive(now, true));
        assertEquals("", recreated.directionIfActive(now + 60_001L, true));
        stored.clearDeliveryState();
    }

    @Test
    public void removedNotificationClearsProcessLocalDeliveryProof() {
        long now = 1_800_000_000_000L;
        PredictiveAlertCoordinator.RuntimeDeliveryLatch latch =
                new PredictiveAlertCoordinator.RuntimeDeliveryLatch();
        latch.record(PredictiveAlertPreferences.DIRECTION_HIGH,
                now + 60_000L);

        assertEquals("", latch.directionIfActive(now, false));
        assertEquals(0L, latch.expiresAtMs());
    }

    @Test
    public void authoritativeNoDataAndStaleResponsesInvalidate() {
        long now = 1_800_000_000_000L;
        ForecastRepository.CurrentForecastGate gate =
                new ForecastRepository.CurrentForecastGate();
        ForecastSnapshot ready = forecast(now, 110f, true, null, null);

        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(1L, ready, now).outcome);
        assertFalse(gate.transportErrorInvalidates(now));
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.INVALIDATE,
                gate.accept(2L, ForecastSnapshot.empty("no_data"), now)
                        .outcome);
        assertTrue(gate.transportErrorInvalidates(now));

        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(3L, ready, now).outcome);
        ForecastSnapshot stale = new ForecastSnapshot("stale", now + 1L,
                now, 110f, ready.horizonMinutes, ready.modelVersion,
                ready.confidence, ready.points, ready.activities,
                ready.conditionalNotice, ready.alertAssessment);
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.INVALIDATE,
                gate.accept(4L, stale, now).outcome);
    }

    @Test
    public void latestSuccessfulRollbackInvalidatesAndStartsNewAnchorEpoch() {
        long now = 1_800_000_000_000L;
        ForecastRepository.CurrentForecastGate gate =
                new ForecastRepository.CurrentForecastGate();
        ForecastSnapshot newer = forecast(now, 110f, true, null, null);
        ForecastSnapshot rolledBack = forecast(now - 30 * 60_000L,
                112f, true, null, null);

        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(10L, newer, now).outcome);
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.INVALIDATE,
                gate.accept(12L, rolledBack,
                        now - 30 * 60_000L).outcome);
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.IGNORED,
                gate.accept(11L, newer, now).outcome);
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(13L, rolledBack,
                        now - 30 * 60_000L).outcome);
    }

    @Test
    public void unresolvedSensorReadingBlocksPreBarrierResurrection() {
        long now = 1_800_000_000_000L;
        ForecastRepository.CurrentForecastGate gate =
                new ForecastRepository.CurrentForecastGate();
        ForecastSnapshot before = forecast(now, 110f, true, null, null);

        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(1L, before, now).outcome);
        gate.markUnresolved(now + 5 * 60_000L);
        assertTrue(gate.transportErrorInvalidates(now));
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.INVALIDATE,
                gate.accept(2L, before, now).outcome);

        ForecastSnapshot caughtUp = forecast(now + 5 * 60_000L,
                111f, true, null, null);
        assertEquals(ForecastRepository.CurrentForecastGate.Outcome.PUBLISH,
                gate.accept(3L, caughtUp, now + 5 * 60_000L).outcome);
        assertFalse(gate.transportErrorInvalidates(now + 5 * 60_000L));
        assertTrue(gate.transportErrorInvalidates(now + 5 * 60_000L
                + ForecastSnapshot.MAX_ALERT_AGE_MS + 1L));
    }

    @Test
    public void eligibleAssessmentStillRequiresReadyForecastStatus() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot ready = forecast(now, 110f, true,
                crossing("low", "likely", now + 15 * 60_000L,
                        15, 72f, 60f), null);
        ForecastSnapshot warming = new ForecastSnapshot("cold_start",
                ready.generatedAtMs, ready.basedOnReadingAtMs,
                ready.basedOnGlucoseMgDl, ready.horizonMinutes,
                ready.modelVersion, ready.confidence, ready.points,
                ready.activities, ready.conditionalNotice,
                ready.alertAssessment);

        ForecastRiskEvaluator.Decision decision =
                ForecastRiskEvaluator.evaluate(warming,
                        policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                                20, 30), now);

        assertFalse(decision.shouldNotify());
        assertEquals("model_not_ready", decision.suppressionReason);
    }

    @Test
    public void possibleCrossingUsesUncertaintyEdgeCopy() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot forecast = forecast(now, 110f, true,
                crossing("low", "possible", now + 15 * 60_000L,
                        15, 90f, 72f), null);

        ForecastRiskEvaluator.Decision decision =
                ForecastRiskEvaluator.evaluate(forecast,
                        policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                                20, 30), now);

        assertTrue(decision.shouldNotify());
        assertEquals(72f, decision.intervalEdgeMgDl, .001f);
        assertEquals(R.string.predictive_alert_body_possible_low,
                PredictiveAlertNotifier.bodyResource(decision));
        Application application = RuntimeEnvironment.getApplication();
        assertTrue(application.getString(
                R.string.predictive_alert_body_possible_low)
                .toLowerCase(java.util.Locale.ROOT)
                .contains("uncertainty"));
    }

    @Test
    public void leadTimeIsRecomputedFromNowAndPastCrossingsAreIgnored() {
        long now = 1_800_000_000_000L;
        long anchor = now - 7 * 60_000L;
        ForecastSnapshot forecast = forecast(anchor, 110f, true,
                crossing("low", "likely", anchor + 20 * 60_000L,
                        20, 73f, 61f), null);

        ForecastRiskEvaluator.Decision result = ForecastRiskEvaluator.evaluate(
                forecast, policy(ForecastRiskEvaluator.SENSITIVITY_BALANCED,
                        20, 30), now);

        assertTrue(result.shouldNotify());
        assertEquals(13, result.leadMinutes);

        ForecastSnapshot elapsed = forecast(anchor, 110f, true,
                crossing("low", "likely", anchor + 5 * 60_000L,
                        5, 73f, 61f), null);
        assertFalse(ForecastRiskEvaluator.evaluate(elapsed,
                policy(ForecastRiskEvaluator.SENSITIVITY_EARLY, 20, 30),
                now).shouldNotify());
    }

    @Test
    public void currentOutsideTargetIsLeftToNativeAlarm() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot forecast = forecast(now, 70f, true,
                crossing("low", "likely", now + 10 * 60_000L,
                        10, 65f, 54f), null);

        ForecastRiskEvaluator.Decision result = ForecastRiskEvaluator.evaluate(
                forecast, policy(ForecastRiskEvaluator.SENSITIVITY_EARLY,
                        20, 30), now);

        assertFalse(result.shouldNotify());
        assertEquals("current_outside_personal_target",
                result.suppressionReason);
    }

    @Test
    public void alertAssessmentParsesFromBackendWithoutChangingGraphContract()
            throws Exception {
        long anchor = 1_800_000_000_000L;
        JSONObject payload = new JSONObject()
                .put("status", "ready")
                .put("generated_at_ms", anchor)
                .put("based_on_reading_at_ms", anchor)
                .put("based_on_glucose_mg_dl", 108.5)
                .put("confidence", .7)
                .put("points", new org.json.JSONArray()
                        .put(new JSONObject().put("at_ms", anchor + 300_000L)
                                .put("median_mg_dl", 100).put("low_mg_dl", 90)
                                .put("high_mg_dl", 110))
                        .put(new JSONObject().put("at_ms", anchor + 600_000L)
                                .put("median_mg_dl", 92).put("low_mg_dl", 78)
                                .put("high_mg_dl", 106)))
                .put("alert_assessment", new JSONObject()
                        .put("monitoring_status", "eligible")
                        .put("delivery_eligible", true)
                        .put("target_low_mg_dl", 75.6)
                        .put("target_high_mg_dl", 162.0)
                        .put("target_low_mmol_l", 4.2)
                        .put("target_high_mmol_l", 9.0)
                        .put("suppressed_reasons", new org.json.JSONArray())
                        .put("low_possible", crossingJson("low", "possible",
                                anchor + 600_000L, 10, 88, 72))
                        .put("low_likely", crossingJson("low", "likely",
                                anchor + 1_200_000L, 20, 73, 61))
                        .put("low", crossingJson("low", "likely",
                                anchor + 1_200_000L, 20, 73, 61)));

        ForecastSnapshot parsed = ForecastSnapshot.fromJson(payload);

        assertEquals(108.5f, parsed.basedOnGlucoseMgDl, .001f);
        assertEquals("eligible", parsed.alertAssessment.monitoringStatus);
        assertTrue(parsed.alertAssessment.deliveryEligible);
        assertNotNull(parsed.alertAssessment.low);
        assertEquals(20, parsed.alertAssessment.low.leadMinutes);
        assertEquals(10, parsed.alertAssessment.lowPossible.leadMinutes);
        assertEquals(20, parsed.alertAssessment.lowLikely.leadMinutes);
    }

    @Test
    public void oldBackendPayloadDefaultsToUnavailableAlertAssessment()
            throws Exception {
        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "no_data"));

        assertNotNull(parsed.alertAssessment);
        assertEquals("unavailable", parsed.alertAssessment.monitoringStatus);
        assertFalse(parsed.alertAssessment.deliveryEligible);
    }

    @Test
    public void mismatchedTargetContractFailsClosed() throws Exception {
        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "ready")
                .put("alert_assessment", new JSONObject()
                        .put("monitoring_status", "eligible")
                        .put("delivery_eligible", true)
                        .put("target_low_mg_dl", 70)
                        .put("target_high_mg_dl", 180)));

        assertEquals("unavailable", parsed.alertAssessment.monitoringStatus);
        assertFalse(parsed.alertAssessment.deliveryEligible);
        assertEquals("target_contract_mismatch",
                parsed.alertAssessment.suppressedReasons.get(0));
    }

    @Test
    public void eligibleAssessmentMustDeclareEveryFixedTargetField()
            throws Exception {
        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "ready")
                .put("alert_assessment", new JSONObject()
                        .put("monitoring_status", "eligible")
                        .put("delivery_eligible", true)
                        .put("target_low_mg_dl", 75.6)
                        .put("target_high_mg_dl", 162.0)
                        .put("target_low_mmol_l", 4.2)));

        assertEquals("unavailable", parsed.alertAssessment.monitoringStatus);
        assertFalse(parsed.alertAssessment.deliveryEligible);
        assertEquals("target_contract_mismatch",
                parsed.alertAssessment.suppressedReasons.get(0));
    }

    @Test
    public void deliveryEligibilityMustMatchMonitoringStatus()
            throws Exception {
        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "ready")
                .put("alert_assessment", new JSONObject()
                        .put("monitoring_status", "shadow")
                        .put("delivery_eligible", true)
                        .put("target_low_mg_dl", 75.6)
                        .put("target_high_mg_dl", 162.0)
                        .put("target_low_mmol_l", 4.2)
                        .put("target_high_mmol_l", 9.0)));

        assertEquals("unavailable", parsed.alertAssessment.monitoringStatus);
        assertFalse(parsed.alertAssessment.deliveryEligible);
        assertEquals("delivery_contract_mismatch",
                parsed.alertAssessment.suppressedReasons.get(0));
    }

    @Test
    public void v2PredictiveChannelsAreSharedAlarmUsageChannels() {
        Application application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        PredictiveAlertNotifier.ensureChannels(application);

        NotificationManager manager = application.getSystemService(
                NotificationManager.class);
        assertNotNull(manager);
        NotificationChannel low = manager.getNotificationChannel(
                PredictiveAlertNotifier.LOW_CHANNEL_ID);
        NotificationChannel high = manager.getNotificationChannel(
                PredictiveAlertNotifier.HIGH_CHANNEL_ID);
        assertNotNull(low);
        assertNotNull(high);
        assertEquals(CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID,
                PredictiveAlertNotifier.LOW_CHANNEL_ID);
        assertEquals(CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID,
                PredictiveAlertNotifier.HIGH_CHANNEL_ID);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, low.getImportance());
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
                high.getImportance());
        assertNotNull(low.getSound());
        assertNotNull(high.getSound());
        assertEquals(AudioAttributes.USAGE_ALARM,
                low.getAudioAttributes().getUsage());
        assertEquals(AudioAttributes.USAGE_ALARM,
                high.getAudioAttributes().getUsage());
        assertTrue(PredictiveAlertNotifier.canPost(application));
        assertTrue(PredictiveAlertNotifier.channelEnabled(application,
                PredictiveAlertNotifier.LOW_CHANNEL_ID));
        assertTrue(PredictiveAlertNotifier.channelEnabled(application,
                PredictiveAlertNotifier.HIGH_CHANNEL_ID));
        assertEquals(PredictiveAlertNotifier.HIGH_CHANNEL_ID,
                PredictiveAlertNotifier.testChannelId(application,
                        false, true));
        assertEquals(PredictiveAlertNotifier.HIGH_CHANNEL_ID,
                PredictiveAlertNotifier.settingsChannelId(application,
                false, true));
    }

    @Test
    public void possibleBuilderIsPrivateWithGenericPublicVersionAndHeadsUp() {
        Application application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        PredictiveAlertNotifier.ensureChannels(application);

        String privateTitle = "Possible low in 15 minutes — 72 mg/dL";
        String privateBody = "Predicted 72 mg/dL; target 76–162 mg/dL";
        Notification notification = PredictiveAlertNotifier.baseBuilder(
                application, PredictiveAlertNotifier.LOW_CHANNEL_ID,
                privateTitle, privateBody, true).build();

        assertNull(notification.fullScreenIntent);
        assertEquals(Notification.CATEGORY_RECOMMENDATION,
                notification.category);
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertEquals(0, notification.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals(privateTitle, notification.extras.getCharSequence(
                Notification.EXTRA_TITLE));
        assertEquals(privateBody, notification.extras.getCharSequence(
                Notification.EXTRA_TEXT));

        Notification publicVersion = notification.publicVersion;
        assertNotNull(publicVersion);
        assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility);
        assertEquals(application.getString(R.string.app_name),
                publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertNull(publicVersion.extras.getCharSequence(
                Notification.EXTRA_BIG_TEXT));
        assertNull(publicVersion.fullScreenIntent);
        assertTrue(publicVersion.actions == null
                || publicVersion.actions.length == 0);

        NotificationChannel channel = application.getSystemService(
                NotificationManager.class).getNotificationChannel(
                notification.getChannelId());
        assertNotNull(channel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
                channel.getImportance());
    }

    private static ForecastRiskEvaluator.Policy policy(int sensitivity,
            int lowHorizon, int highHorizon) {
        return new ForecastRiskEvaluator.Policy(true, true, true,
                lowHorizon, highHorizon, sensitivity);
    }

    private static ForecastSnapshot.ThresholdCrossing crossing(
            String direction, String evidence, long atMs, int lead,
            float median, float edge) {
        return new ForecastSnapshot.ThresholdCrossing(direction, evidence,
                atMs, lead, median, edge);
    }

    private static JSONObject crossingJson(String direction, String evidence,
            long atMs, int lead, float median, float edge) throws Exception {
        return new JSONObject()
                .put("direction", direction)
                .put("evidence", evidence)
                .put("crossing_at_ms", atMs)
                .put("lead_minutes", lead)
                .put("predicted_median_mg_dl", median)
                .put("interval_edge_mg_dl", edge);
    }

    private static ForecastSnapshot forecast(long anchor, float current,
            boolean eligible, ForecastSnapshot.ThresholdCrossing low,
            ForecastSnapshot.ThresholdCrossing high) {
        List<ForecastSnapshot.Point> points = new ArrayList<>();
        for (int minute = 5; minute <= 60; minute += 5) {
            points.add(new ForecastSnapshot.Point(anchor + minute * 60_000L,
                    current, current - 10f, current + 10f));
        }
        ForecastSnapshot.AlertAssessment assessment =
                new ForecastSnapshot.AlertAssessment(
                        eligible ? "eligible" : "shadow", eligible,
                        75.6f, 162f, 4.2f, 9f,
                        Collections.emptyList(), low, high);
        return new ForecastSnapshot("ready", anchor, anchor, current,
                120, "model", .7f, points, Collections.emptyList(),
                "research only", assessment);
    }
}
