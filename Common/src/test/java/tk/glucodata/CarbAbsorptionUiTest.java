package tk.glucodata;

import static android.view.View.GONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import org.json.JSONObject;
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

/** Contract for the honest 0..100 carbohydrate-absorption presentation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CarbAbsorptionUiTest {
    @Test
    public void scoreAndBandsPreserveContinuousBackendEstimate() {
        assertEquals(-1, CarbAbsorptionUi.index(null));
        assertEquals(-1, CarbAbsorptionUi.index(Float.NaN));
        assertEquals(-1, CarbAbsorptionUi.index(-0.01f));
        assertEquals(-1, CarbAbsorptionUi.index(1.01f));
        assertEquals(CarbAbsorptionUi.Band.NOT_ESTIMATED,
                CarbAbsorptionUi.band(null));

        assertEquals(0, CarbAbsorptionUi.index(0.0f));
        assertEquals(33, CarbAbsorptionUi.index(0.33f));
        assertEquals(CarbAbsorptionUi.Band.SLOW,
                CarbAbsorptionUi.band(0.33f));
        assertEquals(34, CarbAbsorptionUi.index(0.34f));
        assertEquals(CarbAbsorptionUi.Band.MEDIUM,
                CarbAbsorptionUi.band(0.34f));
        assertEquals(66, CarbAbsorptionUi.index(0.66f));
        assertEquals(67, CarbAbsorptionUi.index(0.67f));
        assertEquals(CarbAbsorptionUi.Band.FAST,
                CarbAbsorptionUi.band(0.67f));
        assertEquals(100, CarbAbsorptionUi.index(1.0f));
    }

    @Test
    public void formatterIsLocalizedAndNeverInventsMissingEvidence() {
        Context english = localized(Locale.ENGLISH);
        Context russian = localized(Locale.forLanguageTag("ru"));

        assertEquals("Fast \u00b7 78/100",
                CarbAbsorptionUi.compact(english, 0.78f));
        assertEquals("\u0411\u044b\u0441\u0442\u0440\u043e \u00b7 78/100",
                CarbAbsorptionUi.compact(russian, 0.78f));
        assertEquals("Not estimated",
                CarbAbsorptionUi.compact(english, null));
        String missing = CarbAbsorptionUi.valueDetails(english, null,
                null, null, null);
        assertEquals("Not estimated", missing);

        String complete = CarbAbsorptionUi.valueDetails(english, 0.78f,
                45, 180, 0.81f);
        assertTrue(complete.contains("78/100"));
        assertTrue(complete.contains("peak around 45 min"));
        assertTrue(complete.contains("duration around 180 min"));
        assertTrue(complete.contains("confidence 81%"));
    }

    @Test
    public void mealProposalParsesOptionalAbsorptionEvidence() throws Exception {
        JSONObject value = new JSONObject()
                .put("meal_name", "Fruit and yoghurt")
                .put("meal_description", "Fruit and yoghurt")
                .put("total_portion_g", 250)
                .put("items", new org.json.JSONArray())
                .put("estimated_carbs_g", 42)
                .put("carbs_low_g", 35)
                .put("carbs_high_g", 50)
                .put("confidence", 0.8)
                .put("absorption_speed", 0.73)
                .put("absorption_peak_minutes", 50)
                .put("absorption_duration_minutes", 210)
                .put("absorption_confidence", 0.69)
                .put("warnings", new org.json.JSONArray());
        MealChatSession.Proposal proposal =
                MealChatSession.Proposal.fromJson(value);

        assertNotNull(proposal);
        assertEquals(0.73f, proposal.absorptionSpeed, 0.0001f);
        assertEquals(Integer.valueOf(50), proposal.absorptionPeakMinutes);
        assertEquals(Integer.valueOf(210), proposal.absorptionDurationMinutes);
        assertEquals(0.69f, proposal.absorptionConfidence, 0.0001f);

        MealChatSession.Proposal missing =
                MealChatSession.Proposal.fromJson(withoutAbsorption(value));
        assertNull(missing.absorptionSpeed);
        assertNull(missing.absorptionPeakMinutes);
        assertNull(missing.absorptionDurationMinutes);
        assertNull(missing.absorptionConfidence);
    }

    @Test
    public void confirmedEventRoundTripsAbsorptionWithoutFakingMissingData()
            throws Exception {
        JSONObject raw = eventJson()
                .put("absorption_speed", 0.58)
                .put("absorption_peak_minutes", 75)
                .put("absorption_duration_minutes", 260)
                .put("absorption_confidence", 0.64);
        IntakeEvent parsed = IntakeEvent.fromJson(raw);
        assertTrue(parsed.hasAbsorptionSpeed());
        assertEquals(58, CarbAbsorptionUi.index(parsed.absorptionSpeed));
        assertEquals(Integer.valueOf(75), parsed.absorptionPeakMinutes);
        assertEquals(Integer.valueOf(260), parsed.absorptionDurationMinutes);
        assertEquals(0.64f, parsed.absorptionConfidence, 0.0001f);

        IntakeEvent restored = IntakeEvent.fromJson(parsed.toJson());
        assertEquals(parsed.absorptionSpeed, restored.absorptionSpeed);
        assertEquals(parsed.absorptionPeakMinutes,
                restored.absorptionPeakMinutes);
        assertEquals(parsed.absorptionDurationMinutes,
                restored.absorptionDurationMinutes);
        assertEquals(parsed.absorptionConfidence,
                restored.absorptionConfidence);

        IntakeEvent missing = IntakeEvent.fromJson(eventJson());
        assertFalse(missing.hasAbsorptionSpeed());
        assertNull(missing.absorptionSpeed);
        assertNull(missing.absorptionPeakMinutes);
        assertNull(missing.absorptionDurationMinutes);
        assertNull(missing.absorptionConfidence);
    }

    @Test
    public void proposalAndSavedDetailsLayoutsExposeTheEstimate() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        View chat = LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        View details = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_details, null, false);

        assertNotNull(chat.findViewById(
                R.id.meal_chat_proposal_absorption));
        View section = details.findViewById(
                R.id.intake_event_details_absorption);
        assertNotNull(section);
        assertEquals(GONE, section.getVisibility());
        assertNotNull(details.findViewById(
                R.id.intake_event_details_absorption_value));
    }

    @Test
    public void uiWiringUsesSharedFormatterInAllRelevantSummaries()
            throws Exception {
        String composer = source("IntakeComposer.java");
        String details = source("IntakeEventDetailsSheet.java");
        String forecast = source("ForecastDetailsPage.java");

        assertTrue(composer.contains("CarbAbsorptionUi.details(activity"));
        assertTrue(details.contains("CarbAbsorptionUi.valueDetails(activity"));
        assertTrue(forecast.contains("CarbAbsorptionUi.compact(activity"));
        assertFalse(composer.toLowerCase(Locale.ROOT)
                .contains("sweetness index"));
    }

    private static JSONObject eventJson() throws Exception {
        return new JSONObject()
                .put("id", "event-1")
                .put("occurred_at_ms", 1_700_000_000_000L)
                .put("meal_text", "Meal")
                .put("carbs_g", 42)
                .put("carbs_source", "ai_estimate")
                .put("analysis_id", "analysis-1")
                .put("ai_confidence", 0.8);
    }

    private static JSONObject withoutAbsorption(JSONObject source)
            throws Exception {
        JSONObject copy = new JSONObject(source.toString());
        copy.remove("absorption_speed");
        copy.remove("absorption_peak_minutes");
        copy.remove("absorption_duration_minutes");
        copy.remove("absorption_confidence");
        return copy;
    }

    private static Context localized(Locale locale) {
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources()
                        .getConfiguration());
        configuration.setLocale(locale);
        return RuntimeEnvironment.getApplication()
                .createConfigurationContext(configuration);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
