package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmSoundCatalogTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CriticalAlarmSoundCatalog.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void catalogContainsThirtySixStableCategorizedChoices() {
        assertEquals(36, CriticalAlarmSoundCatalog.tones().size());
        Set<String> ids = new HashSet<>();
        Set<Integer> resources = new HashSet<>();
        Set<CriticalAlarmSoundCatalog.Category> categories = new HashSet<>();
        for (CriticalAlarmSoundCatalog.Tone tone
                : CriticalAlarmSoundCatalog.tones()) {
            assertTrue(ids.add(tone.id));
            assertTrue(resources.add(tone.soundRes));
            assertTrue(tone.soundRes != 0);
            assertNotNull(tone.category);
            categories.add(tone.category);
            assertTrue(tone.durationMs >= 3_000L);
            assertTrue(tone.durationMs <= 16_000L);
            assertFalse(context.getString(tone.labelRes).isEmpty());
            assertEquals(tone.soundRes,
                    CriticalAlarmSoundCatalog.soundRes(tone.id));
            assertEquals(tone.durationMs,
                    CriticalAlarmSoundCatalog.durationMs(tone.soundRes));
        }
        assertEquals(CriticalAlarmSoundCatalog.Category.values().length,
                categories.size());
        assertEquals(0, CriticalAlarmSoundCatalog.soundRes("not_in_catalog"));
        assertEquals(8_500L, CriticalAlarmSoundCatalog.durationMs(0));
    }

    @Test
    public void historicPerAlertDefaultsRemainUnchanged() {
        assertEquals(R.raw.verylow, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        assertEquals(R.raw.veryhigh, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
        assertEquals(R.raw.lowsoon, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW));
        assertEquals(R.raw.highsoon, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH));
        assertEquals(R.raw.siren, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS));
        assertEquals(R.raw.lowsoon, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, "test", "low"));
    }

    @Test
    public void eachAlertTypePersistsItsOwnSelection() {
        assertTrue(CriticalAlarmSoundCatalog.select(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, "siren"));
        assertTrue(CriticalAlarmSoundCatalog.select(context,
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH,
                "sonar_ping"));

        assertEquals("siren", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        assertEquals(R.raw.siren, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, "actual", "low"));
        assertEquals("sonar_ping", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH));
        assertEquals("very_high", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH));
        assertTrue(CriticalAlarmSoundCatalog.select(context,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS, "ghost"));
        assertEquals("ghost", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS));
    }

    @Test
    public void invalidOrCorruptSelectionFallsBackWithoutOverwritingChoice() {
        assertFalse(CriticalAlarmSoundCatalog.select(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW, "unknown"));
        assertEquals("very_low", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));

        context.getSharedPreferences(CriticalAlarmSoundCatalog.PREFS_NAME,
                Context.MODE_PRIVATE).edit()
                .putString("actual_low", "removed_in_future")
                .commit();
        assertEquals("very_low", CriticalAlarmSoundCatalog.selectedToneId(context,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
        assertEquals(R.raw.verylow, CriticalAlarmSoundCatalog.selectedSoundRes(
                context, CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW));
    }
}
