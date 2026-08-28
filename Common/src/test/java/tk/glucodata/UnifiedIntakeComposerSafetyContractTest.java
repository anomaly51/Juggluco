package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression guards for the voice-first unified intake interaction. */
public class UnifiedIntakeComposerSafetyContractTest {
    @Test
    public void dashboardEntryAlwaysMountsTheUnifiedConversation() throws Exception {
        String source = composer();
        String entry = between(source, "void show() {",
                "private void showChooserInternal()");

        assertTrue(entry.contains("showMeal();"));
        assertFalse(entry.contains("showChooserInternal();"));
        assertFalse(entry.contains("showManualMeal();"));
    }

    @Test
    public void freshUnifiedChatAutoStartsVoiceOnlyAfterAttachment()
            throws Exception {
        String source = composer();
        String showMeal = between(source, "private void showMeal()",
                "private void sendMealMessage()");
        String autoStart = between(source,
                "private void scheduleInitialVoiceRecording()",
                "private void voiceAction()");

        assertTrue(showMeal.contains("scheduleInitialVoiceRecording();"));
        assertTrue(showMeal.contains("ACCESSIBILITY_LIVE_REGION_POLITE"));
        assertTrue(autoStart.contains("surface.post(() ->"));
        assertTrue(autoStart.contains("surface.isAttachedToWindow()"));
        assertTrue(autoStart.contains("surface != root"));
        assertTrue(autoStart.contains("initialVoiceAutoStartPending = false"));
        assertTrue(autoStart.contains("if (canStartVoiceRecording())"));
    }

    @Test
    public void autoVoiceNeverReplaysPendingTurnOrDoubleStartsForPermission()
            throws Exception {
        String source = composer();
        String autoStart = between(source,
                "private void scheduleInitialVoiceRecording()",
                "private void voiceAction()");
        String permission = between(source,
                "private void requestOrStartRecording()",
                "boolean handlePermissionResult");
        String permissionResult = between(source,
                "boolean handlePermissionResult", "private void startRecording()");
        String configuration = between(source,
                "void onConfigurationChanged()", "void destroy()");

        assertTrue(autoStart.contains("if (!retryClientTurnId.isEmpty())"));
        assertFalse(autoStart.contains("sendIntakeTurn("));
        assertTrue(source.contains("recordPermissionRequestInFlight"));
        assertTrue(permission.contains("if (!canStartVoiceRecording()) return"));
        assertTrue(permission.contains("recordPermissionRequestInFlight = true"));
        assertTrue(permissionResult.contains(
                "recordPermissionRequestInFlight = false"));
        assertFalse(configuration.contains("requestOrStartRecording()"));
    }

    @Test
    public void ambiguousRetryKeepsTheExactTurnIdentityAndPayload()
            throws Exception {
        String source = composer();
        String repository = read(path("src", "main", "java", "tk",
                "glucodata", "IntakeRepository.java"));

        assertTrue(source.contains("retryClientTurnId"));
        assertTrue(source.contains("retryTurnOccurredAtMs"));
        assertTrue(source.contains("retryTurnAudio"));
        assertTrue(source.contains("retryTurnPhotos"));
        assertTrue(source.contains("startIntakeTurnRequest(retryClientTurnId,"));
        assertTrue(source.contains("retryTurnOccurredAtMs, retryTurnText,"));
        assertTrue(source.contains("releaseIntakeTurnFile(audio, false)"));
        assertTrue(source.contains("mealInput.setEnabled(!value && !pendingUnknown)"));
        assertTrue(repository.contains("boolean hadAmbiguousAttempt = false"));
        assertTrue(repository.contains("hadAmbiguousAttempt = true"));
        assertTrue(repository.contains("commitMayHaveOccurred = hadAmbiguousAttempt"));
    }

    @Test
    public void everyUnpinnedTurnGetsItsRealSendTime() throws Exception {
        String source = composer();
        String send = between(source, "private void sendIntakeTurn(File audio)",
                "private void retryPendingIntakeTurn()");

        assertTrue(send.contains(
                "if (!controlTurn && !mealTimeExplicitForNextTurn)"));
        assertTrue(send.contains("mealOccurredAtMs = System.currentTimeMillis()"));
        assertTrue(send.contains("? System.currentTimeMillis() : mealOccurredAtMs"));
        assertTrue(send.contains("rememberPendingTurn(clientTurnId, occurredAtMs"));
    }

    @Test
    public void photoImportCannotBeOvertakenByVoice() throws Exception {
        String source = composer();

        assertTrue(source.contains("if (pendingPhotoImports > 0)"));
        assertTrue(source.contains("deferredIntakeAudio = audio"));
        assertTrue(source.contains(
                "pendingPhotoImports == 0 && deferredIntakeAudio != null"));
        assertTrue(source.contains("sendIntakeTurn(queuedAudio)"));
        assertTrue(source.contains("pendingPhotoImports == 0"
                + " && retryClientTurnId.isEmpty()"));
    }

    @Test
    public void clarificationDoesNotHideUndoForTheLastAppliedAction()
            throws Exception {
        String source = composer();

        assertTrue(source.contains("private IntakeChatTurn lastActionTurn"));
        assertTrue(source.contains("lastActionTurn = turn"));
        assertTrue(source.contains("IntakeChatTurn turn = lastActionTurn"));
        assertTrue(source.contains("OUTCOME_ALREADY_UNDONE"));
        assertTrue(source.contains("lastActionId = \"\""));
    }

    @Test
    public void backendIdentityChangeStartsAFreshConversation()
            throws Exception {
        String source = composer();

        assertTrue(source.contains("repository.addConfigurationListener"));
        assertTrue(source.contains("repository.removeConfigurationListener"));
        assertTrue(source.contains("intakeSessionGeneration++"));
        assertTrue(source.contains("intakeClientSessionId = UUID.randomUUID()"));
        assertTrue(source.contains("intakeSessionId = \"\""));
        assertTrue(source.contains("lastActionId = \"\""));
    }

    @Test
    public void pendingTurnPinsItsBackendUntilExplicitResolution()
            throws Exception {
        String source = composer();
        String repository = read(path("src", "main", "java", "tk",
                "glucodata", "IntakeRepository.java"));
        String settings = read(path("src", "main", "java", "tk",
                "glucodata", "IntakeBackendSettings.java"));
        String store = read(path("src", "main", "java", "tk", "glucodata",
                "IntakeChatStateStore.java"));
        String configuration = between(source,
                "private void onBackendConfigurationChanged",
                "private void checkBackend");
        String retry = between(source,
                "private void retryPendingIntakeTurn()",
                "private boolean rememberPendingTurn");

        int pendingGuard = configuration.indexOf(
                "if (!retryClientTurnId.isEmpty())");
        int destructiveReset = configuration.indexOf(
                "clearPendingTurnRetry(true)");
        assertTrue(pendingGuard >= 0);
        assertTrue(destructiveReset > pendingGuard);
        assertTrue(configuration.substring(pendingGuard, destructiveReset)
                .contains("return;"));
        assertTrue(source.contains("retryBackendFingerprint"));
        assertTrue(retry.contains("if (!pendingTurnMatchesBackend())"));
        assertTrue(source.contains(
                "mealSend.setEnabled(!value && pendingTurnMatchesBackend())"));
        assertTrue(source.contains(
                "boolean enabled = !busy && retryClientTurnId.isEmpty()"));
        assertTrue(repository.contains("IntakeChatStateStore.hasPendingTurn"));
        assertTrue(repository.contains("public boolean configure"));
        assertTrue(settings.contains("if(!repository.configure"));
        assertTrue(store.contains("ageMs < 0L"
                + " || ageMs > PENDING_MEDIA_TTL_MS"));
    }

    @Test
    public void resultCardDisclosesAiEstimateAndOccurrenceTime()
            throws Exception {
        String source = composer();
        String layout = read(path("src", "main", "res", "layout",
                "modern_meal_chat.xml"));

        assertTrue(source.contains("intake_chat_ai_estimate_confidence"));
        assertTrue(source.contains("intake_chat_ai_warning"));
        assertTrue(source.contains("intake_chat_action_time"));
        assertTrue(source.contains("CarbAbsorptionUi.details"));
        assertTrue(layout.contains("android:accessibilityLiveRegion=\"polite\""));
    }

    @Test
    public void closingTheSurfaceDoesNotCancelAnAuthoritativeTurn()
            throws Exception {
        String source = composer();
        String close = between(source, "private void requestClose(boolean popBack)",
                "private void close(boolean popBack)");

        assertTrue(close.contains("let the authoritative request finish"));
        assertFalse(close.contains("intakeTurnCall.cancel()"));
        assertTrue(source.contains("for (File photo : photos) deleteTemporary(photo)"));
    }

    @Test
    public void pendingTurnSurvivesSurfaceAndProcessRecreation()
            throws Exception {
        String source = composer();
        String store = read(path("src", "main", "java", "tk", "glucodata",
                "IntakeChatStateStore.java"));

        assertTrue(source.contains("restorePendingTurnState()"));
        assertTrue(source.contains("return persistChatState()"));
        assertTrue(source.contains("Never submit a restored medical fact"));
        String showMeal = between(source, "private void showMeal()",
                "private void sendMealMessage()");
        assertFalse(showMeal.contains("retryPendingIntakeTurn();"));
        assertTrue(store.contains("client_turn_id"));
        assertTrue(store.contains("occurred_at_ms"));
        assertTrue(store.contains("audio_path"));
        assertTrue(store.contains("photo_paths"));
        assertTrue(store.contains("backend_fingerprint"));
        assertTrue(store.contains(".commit()"));
        assertFalse(store.contains("backendToken"));
    }

    @Test
    public void correctionIsConversationalWhileDeleteRemainsExplicit()
            throws Exception {
        String source = composer();
        String send = between(source, "private void sendMealMessage()",
                "private void ensureIntakeSession");
        String delete = between(source, "private void confirmMeal()",
                "private void reconcilePendingTurn()");

        assertFalse(source.contains("beginCorrectionDialogue"));
        assertFalse(source.contains("intake_chat_action_edit"));
        assertTrue(send.contains("sendIntakeTurn(null);"));
        assertTrue(delete.contains("intake_chat_delete_control"));
        assertTrue(delete.contains("CONTROL_DELETE"));
        assertFalse(delete.contains("undoIntakeChatAction"));
        assertTrue(source.contains("retryTurnControlKind"));
        assertTrue(source.contains("rememberActionReceipt(turn, false)"));
    }

    @Test
    public void definitiveFailureCanBeExplicitlyReconciledAfterReopen()
            throws Exception {
        String source = composer();
        String layout = read(path("src", "main", "res", "layout",
                "modern_meal_chat.xml"));

        assertTrue(source.contains("onDefinitiveError(String message"));
        assertTrue(source.contains("retryTurnDefinitiveFailure"));
        assertTrue(source.contains("reconcilePendingTurn()"));
        assertTrue(source.contains("discardPendingTurnAndStartFresh()"));
        assertTrue(source.contains("pending.definitiveFailure"));
        assertTrue(layout.contains("@+id/intake_chat_reconcile"));
    }

    @Test
    public void transportFailuresUseAccessibleInlineRecoveryNotChatRows()
            throws Exception {
        String source = composer();
        String layout = read(path("src", "main", "res", "layout",
                "modern_meal_chat.xml"));
        String sessionFailure = between(source,
                "private void failIntakeSession", "private void sendIntakeTurn");
        String turnFailure = between(source,
                "private void handleIntakeTurnFailure",
                "private void releaseIntakeTurnFile");
        String configuration = between(source,
                "private void onBackendConfigurationChanged",
                "private void checkBackend");

        assertTrue(sessionFailure.contains("showInlineError("));
        assertFalse(sessionFailure.contains("chatLines.add("));
        assertTrue(turnFailure.contains("intake_chat_retry_inline_error"));
        assertTrue(turnFailure.contains("intake_chat_rejected_inline_error"));
        assertFalse(turnFailure.contains("chatLines.add("));
        assertFalse(turnFailure.contains("toast(error)"));
        assertFalse(configuration.contains("intake_chat_backend_changed"));
        assertTrue(layout.contains("@+id/intake_chat_inline_error"));
        assertTrue(layout.contains("android:accessibilityLiveRegion=\"assertive\""));
        assertTrue(layout.contains("android:minHeight=\"48dp\""));
        assertTrue(layout.contains("android:clickable=\"true\""));
    }

    @Test
    public void everyNormalOpenStartsWithFreshHistoryAndBackendSession()
            throws Exception {
        String source = composer();
        String restore = between(source, "private void restorePendingTurnState()",
                "private boolean persistChatState()");
        String persist = between(source, "private boolean persistChatState()",
                "private String backendFingerprint()");
        String store = read(path("src", "main", "java", "tk", "glucodata",
                "IntakeChatStateStore.java"));

        assertTrue(restore.contains("if (pending == null)"));
        assertTrue(restore.contains("IntakeChatStateStore.clear(activity)"));
        assertFalse(restore.contains("state.lines"));
        assertTrue(restore.contains(
                "if (pending.controlKind != CONTROL_NONE)"));
        assertTrue(persist.contains("if (retryClientTurnId.isEmpty())"));
        assertTrue(persist.contains("IntakeChatStateStore.clear(activity)"));
        assertTrue(persist.contains("Collections.emptyList()"));
        assertFalse(persist.contains("chatLines"));
        assertTrue(store.contains("STATE_SCHEMA_VERSION = 3"));
        assertTrue(store.contains("if (line == null || line.user) return false"));
        assertTrue(store.contains("audio has invalid stream metadata"));
        assertTrue(store.contains(
                "return new State(clientSessionId, sessionId, lastActionId,"));
        assertTrue(store.contains(
                "actionTurn, lastActionDeleted, lines, pending)"));
    }

    @Test
    public void actionCardHasNoEditButtonAndKeepsAccessibleDelete()
            throws Exception {
        String source = composer();
        String layout = read(path("src", "main", "res", "layout",
                "modern_meal_chat.xml"));
        String english = read(path("src", "main", "res", "values",
                "intake_v10_strings.xml"));
        String russian = read(path("src", "main", "res", "values-ru",
                "intake_v10_strings.xml"));
        String store = read(path("src", "main", "java", "tk", "glucodata",
                "IntakeChatStateStore.java"));
        String controlSuccess = between(source,
                "@Override public void onSuccess(IntakeChatTurn turn)",
                "@Override public void onError(String message)");
        String correctionGate = between(controlSuccess,
                "if (controlKind == CONTROL_REVISION",
                "prepareCorrectionInput();");
        String staleControl = between(source,
                "private boolean invalidateStaleControlTarget",
                "private void rememberActionReceipt");

        assertFalse(layout.contains("@+id/intake_chat_action_edit"));
        assertTrue(layout.contains("@+id/meal_chat_confirm"));
        assertFalse(layout.contains("@string/intake_chat_action_edit"));
        assertTrue(layout.contains("@string/intake_chat_action_delete"));
        assertTrue(occurrences(layout, "android:minHeight=\"48dp\"") >= 1);
        assertTrue(layout.contains("android:accessibilityLiveRegion=\"polite\""));
        assertTrue(source.contains("ViewCompat.setAccessibilityHeading"));
        assertTrue(source.contains("lastActionDeleted"));
        assertTrue(source.contains("STRIKE_THRU_TEXT_FLAG"));
        assertFalse(source.contains("intake_chat_action_edit_description"));
        assertTrue(source.contains("intake_chat_action_delete_description"));
        assertTrue(source.contains("invalidateStaleControlTarget"));
        assertTrue(source.contains("intake_chat_unavailable_hint"));
        assertTrue(staleControl.contains("controlKind == CONTROL_NONE"));
        assertTrue(staleControl.contains("OUTCOME_NO_CHANGE.equalsIgnoreCase"));
        assertTrue(staleControl.contains("lastActionId = \"\""));
        assertTrue(staleControl.contains("correctionMode = false"));
        int acceptedReceipt = controlSuccess.indexOf("acceptIntakeTurn(turn);");
        int refreshedCorrectionUi = controlSuccess.indexOf(
                "updateCorrectionUi();", acceptedReceipt);
        assertTrue(acceptedReceipt >= 0);
        assertTrue(refreshedCorrectionUi > acceptedReceipt);
        assertTrue(correctionGate.contains("OUTCOME_CLARIFICATION"));
        assertFalse(correctionGate.contains("OUTCOME_NO_CHANGE"));
        assertTrue(source.contains("mealInput.setHint(correctionMode"));
        assertTrue(source.contains("lastActionTurn, lastActionDeleted"));
        assertTrue(store.contains("last_action_deleted"));
        assertTrue(store.contains("last_action_turn"));
        assertTrue(english.contains(">Added<"));
        assertTrue(english.contains(">Changed<"));
        assertTrue(english.contains(">Deleted<"));
        assertTrue(russian.contains(">Добавлено<"));
        assertTrue(russian.contains(">Изменено<"));
        assertTrue(russian.contains(">Удалено<"));
        assertTrue(english.contains("intake_chat_unavailable_hint"));
        assertTrue(russian.contains("intake_chat_unavailable_hint"));
        assertFalse(english.contains("name=\"intake_chat_action_edit"));
        assertFalse(russian.contains("name=\"intake_chat_action_edit"));
        assertTrue(english.contains("Say or type what should change"));
        assertTrue(russian.contains("Скажите или напишите, что исправить"));
        assertTrue(layout.contains("android:textSize=\"13sp\""));
    }

    private static String composer() throws Exception {
        return read(path("src", "main", "java", "tk", "glucodata",
                "IntakeComposer.java"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("Missing token: " + start, from >= 0);
        assertTrue("Missing boundary: " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static Path path(String... parts) {
        Path relative = Paths.get(parts[0], java.util.Arrays.copyOfRange(
                parts, 1, parts.length));
        Path underCommon = Paths.get("Common").resolve(relative);
        return Files.exists(underCommon) ? underCommon : relative;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
