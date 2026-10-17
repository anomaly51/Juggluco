package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class IntakeStructuredIntentTest {
    private static final String OPERATION_ID =
            "00000000-0000-0000-0000-000000000101";
    private static final String EVENT_ID =
            "00000000-0000-0000-0000-000000000202";

    @Test
    public void insulinAddUsesCanonicalVersionedWireShape() throws Exception {
        long when = System.currentTimeMillis() - 300_000L;
        String raw = IntakeStructuredIntent.insulin(
                IntakeStructuredIntent.ACTION_ADD, "", when,
                "NovoRapid", 5.5f, OPERATION_ID);

        assertTrue(IntakeStructuredIntent.isValidWirePayload(raw));
        JSONObject root = new JSONObject(raw);
        JSONObject operation = root.getJSONArray("operations")
                .getJSONObject(0);
        assertEquals(1, root.getInt("version"));
        assertEquals("add", operation.getString("action"));
        assertEquals("insulin", operation.getString("entity"));
        assertTrue(operation.isNull("target_event_id"));
        assertEquals(when, operation.getLong("occurred_at_ms"));
        assertEquals("NovoRapid", operation.getString("insulin_name"));
        assertEquals(5.5, operation.getDouble("insulin_units"), 0.0);
        assertTrue(operation.isNull("meal_text"));
    }

    @Test
    public void mealUpdateCarriesExactTargetAndFullDesiredSnapshot()
            throws Exception {
        long when = System.currentTimeMillis() - 900_000L;
        String raw = IntakeStructuredIntent.meal(
                IntakeStructuredIntent.ACTION_UPDATE, EVENT_ID, when,
                "Rice and chicken", 42.0f, OPERATION_ID);

        assertTrue(IntakeStructuredIntent.isValidWirePayload(raw));
        JSONObject operation = new JSONObject(raw)
                .getJSONArray("operations").getJSONObject(0);
        assertEquals(EVENT_ID, operation.getString("target_event_id"));
        assertEquals("meal", operation.getString("entity"));
        assertEquals("Rice and chicken", operation.getString("meal_text"));
        assertEquals(42.0, operation.getDouble("carbs_g"), 0.0);
        assertTrue(operation.isNull("insulin_name"));
    }

    @Test
    public void mealNullableFieldsKeepTheirMeaningAcrossQuickUpdates()
            throws Exception {
        long when = System.currentTimeMillis() - 60_000L;
        JSONObject nameOnly = new JSONObject(IntakeStructuredIntent.meal(
                IntakeStructuredIntent.ACTION_UPDATE, EVENT_ID, when,
                "Soup", (Float) null, OPERATION_ID))
                .getJSONArray("operations").getJSONObject(0);
        assertEquals("Soup", nameOnly.getString("meal_text"));
        assertTrue(nameOnly.isNull("carbs_g"));

        JSONObject carbsOnly = new JSONObject(IntakeStructuredIntent.meal(
                IntakeStructuredIntent.ACTION_ADD, "", when, "", 18.0f,
                OPERATION_ID)).getJSONArray("operations").getJSONObject(0);
        assertTrue(carbsOnly.isNull("meal_text"));
        assertEquals(18.0, carbsOnly.getDouble("carbs_g"), 0.0);
        assertTrue(IntakeStructuredIntent.isValidWirePayload(
                new JSONObject().put("version", 1).put("operations",
                        new org.json.JSONArray().put(carbsOnly)).toString()));
    }

    @Test
    public void invalidOrFutureMedicalFactsAreRejectedBeforeTransport() {
        assertFalse(IntakeStructuredIntent.isValidWirePayload("{}"));
        try {
            IntakeStructuredIntent.insulin(IntakeStructuredIntent.ACTION_ADD,
                    "", System.currentTimeMillis() + 60_000L,
                    "NovoRapid", 5.0f, OPERATION_ID);
            fail("Future injection must be rejected");
        } catch (IllegalArgumentException expected) {}
        try {
            IntakeStructuredIntent.insulin(
                    IntakeStructuredIntent.ACTION_UPDATE, "not-an-id",
                    System.currentTimeMillis(), "Tresiba", 8.0f,
                    OPERATION_ID);
            fail("Update must carry an exact event id");
        } catch (IllegalArgumentException expected) {}
    }
}
