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
    public void coordinatorLifecycleSurvivesProcessRecreationAndDisableClearsEpisode() {
        PredictiveAlertPreferences first = new PredictiveAlertPreferences(context);
        first.recordAlert("LOW", 1_900_000_000_000L, 1_899_999_700_000L);
        first.setSnoozeUntil(1_900_000_900_000L);

        PredictiveAlertPreferences restored =
                new PredictiveAlertPreferences(context);
        assertEquals(1_900_000_000_000L, restored.lastAlertAt("low"));
        assertEquals("low", restored.activeEpisodeDirection());
        assertEquals(1_899_999_700_000L, restored.activeAnchorMs());
        assertEquals(1_900_000_900_000L, restored.snoozeUntil());

        restored.setEnabled(false);
        assertEquals("", restored.activeEpisodeDirection());
        assertEquals(0L, restored.activeAnchorMs());
        assertEquals(0L, restored.snoozeUntil());
        assertEquals(1_900_000_000_000L, restored.lastAlertAt("low"));

        restored.clearDeliveryState();
        assertEquals(0L, restored.lastAlertAt("low"));
        assertEquals("", restored.activeEpisodeDirection());
        assertEquals(0L, restored.snoozeUntil());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownAlertDirectionCannotCreateAnUnboundedPreferenceKey() {
        new PredictiveAlertPreferences(context).lastAlertAt("sideways");
    }
}
