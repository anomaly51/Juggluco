package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GlucoseAlertSettingsPageContractTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
    }

    @Test
    public void hubHasExactlyFiveEqualAlertDestinations() {
        assertEquals(5, GlucoseAlertSettingsPage.HUB_ALERT_TYPES.length);
        assertEquals(GlucoseAlertSettingsPage.Kind.CURRENT_LOW,
                GlucoseAlertSettingsPage.HUB_ALERT_TYPES[0]);
        assertEquals(GlucoseAlertSettingsPage.Kind.CURRENT_HIGH,
                GlucoseAlertSettingsPage.HUB_ALERT_TYPES[1]);
        assertEquals(GlucoseAlertSettingsPage.Kind.FORECAST_LOW,
                GlucoseAlertSettingsPage.HUB_ALERT_TYPES[2]);
        assertEquals(GlucoseAlertSettingsPage.Kind.FORECAST_HIGH,
                GlucoseAlertSettingsPage.HUB_ALERT_TYPES[3]);
        assertEquals(GlucoseAlertSettingsPage.Kind.SIGNAL_LOSS,
                GlucoseAlertSettingsPage.HUB_ALERT_TYPES[4]);
        assertTrue(GlucoseAlertSettingsPage.MIN_TOUCH_TARGET_DP >= 48);
    }

    @Test
    public void phoneHubDropsProfilesValueNotificationsAndLegacyModes()
            throws Exception {
        String settings = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Settings.java"));
        String clinical = between(settings,
                "private static void clinicalAlarmSettings",
                "static private void alarmsettings");
        assertTrue(clinical.contains("GlucoseAlertSettingsPage.show"));
        assertTrue(clinical.contains("Natives.setalarms"));
        assertTrue(clinical.contains("false"));
        assertFalse(clinical.contains("getProfileSpinner"));
        assertFalse(clinical.contains("hasvaluealarm"));
        assertFalse(clinical.contains("valueavailablenotification"));
        assertFalse(clinical.contains("advancedalarm"));
        assertFalse(clinical.contains("getalarmSoundType"));
        assertFalse(clinical.contains("setalarmSoundType"));
        assertFalse(clinical.contains("PredictiveAlertSettingsPage"));
    }

    @Test
    public void everyDetailUsesOneTypedSoundVolumeAndFullScreenTestFlow()
            throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "GlucoseAlertSettingsPage.java"));
        assertTrue(page.contains("private static final class DetailPage"));
        assertTrue(page.contains("CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS"));
        assertTrue(page.contains("CriticalAlarmSoundCatalog.selectedToneId"));
        assertTrue(page.contains("CriticalGlucoseAlarm.previewSound"));
        assertTrue(page.contains("CriticalAlertPreferences.volumeOptions()"));
        assertTrue(page.contains("getMinimumVolumePercent("));
        assertTrue(page.contains("setMinimumVolumePercent("));
        assertTrue(page.contains("CriticalGlucoseAlarm.showTest(\n                        activity, kind.soundType)"));
        assertTrue(page.contains("setDirectionEnabled(low, enabled.isChecked())"));
        assertTrue(page.contains("setLowSensitivity"));
        assertTrue(page.contains("setHighSensitivity"));
        assertTrue(page.contains("setLowCooldownMinutes"));
        assertTrue(page.contains("setHighCooldownMinutes"));
        assertTrue(page.contains("Natives.readalarmsuspension(4)"));
    }

    @Test
    public void soundPickerUsesCategoryCardsAndExplicitSelectionState()
            throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "GlucoseAlertSettingsPage.java"));
        String picker = between(page,
                "private LinearLayout buildSoundPickerContent",
                "private boolean save()");

        assertTrue(picker.contains("CriticalAlarmSoundCatalog.Category.values()"));
        assertTrue(picker.contains("styleSoundCategoryTab(tab)"));
        assertTrue(picker.contains("tab.setButtonDrawable(null)"));
        assertTrue(picker.contains("StateListDrawable"));
        assertTrue(picker.contains("categoryScroll.smoothScrollTo"));
        assertTrue(picker.contains("tone.category != picker.visibleCategory"));
        assertTrue(picker.contains("categories.setOnCheckedChangeListener"));
        assertTrue(picker.contains("picker.options.clear()"));
        assertTrue(picker.contains("picker.categoryContent.removeAllViews()"));
        assertTrue(picker.contains("ClinicalUi.card(activity"));
        assertTrue(picker.contains("option.radio.setChecked(checked)"));
        assertTrue(picker.contains("soundOptionBackground(checked, true)"));
        assertTrue(picker.contains("announceForAccessibility"));
        assertTrue(picker.contains("critical_alarm_sound_option_selected"));
        assertTrue(picker.contains("critical_alarm_sound_option_available"));
        assertTrue(picker.contains("toneId.equals(picker.chosenToneId)"));
        assertTrue(picker.contains("refreshSoundPicker(picker, false)"));
        assertEquals(5, CriticalAlarmSoundCatalog.Category.values().length);
    }

    @Test
    public void previewIsSeparateAndCannotPersistCandidate() throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "GlucoseAlertSettingsPage.java"));
        String preview = between(page, "private void previewSound(",
                "private void refreshSoundPicker(");
        String dialog = between(page, "private void showSoundPicker()",
                "private LinearLayout buildSoundPickerContent");

        assertTrue(preview.contains("CriticalGlucoseAlarm.previewSound"));
        assertFalse(preview.contains("CriticalAlarmSoundCatalog.select"));
        assertTrue(dialog.contains("DialogInterface.BUTTON_POSITIVE"));
        assertTrue(dialog.contains("CriticalAlarmSoundCatalog.select"));
        assertFalse(dialog.contains("BUTTON_NEUTRAL"));
        assertTrue(context.getString(
                R.string.critical_alarm_sound_picker_intro)
                .contains("never changes your saved alert"));
        assertTrue(context.getString(
                R.string.critical_alarm_sound_preview_accessibility)
                .contains("does not save"));

        Configuration russian = new Configuration(
                context.getResources().getConfiguration());
        russian.setLocale(Locale.forLanguageTag("ru"));
        Context ru = context.createConfigurationContext(russian);
        assertTrue(ru.getString(R.string.critical_alarm_sound_picker_intro)
                .contains("никогда не меняет"));
        assertTrue(ru.getString(
                R.string.critical_alarm_sound_preview_accessibility)
                .contains("не будет сохранён"));
    }

    @Test
    public void globalPageOffersReadinessActionsNotDeliveryModes()
            throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "FullScreenAlertSettingsPage.java"));
        assertTrue(page.contains("openNotificationSettings(activity)"));
        assertTrue(page.contains("openFullScreenSettings(activity)"));
        assertTrue(page.contains("openOverlaySettings(activity)"));
        assertTrue(page.contains("openDndSettings(activity)"));
        assertFalse(page.contains("openExactAlarmSettings(activity)"));
        assertFalse(page.contains("RadioGroup"));
        assertFalse(page.contains("getalarmSoundType"));
        assertFalse(page.contains("setalarmSoundType"));
        assertTrue(FullScreenAlertSettingsPage.MIN_TOUCH_TARGET_DP >= 48);
    }

    @Test
    public void copyNamesGlobalAlertsAndForcedFullScreenDelivery() {
        assertEquals("Glucose alerts",
                context.getString(R.string.glucose_alert_types_section));
        assertTrue(context.getString(R.string.fullscreen_alert_settings_intro)
                .contains("All five glucose alerts"));
        assertTrue(context.getString(R.string.fullscreen_alert_behavior_body)
                .contains("single red acknowledgement button"));
        String saveHint = context.getString(R.string.glucose_alert_auto_save_hint);
        assertTrue(saveHint.contains("Close or Back"));
        assertFalse(saveHint.contains("automatically"));
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
