package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;

/** Regression contract for the separate insulin form and confirmable meal chat. */
public class IntakeSeparatedFlowsContractTest {
    @Test
    public void chooserRoutesToTwoIndependentScreens() throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        Document chooser = layout("modern_intake_chooser.xml");

        assertNotNull(chooser.getElementById("intake_choose_insulin"));
        assertNotNull(chooser.getElementById("intake_choose_meal"));
        assertTrue(composer.contains("showInsulin()"));
        assertTrue(composer.contains("showMeal()"));
        assertTrue(composer.contains("R.layout.modern_insulin_composer"));
        assertTrue(composer.contains("R.layout.modern_meal_chat"));
        assertFalse("The old combined form must not be mounted",
                composer.contains("R.layout.modern_intake_composer"));
    }

    @Test
    public void insulinFormOffersOnlyNovoRapidAndTresiba() throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String client = mainJava("tk/glucodata/IntakeApiClient.java");
        String layout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_insulin_composer.xml")));

        assertTrue(composer.contains("@Override public int getCount() { return 2; }"));
        assertTrue(composer.contains("\"NovoRapid\" : \"Tresiba\""));
        assertTrue(client.contains("request.put(\"insulin_name\""));
        assertFalse(client.contains("request.put(\"insulin_type\""));
        assertFalse(composer.contains("intake_type_other"));
        assertTrue(layout.contains("@+id/insulin_product_spinner"));
        assertTrue(layout.contains("@+id/insulin_dose"));
    }

    @Test
    public void doseIsTheImmediatePrimaryInput() throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String layout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_insulin_composer.xml")));

        assertTrue(composer.contains("insulinDose.requestFocus()"));
        assertTrue(composer.contains("keyboard.showSoftInput(insulinDose"));
        assertTrue(composer.contains("insulinProduct.setSelection(insulinProductIndex"));
        assertTrue(layout.contains("android:inputType=\"numberDecimal\""));
    }

    @Test
    public void bothFlowsUseQuickAndExactOccurrenceTimeChooser()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        Document chooser = layout("modern_intake_time_chooser.xml");

        assertNotNull(chooser.getElementById("intake_time_now"));
        assertNotNull(chooser.getElementById("intake_time_5m"));
        assertNotNull(chooser.getElementById("intake_time_10m"));
        assertNotNull(chooser.getElementById("intake_time_20m"));
        assertNotNull(chooser.getElementById("intake_time_30m"));
        assertNotNull(chooser.getElementById("intake_time_40m"));
        assertNotNull(chooser.getElementById("intake_time_50m"));
        assertNotNull(chooser.getElementById("intake_time_60m"));
        assertNotNull(chooser.getElementById("intake_time_exact"));
        assertTrue("The expanded chooser must scroll on short landscape screens",
                "ScrollView".equals(
                        chooser.getDocumentElement().getTagName()));
        assertTrue(composer.contains("pickTime(false)"));
        assertTrue(composer.contains("pickTime(true)"));
        assertTrue(composer.contains("pickExactDateAndTime"));
        assertTrue(composer.contains("selected.set(Calendar.YEAR, year)"));
        assertTrue(composer.contains("selected.set(Calendar.HOUR_OF_DAY, hour)"));
        assertTrue("The 60-minute preset must remain visibly relative",
                composer.contains(": ageMinutes <= 90L"));
    }

    @Test
    public void readyMealTimeCanBeChangedAndBackendRemainsSourceOfTruth()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String client = mainJava("tk/glucodata/IntakeApiClient.java");
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        Document meal = layout("modern_meal_chat.xml");
        String english = read(commonPath(Paths.get("src", "main", "res",
                "values", "intake_v10_strings.xml")));
        String russian = read(commonPath(Paths.get("src", "main", "res",
                "values-ru", "intake_v10_strings.xml")));

        assertNotNull(meal.getElementById("meal_chat_proposal_time"));
        assertTrue(composer.contains(
                "proposalTime.setOnClickListener(view -> mealTimeAction())"));
        assertFalse("A created chat must not permanently lock event time",
                composer.contains("mealTimeLocked"));
        assertTrue(client.contains("MealChatSession updateMealChatTime"));
        assertTrue(client.contains("requestJson(\"PUT\""));
        assertTrue(client.contains("+ \"/time\""));
        int resolver = repository.indexOf("void resolveMealChatTime(");
        int firstPut = repository.indexOf(
                "return api.updateMealChatTime(sessionId, occurredAtMs);",
                resolver);
        int retryPut = repository.indexOf(
                "return api.updateMealChatTime(sessionId, occurredAtMs);",
                firstPut + 1);
        int reconcileGet = repository.indexOf(
                "return api.getMealChatSession(sessionId);", retryPut);
        assertTrue("Ambiguous PUT must retry once, then reconcile with GET",
                resolver >= 0 && firstPut > resolver && retryPut > firstPut
                        && reconcileGet > retryPut);
        assertTrue(composer.contains(
                "repository.resolveMealChatTime(mealSessionId, value"));
        assertTrue("The accepted backend timestamp must drive Add and UI",
                composer.contains("mealOccurredAtMs = session.occurredAtMs;"));
        assertFalse("A lost PUT response must never trigger a blind rollback",
                composer.contains("mealOccurredAtMs = previous;"));
        assertTrue("Unknown time state must block Add and expose retry",
                composer.contains("&& !mealTimeSyncUnknown")
                        && composer.contains("intake_time_selected_retry")
                        && composer.contains("meal_chat_proposal_time_retry"));
        assertTrue("Confirm cannot overtake an in-flight time update",
                composer.indexOf("if (mealTimeUpdating)")
                        < composer.indexOf("repository.confirmMealChat("));
        assertTrue(english.contains("meal_chat_proposal_time_action"));
        assertTrue(english.contains("meal_chat_time_sync_error"));
        assertTrue(russian.contains("meal_chat_proposal_time_action"));
        assertTrue(russian.contains("meal_chat_time_sync_error"));
    }

    @Test
    public void unspecifiedMealPortionNeverRendersAsZeroGrams()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String english = read(commonPath(Paths.get("src", "main", "res",
                "values", "intake_v10_strings.xml")));
        String russian = read(commonPath(Paths.get("src", "main", "res",
                "values-ru", "intake_v10_strings.xml")));

        assertTrue(composer.contains("totalPortionGrams > 0.0f"));
        assertTrue(composer.contains(
                "R.string.meal_chat_proposal_meal_no_portion"));
        assertTrue(english.contains("meal_chat_proposal_meal_no_portion")
                && english.contains("portion not specified"));
        assertTrue(russian.contains("meal_chat_proposal_meal_no_portion")
                && russian.contains("порция не указана"));
    }

    @Test
    public void intakeScreensResizeAroundImeAndKeepProposalScrollable()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        Document meal = layout("modern_meal_chat.xml");
        String insulinLayout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_insulin_composer.xml")));
        String mealLayout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_meal_chat.xml")));

        assertTrue(composer.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertTrue(composer.contains("WindowInsetsCompat.Type.ime()"));
        assertTrue(composer.contains("Math.max(bars.bottom, ime.bottom)"));
        assertTrue(insulinLayout.contains("actionDone|flagNoExtractUi"));
        assertTrue(mealLayout.contains("actionSend|flagNoExtractUi"));
        assertNotNull(meal.getElementById("meal_chat_feed"));
        assertNotNull(meal.getElementById("meal_chat_proposal"));
        assertNotNull(meal.getElementById("meal_chat_safety"));
    }

    @Test
    public void mealConversationUsesCreateTurnAndExplicitConfirmEndpoints()
            throws Exception {
        String client = mainJava("tk/glucodata/IntakeApiClient.java");
        String repository = mainJava("tk/glucodata/IntakeRepository.java");

        assertTrue(client.contains("\"/v1/insulin-events\""));
        assertFalse(client.contains("\"POST\", \"/v1/intakes\""));
        assertFalse(client.contains("\"/v1/analyze\""));
        assertTrue(client.contains("\"/v1/meal-chat/sessions\""));
        assertTrue(client.contains("+ \"/messages\""));
        assertTrue(client.contains("+ \"/confirm\""));
        assertTrue(client.contains("for (File photo : photos)"));
        assertTrue(client.contains("\"photos\", photo"));

        int confirm = repository.indexOf(
                "api -> api.confirmMealChatSession(sessionId)");
        int publish = repository.indexOf("mergeConfirmedEvent(confirmed)");
        assertTrue("Meal must reach the graph only after backend confirmation",
                confirm >= 0 && publish >= 0
                        && repository.contains("onSuccess(IntakeEvent confirmed)"));
    }

    @Test
    public void mealUiSupportsMultiPhotoTurnsWithoutFixedPhotoSlots()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String layout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_meal_chat.xml")));

        assertTrue(composer.contains("Intent.EXTRA_ALLOW_MULTIPLE"));
        assertTrue(composer.contains("ArrayList<File> pendingPhotos"));
        assertTrue(composer.contains("clearSentMedia(photos)"));
        assertFalse(composer.contains("foodPhoto"));
        assertFalse(composer.contains("labelPhoto"));
        assertTrue(layout.contains("@+id/meal_chat_attachments"));
        assertTrue(layout.contains("@+id/meal_chat_messages"));
        assertTrue(layout.contains("@+id/meal_chat_confirm"));
    }

    @Test
    public void voiceBecomesEditableTextBeforeItCanEnterTheChat()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String client = mainJava("tk/glucodata/IntakeApiClient.java");

        assertTrue(client.contains("\"/v1/transcriptions\""));
        assertTrue(composer.contains("repository.transcribeAudio(audio"));
        assertTrue(composer.contains("Editable editable = mealInput.getText()"));
        assertTrue(composer.contains("editable.replace(start, end, insertion)"));
        assertTrue(composer.contains("mealInput.requestFocus()"));
        assertFalse(composer.contains("pendingAudio"));
    }

    @Test
    public void mealComposerShowsRealBusyIndicatorsAndClearAddAction()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        Document meal = layout("modern_meal_chat.xml");
        String layout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_meal_chat.xml")));
        String english = read(commonPath(Paths.get("src", "main", "res",
                "values", "intake_v10_strings.xml")));
        String russian = read(commonPath(Paths.get("src", "main", "res",
                "values-ru", "intake_v10_strings.xml")));

        assertNotNull(meal.getElementById("meal_chat_voice_progress"));
        assertNotNull(meal.getElementById("meal_chat_send_progress"));
        assertNotNull(meal.getElementById("meal_chat_voice_icon"));
        assertNotNull(meal.getElementById("meal_chat_send_icon"));
        assertTrue(layout.contains("@drawable/intake_add"));
        assertTrue(layout.contains("@drawable/intake_send"));
        assertFalse("Busy state must not be a text ellipsis",
                layout.contains("android:text=\"…\""));
        assertFalse("Send action must not depend on a font arrow glyph",
                layout.contains("android:text=\"↑\""));
        assertTrue(composer.contains("boolean showProgress = transcribing"));
        assertTrue(composer.contains(
                "mealVoiceProgress.setVisibility(showProgress ? VISIBLE : GONE)"));
        assertTrue(composer.contains(
                "mealSendProgress.setVisibility(sending ? VISIBLE : GONE)"));
        assertTrue(english.contains(">Add  ·  %1$s</string>"));
        assertTrue(russian.contains(">Добавить  ·  %1$s</string>"));
    }

    @Test
    public void aCorrectionDismissesTheStaleReadyProposalBeforeSending()
            throws Exception {
        String composer = mainJava("tk/glucodata/IntakeComposer.java");
        String layout = read(commonPath(Paths.get("src", "main", "res",
                "layout", "modern_meal_chat.xml")));

        int clear = composer.indexOf("clearProposalForRevision();");
        int busy = composer.indexOf("setMealBusy(true);", clear);
        assertTrue("The old proposal must disappear before a correction waits",
                clear >= 0 && busy > clear);
        assertTrue(composer.contains("mealProposal = turn.proposal;"));
        assertTrue(composer.contains(
                "mealReadyToConfirm = turn.readyToConfirm;"));
        assertTrue("The correction input stays outside the proposal card",
                layout.indexOf("@+id/meal_chat_proposal")
                        < layout.indexOf("@+id/meal_chat_input"));
        assertTrue(layout.contains("@string/meal_chat_keep_chatting"));
    }

    @Test
    public void androidClientContainsNoAiProviderDetailsOrSecrets()
            throws Exception {
        for (Path root : new Path[]{
                commonPath(Paths.get("src", "main", "java")),
                commonPath(Paths.get("src", "main", "res"))}) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String content = read(path).toLowerCase(
                                java.util.Locale.ROOT);
                        for (String forbidden : new String[]{
                                "sk-or-v1-", "openrouter", "qwen", "gemini",
                                "whisper", "api.openai", "generativelanguage"}) {
                            assertFalse("AI provider detail was embedded in " + path,
                                    content.contains(forbidden));
                        }
                    } catch (IOException error) {
                        throw new RuntimeException(error);
                    }
                });
            }
        }
    }

    @Test
    public void backendSourceContainsNoEmbeddedProviderSecret()
            throws Exception {
        Path root = projectPath(Paths.get("backend", "app"));
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    assertFalse("A provider secret prefix was embedded in " + path,
                            read(path).contains("sk-or-v1-"));
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            });
        }
    }

    private static Document layout(String file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(commonPath(
                Paths.get("src", "main", "res", "layout", file)).toFile());
        // Android IDs are not XML type IDs. Mark them for convenient lookup.
        markAndroidIds(document.getDocumentElement());
        return document;
    }

    private static void markAndroidIds(org.w3c.dom.Element element) {
        String id = element.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "id");
        if (id.startsWith("@+id/")) {
            element.setAttribute("contract-id", id.substring(5));
            element.setIdAttribute("contract-id", true);
        }
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof org.w3c.dom.Element) {
                markAndroidIds((org.w3c.dom.Element) children.item(index));
            }
        }
    }

    private static String mainJava(String relative) throws IOException {
        return read(commonPath(Paths.get("src", "main", "java")
                .resolve(relative)));
    }

    private static Path commonPath(Path relative) {
        Path direct = Paths.get("Common").resolve(relative);
        return Files.exists(direct) ? direct : relative;
    }

    private static Path projectPath(Path relative) {
        return Files.exists(relative) ? relative : Paths.get("..").resolve(relative);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
