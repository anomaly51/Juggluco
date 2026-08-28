package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/** Behavioral contract for independent meal/insulin chat cards. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeChatCardHistoryTest {
    @Test
    public void mixedTurnCreatesTwoIndependentActiveCards() throws Exception {
        IntakeChatTurn mixed = turn(1, 2, 3,
                insulinEvent(4, 5, 5.0, 1_000L),
                mealEvent(6, 7, "Pizza", 42.0, 2_000L));
        IntakeChatCardHistory history = new IntakeChatCardHistory();

        history.accept(mixed);

        assertEquals(2, history.cards().size());
        assertTrue(history.cards().get(0).event().hasInsulin());
        assertTrue(history.cards().get(1).event().hasMeal());
        assertTrue(history.cards().get(0).isActive());
        assertTrue(history.cards().get(1).isActive());
        assertSame(history.cards().get(1), history.primaryForTurn(mixed));
        assertFalse(IntakeChatCardHistory.supportsSingleCardDelete(mixed));
    }

    @Test
    public void replacementTombstonesOnlyNamedEventAndKeepsSiblingActive()
            throws Exception {
        JSONObject insulin = insulinEvent(14, 15, 5.0, 1_000L);
        JSONObject oldMeal = mealEvent(16, 17, "Pizza", 42.0, 2_000L);
        IntakeChatTurn mixed = turn(11, 12, 13, insulin, oldMeal);
        IntakeChatTurn replacement = turnWithDeleted(21, 22, 23,
                new JSONArray().put(mealEvent(24, 25,
                        "Pizza", 60.0, 2_000L)),
                new JSONArray().put(uuid(16)));
        IntakeChatCardHistory history = new IntakeChatCardHistory();

        history.accept(mixed);
        history.accept(replacement);

        List<IntakeChatCardHistory.Card> cards = history.cards();
        assertEquals(3, cards.size());
        assertTrue(cards.get(0).isActive());
        assertEquals(IntakeChatCardHistory.Status.REPLACED,
                cards.get(1).status());
        assertFalse(cards.get(1).isActive());
        assertTrue(cards.get(2).isActive());
        assertEquals(60.0f, cards.get(2).event().carbsGrams, 0.001f);
        assertFalse(IntakeChatCardHistory.supportsSingleCardDelete(
                replacement));
    }

    @Test
    public void cardDeleteIsOfferedOnlyForPlainSingleRecordCreate()
            throws Exception {
        IntakeChatTurn single = turn(71, 72, 73,
                insulinEvent(74, 75, 5.0, 1_000L));
        IntakeChatTurn mixed = turn(81, 82, 83,
                insulinEvent(84, 85, 5.0, 1_000L),
                mealEvent(86, 87, "Soup", 20.0, 2_000L));
        IntakeChatTurn replacement = turnWithDeleted(91, 92, 93,
                new JSONArray().put(insulinEvent(94, 95,
                        6.0, 1_000L)),
                new JSONArray().put(uuid(74)));

        assertTrue(IntakeChatCardHistory.supportsSingleCardDelete(single));
        assertFalse(IntakeChatCardHistory.supportsSingleCardDelete(mixed));
        assertFalse(IntakeChatCardHistory.supportsSingleCardDelete(
                replacement));
    }

    @Test
    public void deleteLeavesReadableTombstoneWithoutRemovingOtherCard()
            throws Exception {
        IntakeChatTurn insulin = turn(31, 32, 33,
                insulinEvent(34, 35, 5.0, 1_000L));
        IntakeChatTurn meal = turn(41, 42, 43,
                mealEvent(44, 45, "Soup", 20.0, 2_000L));
        IntakeChatTurn deleted = IntakeChatTurn.fromUndoJson(new JSONObject()
                .put("action_id", uuid(43))
                .put("outcome", "undone")
                .put("events", new JSONArray())
                .put("deleted_event_ids", new JSONArray().put(uuid(44))));
        IntakeChatCardHistory history = new IntakeChatCardHistory();

        history.accept(insulin);
        history.accept(meal);
        history.accept(deleted);

        assertEquals(2, history.cards().size());
        assertTrue(history.cards().get(0).isActive());
        assertEquals(IntakeChatCardHistory.Status.DELETED,
                history.cards().get(1).status());
        assertFalse(history.cards().get(1).isActive());
        assertSame(history.cards().get(1), history.primaryForTurn(meal));
    }

    @Test
    public void restoringEarlierEventReactivatesExistingCardWithoutDuplicate()
            throws Exception {
        IntakeChatTurn original = turn(51, 52, 53,
                insulinEvent(54, 55, 5.0, 1_000L));
        IntakeChatTurn replacement = turnWithDeleted(61, 62, 63,
                new JSONArray().put(insulinEvent(64, 65,
                        6.0, 1_000L)),
                new JSONArray().put(uuid(54)));
        IntakeChatTurn undo = IntakeChatTurn.fromUndoJson(new JSONObject()
                .put("action_id", uuid(63))
                .put("outcome", "undone")
                .put("events", new JSONArray().put(
                        insulinEvent(54, 55, 5.0, 1_000L)))
                .put("deleted_event_ids", new JSONArray().put(uuid(64))));
        IntakeChatCardHistory history = new IntakeChatCardHistory();

        history.accept(original);
        history.accept(replacement);
        history.accept(undo);

        assertEquals(2, history.cards().size());
        assertTrue(history.cards().get(0).isActive());
        assertEquals(IntakeChatCardHistory.Status.REPLACED,
                history.cards().get(1).status());
        assertSame(history.cards().get(0), history.primaryForTurn(undo));
    }

    private static IntakeChatTurn turn(int session, int clientTurn,
            int action, JSONObject... events) throws Exception {
        return turnWithDeleted(session, clientTurn, action,
                new JSONArray(events), new JSONArray());
    }

    private static IntakeChatTurn turnWithDeleted(int session,
            int clientTurn, int action, JSONArray events, JSONArray deleted)
            throws Exception {
        return IntakeChatTurn.fromJson(new JSONObject()
                .put("session_id", uuid(session))
                .put("client_turn_id", uuid(clientTurn))
                .put("assistant_message", "Recorded")
                .put("transcript", "reported intake")
                .put("outcome", "applied")
                .put("action_id", uuid(action))
                .put("events", events)
                .put("deleted_event_ids", deleted));
    }

    private static JSONObject insulinEvent(int event, int clientEvent,
            double units, long occurredAt) throws Exception {
        return eventBase(event, clientEvent, occurredAt)
                .put("meal_text", JSONObject.NULL)
                .put("carbs_g", JSONObject.NULL)
                .put("portion_g", JSONObject.NULL)
                .put("original_portion_g", JSONObject.NULL)
                .put("original_carbs_g", JSONObject.NULL)
                .put("carbs_source", JSONObject.NULL)
                .put("insulin_units", units)
                .put("insulin_type", "rapid")
                .put("insulin_name", "NovoRapid")
                .put("analysis_id", JSONObject.NULL);
    }

    private static JSONObject mealEvent(int event, int clientEvent,
            String meal, double carbs, long occurredAt) throws Exception {
        return eventBase(event, clientEvent, occurredAt)
                .put("meal_text", meal)
                .put("carbs_g", carbs)
                .put("portion_g", JSONObject.NULL)
                .put("original_portion_g", JSONObject.NULL)
                .put("original_carbs_g", JSONObject.NULL)
                .put("carbs_source", "manual")
                .put("insulin_units", JSONObject.NULL)
                .put("insulin_type", JSONObject.NULL)
                .put("insulin_name", JSONObject.NULL)
                .put("analysis_id", JSONObject.NULL);
    }

    private static JSONObject eventBase(int event, int clientEvent,
            long occurredAt) throws Exception {
        return new JSONObject()
                .put("id", uuid(event))
                .put("client_event_id", uuid(clientEvent))
                .put("occurred_at_ms", occurredAt)
                .put("ai_confidence", 0.0)
                .put("absorption_speed", JSONObject.NULL)
                .put("absorption_peak_minutes", JSONObject.NULL)
                .put("absorption_duration_minutes", JSONObject.NULL)
                .put("absorption_confidence", JSONObject.NULL)
                .put("created_at_ms", occurredAt)
                .put("updated_at_ms", occurredAt)
                .put("deleted_at_ms", JSONObject.NULL)
                .put("deleted", false)
                .put("sync_version", 1L);
    }

    private static String uuid(int value) {
        return String.format(java.util.Locale.ROOT,
                "00000000-0000-4000-8000-%012d", value);
    }
}
