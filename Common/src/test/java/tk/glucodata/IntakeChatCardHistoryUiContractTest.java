package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level accessibility contract for the custom Android chat cards. */
public class IntakeChatCardHistoryUiContractTest {
    @Test
    public void historyUsesPerEventCardsAndExplicitNonColorStatuses()
            throws Exception {
        String composer = read("src/main/java/tk/glucodata/IntakeComposer.java");
        String model = read(
                "src/main/java/tk/glucodata/IntakeChatCardHistory.java");
        String layout = read("src/main/res/layout/modern_meal_chat.xml");
        String english = read("src/main/res/values/intake_v10_strings.xml");
        String russian = read(
                "src/main/res/values-ru/intake_v10_strings.xml");

        assertTrue(layout.contains("@+id/meal_chat_action_history"));
        assertTrue(model.contains("enum Status { ACTIVE, REPLACED, DELETED }"));
        assertTrue(model.contains("for (String deletedEventId"));
        assertTrue(model.contains("for (IntakeEvent event : turn.events)"));
        assertTrue(composer.contains("renderActionCardHistory(current)"));
        assertTrue(composer.contains("addHistoricalActionCard(card)"));
        assertTrue(composer.contains("container.setClickable(false)"));
        assertTrue(composer.contains("container.setFocusable(false)"));
        assertTrue(composer.contains("container.setEnabled(card.isActive())"));
        assertTrue(composer.contains("IMPORTANT_FOR_ACCESSIBILITY_YES"));
        assertTrue(composer.contains(
                "summary.getText(), time.getText()"));
        assertTrue(composer.contains(
                "supportsSingleCardDelete(turn)"));
        assertTrue(composer.contains(
                "mealConfirm.setVisibility(cardDeleteAvailable"));
        assertTrue(composer.contains("intake_chat_compound_action_hint"));
        assertTrue(composer.contains(
                "boolean compoundAction = turn != null && turn.events.size() > 1"));
        assertTrue(composer.contains(
                "compoundAction\n                                                ? R.string.intake_chat_compound_action_hint\n                                                : R.string.intake_chat_active_replacement_hint"));
        assertTrue(english.contains(
                "name=\"intake_chat_active_replacement_hint\">This entry is active."));
        assertTrue(russian.contains(
                "name=\"intake_chat_active_replacement_hint\">Эта запись активна."));
        assertTrue(composer.contains("intake_chat_proposal_inactive"));
        assertTrue(composer.contains("ViewCompat.setAccessibilityHeading"));
        assertTrue(english.contains(">Replaced<"));
        assertTrue(english.contains("kept for history"));
        assertTrue(russian.contains(">Заменено<"));
        assertTrue(russian.contains("сохранено в истории"));
        assertFalse(layout.contains("@+id/intake_chat_action_edit"));
    }

    private static String read(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path path = Files.exists(direct) ? direct
                : Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
