package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PredictiveAlertPreferencesTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PredictiveAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void defaultsAreSafeAndUsefulWithoutEnablingNotifications() {
        PredictiveAlertPreferences.Snapshot snapshot =
                new PredictiveAlertPreferences(context).snapshot();

        assertFalse(snapshot.enabled);
        assertTrue(snapshot.lowEnabled);
        assertTrue(snapshot.highEnabled);
        assertEquals(20, snapshot.lowHorizonMinutes);
        assertEquals(30, snapshot.highHorizonMinutes);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                snapshot.sensitivity);
        assertEquals(60, snapshot.cooldownMinutes);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                snapshot.lowSensitivity);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                snapshot.highSensitivity);
        assertEquals(60, snapshot.lowCooldownMinutes);
        assertEquals(60, snapshot.highCooldownMinutes);
        assertEquals(75.6f, PredictiveAlertPreferences.TARGET_LOW_MG_DL, .001f);
        assertEquals(162.0f, PredictiveAlertPreferences.TARGET_HIGH_MG_DL, .001f);
        assertEquals(4.2f, PredictiveAlertPreferences.TARGET_LOW_MMOL_L, 0f);
        assertEquals(9.0f, PredictiveAlertPreferences.TARGET_HIGH_MMOL_L, 0f);
    }

    @Test
    @Config(sdk = 25, application = Application.class)
    public void preODeviceCannotEnableAlertsThatCannotSafelyExpire() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);

        preferences.setEnabled(true);

        assertFalse(PredictiveAlertNotifier.supportsExpiringAlerts());
        assertFalse(preferences.snapshot().enabled);
    }

    @Test
    public void configurationIsClampedToSupportedClinicalChoices() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);
        preferences.setEnabled(true);
        preferences.setLowEnabled(false);
        preferences.setHighEnabled(false);
        preferences.setLowHorizonMinutes(45);
        preferences.setHighHorizonMinutes(15);
        preferences.setSensitivity(PredictiveAlertPreferences.SENSITIVITY_EARLY);
        preferences.setCooldownMinutes(120);

        PredictiveAlertPreferences.Snapshot selected = preferences.snapshot();
        assertTrue(selected.enabled);
        assertFalse(selected.lowEnabled);
        assertFalse(selected.highEnabled);
        assertEquals(45, selected.lowHorizonMinutes);
        assertEquals(15, selected.highHorizonMinutes);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_EARLY,
                selected.sensitivity);
        assertEquals(120, selected.cooldownMinutes);

        preferences.setLowHorizonMinutes(19);
        preferences.setHighHorizonMinutes(90);
        preferences.setSensitivity(99);
        preferences.setCooldownMinutes(1);
        PredictiveAlertPreferences.Snapshot clamped = preferences.snapshot();
        assertEquals(20, clamped.lowHorizonMinutes);
        assertEquals(30, clamped.highHorizonMinutes);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                clamped.sensitivity);
        assertEquals(60, clamped.cooldownMinutes);
    }

    @Test
    public void sharedPolicyMigratesToBothDirections() {
        context.getSharedPreferences(PredictiveAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit()
                .putInt("sensitivity",
                        PredictiveAlertPreferences.SENSITIVITY_EARLY)
                .putInt("cooldown_minutes", 120)
                .commit();

        PredictiveAlertPreferences.Snapshot migrated =
                new PredictiveAlertPreferences(context).snapshot();

        assertEquals(PredictiveAlertPreferences.SENSITIVITY_EARLY,
                migrated.lowSensitivity);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_EARLY,
                migrated.highSensitivity);
        assertEquals(120, migrated.lowCooldownMinutes);
        assertEquals(120, migrated.highCooldownMinutes);
    }

    @Test
    public void directionPoliciesAreIndependentAndValidated() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);

        preferences.setLowSensitivity(
                PredictiveAlertPreferences.SENSITIVITY_EARLY);
        preferences.setHighSensitivity(
                PredictiveAlertPreferences.SENSITIVITY_FEWER);
        preferences.setLowCooldownMinutes(15);
        preferences.setHighCooldownMinutes(120);

        PredictiveAlertPreferences.Snapshot selected = preferences.snapshot();
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_EARLY,
                selected.lowSensitivity);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_FEWER,
                selected.highSensitivity);
        assertEquals(15, selected.lowCooldownMinutes);
        assertEquals(120, selected.highCooldownMinutes);
        assertEquals(selected.lowSensitivity, selected.sensitivityFor(true));
        assertEquals(selected.highSensitivity,
                selected.sensitivityFor(false));
        assertEquals(15, selected.cooldownMinutesFor(true));
        assertEquals(120, selected.cooldownMinutesFor(false));

        preferences.setDirectionSensitivity(true, 99);
        preferences.setDirectionCooldownMinutes(false, 1);
        PredictiveAlertPreferences.Snapshot clamped = preferences.snapshot();
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                clamped.lowSensitivity);
        assertEquals(PredictiveAlertPreferences.SENSITIVITY_FEWER,
                clamped.highSensitivity);
        assertEquals(15, clamped.lowCooldownMinutes);
        assertEquals(60, clamped.highCooldownMinutes);
    }

    @Test
    public void directionEnableTurnsMasterOnForFirstAndOffForLast() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);

        preferences.setDirectionEnabled(true, true);
        PredictiveAlertPreferences.Snapshot lowOnly = preferences.snapshot();
        assertTrue(lowOnly.enabled);
        assertTrue(lowOnly.lowEnabled);
        assertFalse(lowOnly.highEnabled);

        preferences.setDirectionEnabled(false, true);
        preferences.setDirectionEnabled(true, false);
        PredictiveAlertPreferences.Snapshot highOnly = preferences.snapshot();
        assertTrue(highOnly.enabled);
        assertFalse(highOnly.lowEnabled);
        assertTrue(highOnly.highEnabled);

        preferences.setDirectionEnabled(false, false);
        PredictiveAlertPreferences.Snapshot none = preferences.snapshot();
        assertFalse(none.enabled);
        assertFalse(none.lowEnabled);
        assertFalse(none.highEnabled);
    }

    @Test
    public void firstDirectionEnableDoesNotOptInLegacyDefaultDirection() {
        context.getSharedPreferences(PredictiveAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false)
                .commit();
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);

        preferences.setDirectionEnabled(false, true);

        PredictiveAlertPreferences.Snapshot highOnly = preferences.snapshot();
        assertTrue(highOnly.enabled);
        assertFalse(highOnly.lowEnabled);
        assertTrue(highOnly.highEnabled);
    }

    @Test
    public void existingEnabledMasterPreservesOtherLegacyDefaultDirection() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);
        preferences.setEnabled(true);

        preferences.setDirectionEnabled(true, true);

        PredictiveAlertPreferences.Snapshot both = preferences.snapshot();
        assertTrue(both.enabled);
        assertTrue(both.lowEnabled);
        assertTrue(both.highEnabled);
    }

    @Test
    public void explicitlyEnabledOtherDirectionSurvivesMasterReenable() {
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);
        preferences.setHighEnabled(true);
        preferences.setEnabled(false);

        preferences.setDirectionEnabled(true, true);

        PredictiveAlertPreferences.Snapshot both = preferences.snapshot();
        assertTrue(both.enabled);
        assertTrue(both.lowEnabled);
        assertTrue(both.highEnabled);
    }

    @Test
    public void coordinatorLifecycleSurvivesProcessRecreationAndDisableClearsEpisode() {
        PredictiveAlertPreferences first = new PredictiveAlertPreferences(context);
        first.recordAlert("LOW", 1_900_000_000_000L,
                1_899_999_700_000L,
                PredictiveAlertPreferences.EVIDENCE_POSSIBLE);
        first.setSnoozeUntil(1_900_000_900_000L);

        PredictiveAlertPreferences restored =
                new PredictiveAlertPreferences(context);
        assertEquals(1_900_000_000_000L, restored.lastAlertAt("low"));
        assertEquals("low", restored.activeEpisodeDirection());
        assertEquals(1_899_999_700_000L, restored.activeAnchorMs());
        assertEquals(PredictiveAlertPreferences.EVIDENCE_POSSIBLE,
                restored.activeEvidence());
        assertEquals(1_900_000_900_000L, restored.snoozeUntil());

        restored.setEnabled(false);
        assertEquals("", restored.activeEpisodeDirection());
        assertEquals(0L, restored.activeAnchorMs());
        assertEquals("", restored.activeEvidence());
        assertEquals(0L, restored.snoozeUntil());
        assertEquals(1_900_000_000_000L, restored.lastAlertAt("low"));

        restored.clearDeliveryState();
        assertEquals(0L, restored.lastAlertAt("low"));
        assertEquals("", restored.activeEpisodeDirection());
        assertEquals(0L, restored.snoozeUntil());
    }

    @Test
    public void validatedDirectionalSnoozeAllowsOneReplayAfterExpiry() {
        long anchor = 1_900_000_000_000L;
        long until = anchor + 10 * 60_000L;
        PredictiveAlertPreferences preferences =
                new PredictiveAlertPreferences(context);
        preferences.recordAlert(PredictiveAlertPreferences.DIRECTION_LOW,
                anchor, anchor,
                PredictiveAlertPreferences.EVIDENCE_LIKELY);

        preferences.snoozePrediction(
                PredictiveAlertPreferences.DIRECTION_LOW, anchor, until);

        assertTrue(preferences.snoozeBlocks(
                PredictiveAlertPreferences.DIRECTION_LOW, until - 1L));
        assertFalse(preferences.snoozeBlocks(
                PredictiveAlertPreferences.DIRECTION_HIGH, until - 1L));
        assertFalse(preferences.snoozeReplayDue(
                PredictiveAlertPreferences.DIRECTION_LOW, anchor, until - 1L));
        assertTrue(preferences.snoozeReplayDue(
                PredictiveAlertPreferences.DIRECTION_LOW, anchor, until));
        assertTrue(preferences.snoozeReplayDue(
                PredictiveAlertPreferences.DIRECTION_LOW,
                anchor + 5 * 60_000L, until));
        assertFalse(preferences.snoozeReplayDue(
                PredictiveAlertPreferences.DIRECTION_HIGH, anchor, until));

        preferences.recordAlert(PredictiveAlertPreferences.DIRECTION_HIGH,
                anchor + 1L, anchor + 1L,
                PredictiveAlertPreferences.EVIDENCE_LIKELY);
        assertTrue(preferences.snoozeBlocks(
                PredictiveAlertPreferences.DIRECTION_LOW, until - 1L));

        preferences.recordAlert(PredictiveAlertPreferences.DIRECTION_LOW,
                until, anchor, PredictiveAlertPreferences.EVIDENCE_LIKELY);
        assertEquals(0L, preferences.snoozeUntil());
        assertFalse(preferences.snoozeReplayDue(
                PredictiveAlertPreferences.DIRECTION_LOW, anchor, until + 1L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownAlertDirectionCannotCreateAnUnboundedPreferenceKey() {
        new PredictiveAlertPreferences(context).lastAlertAt("sideways");
    }
}
