package tk.glucodata;

import static org.junit.Assert.assertArrayEquals;
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
public class CriticalAlertPreferencesTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CriticalAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void exposesOnlySafeVolumeFloorsAndDefaultsToSeventy() {
        assertArrayEquals(new int[]{70, 85, 100},
                CriticalAlertPreferences.volumeOptions());
        for (CriticalAlarmSoundCatalog.AlertType type
                : CriticalAlarmSoundCatalog.AlertType.values()) {
            assertEquals(70, CriticalAlertPreferences
                    .getMinimumVolumePercent(context, type));
        }
    }

    @Test
    public void eachOfFiveAlertTypesPersistsIndependently() {
        assertEquals(5, CriticalAlarmSoundCatalog.AlertType.values().length);
        assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, 85));
        assertTrue(CriticalAlertPreferences.setMinimumVolumePercent(context,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS, 100));

        assertEquals(85, CriticalAlertPreferences.getMinimumVolumePercent(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        assertEquals(100, CriticalAlertPreferences.getMinimumVolumePercent(
                context, CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS));
        assertEquals(70, CriticalAlertPreferences.getMinimumVolumePercent(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
    }

    @Test
    public void invalidValuesFailClosedAndCorruptionFallsBack() {
        assertFalse(CriticalAlertPreferences.setMinimumVolumePercent(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, 0));
        assertFalse(CriticalAlertPreferences.setMinimumVolumePercent(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, 99));
        assertFalse(CriticalAlertPreferences.setMinimumVolumePercent(context,
                null, 100));

        context.getSharedPreferences(CriticalAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit()
                .putInt("minimum_volume_actual_low", -25).commit();
        assertEquals(70, CriticalAlertPreferences.getMinimumVolumePercent(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
    }
}
