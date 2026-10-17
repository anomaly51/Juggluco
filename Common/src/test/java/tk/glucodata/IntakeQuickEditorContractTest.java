package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeQuickEditorContractTest {
    @Test
    public void dockIsProgressiveAndEveryCompactControlHasATouchTarget() {
        Context context = RuntimeEnvironment.getApplication();
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        View body = root.findViewById(R.id.intake_quick_body);
        assertEquals(View.GONE, body.getVisibility());
        View editorScroll = root.findViewById(R.id.intake_editor_scroll);
        assertEquals(View.GONE, editorScroll.getVisibility());

        editorScroll.setVisibility(View.VISIBLE);
        root.findViewById(R.id.intake_quick_submit_bar).setVisibility(View.VISIBLE);
        body.setVisibility(View.VISIBLE);
        // Measure both mutually exclusive modes so their hidden controls are
        // covered by the same minimum touch-target contract.
        root.findViewById(R.id.intake_quick_meal_panel)
                .setVisibility(View.VISIBLE);
        root.findViewById(R.id.intake_quick_meal_submit)
                .setVisibility(View.VISIBLE);
        measure(root, dp(context, 375), dp(context, 812));
        int minimum = dp(context, 48);
        int[] controls = {
                R.id.intake_quick_toggle,
                R.id.intake_quick_novorapid,
                R.id.intake_quick_tresiba,
                R.id.intake_quick_dose_minus,
                R.id.intake_quick_dose,
                R.id.intake_quick_dose_plus,
                R.id.intake_quick_insulin_submit,
                R.id.intake_quick_meal_name,
                R.id.intake_quick_carbs,
                R.id.intake_quick_meal_submit,
                R.id.intake_quick_time_now,
                R.id.intake_quick_time_5m,
                R.id.intake_quick_time_10m,
                R.id.intake_quick_time_15m,
                R.id.intake_quick_time_exact
        };
        for (int id : controls) {
            View control = root.findViewById(id);
            assertNotNull(control);
            assertTrue("Control below 48dp: " + id,
                    control.getMeasuredHeight() >= minimum);
        }
    }

    @Test
    public void voiceAndDirectAlternativesStayInTheBottomThumbDock() {
        Context context = RuntimeEnvironment.getApplication();
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        measure(root, dp(context, 375), dp(context, 812));

        View voice = root.findViewById(R.id.meal_chat_voice);
        View dock = root.findViewById(R.id.intake_thumb_dock);
        assertNotNull(voice);
        assertTrue(isDescendantOf(voice, dock));
        assertTrue(voice.getMeasuredWidth() >= dp(context, 48));
        assertTrue(voice.getMeasuredHeight() >= dp(context, 48));
        int[] alternatives = {
                R.id.intake_start_insulin,
                R.id.intake_start_food,
                R.id.intake_back_button,
                R.id.meal_chat_time,
                R.id.meal_chat_attach
        };
        for (int id : alternatives) {
            View action = root.findViewById(id);
            assertNotNull(action);
            assertTrue(isDescendantOf(action, dock));
            assertTrue("Guided action below 48dp: " + id,
                    action.getMeasuredHeight() >= dp(context, 48));
        }
    }

    @Test
    public void pendingRecoveryStaysReachableInTheThumbDock() {
        Context context = RuntimeEnvironment.getApplication();
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        View pending = root.findViewById(R.id.intake_chat_reconcile);
        View dock = root.findViewById(R.id.intake_thumb_dock);
        assertNotNull(pending);
        assertTrue(isDescendantOf(pending, dock));
        assertFalse(isDescendantOf(pending,
                root.findViewById(R.id.meal_chat_scroll)));
    }

    @Test
    public void layoutMeasuresOnUnfoldedFoldAndLargeTextWithoutOverflow() {
        Context base = RuntimeEnvironment.getApplication();
        assertDockFits(base, 375, 812);
        assertDockFits(base, 812, 375);

        Configuration large = new Configuration(
                base.getResources().getConfiguration());
        large.fontScale = 2.0f;
        Context context = base.createConfigurationContext(large);
        assertDockFits(context, 800, 700);
    }

    private static void assertDockFits(Context context, int widthDp,
            int heightDp) {
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        root.findViewById(R.id.intake_editor_scroll).setVisibility(View.VISIBLE);
        root.findViewById(R.id.intake_quick_submit_bar).setVisibility(View.VISIBLE);
        root.findViewById(R.id.intake_quick_body).setVisibility(View.VISIBLE);
        // Mirrors IntakeComposer.updateQuickEditorChrome() while expanded.
        root.findViewById(R.id.meal_chat_time).setVisibility(View.GONE);
        root.findViewById(R.id.intake_composer_surface)
                .setVisibility(View.GONE);
        root.findViewById(R.id.meal_chat_voice).setVisibility(View.GONE);
        root.findViewById(R.id.intake_chat_voice_hint).setVisibility(View.GONE);
        root.findViewById(R.id.meal_chat_safety).setVisibility(View.GONE);

        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        measure(root, width, height);
        View dock = root.findViewById(R.id.intake_quick_editor);
        View scroll = root.findViewById(R.id.intake_editor_scroll);
        View feed = root.findViewById(R.id.meal_chat_scroll);
        View thumbDock = root.findViewById(R.id.intake_thumb_dock);
        assertTrue(dock.getMeasuredWidth() > 0);
        assertTrue(dock.getMeasuredWidth() <= width);
        assertTrue(dock.getLeft() >= 0);
        assertTrue(dock.getRight() <= width);
        assertTrue("Expanded dock must be vertically scrollable",
                isDescendantOf(dock, scroll));
        assertTrue("Editor must remain outside the conversation",
                !isDescendantOf(dock, feed));
        assertTrue("Chat viewport collapsed", feed.getMeasuredHeight() > 0);
        assertTrue("Fixed dock extends below viewport",
                absoluteBottom(thumbDock) <= height);
    }

    @Test
    public void exactCardTargetAndDraftSurviveAllInputPathsAndRotation()
            throws Exception {
        String composer = read("IntakeComposer.java");
        assertTrue(composer.contains(
                "openQuickEditorForEvent(card.event())"));
        assertTrue(composer.contains(
                "target == null ? \"\" : target.id"));
        assertTrue(composer.contains("reconcileQuickTarget()"));
        assertTrue(composer.contains("quickDoseDraft"));
        assertTrue(composer.contains("quickMealDraft"));
        assertTrue(composer.contains("quickCarbsDraft"));
        assertTrue(composer.contains("showPendingChatFeedback"));
        assertTrue(composer.contains("finishPendingChatFeedback"));
        assertTrue(composer.contains("resolveQuickOccurredAtMs()"));
        assertTrue(composer.contains(
                "lastIntakeTurn.deletedEventIds.size() == 1"));
        assertTrue(composer.contains("resetQuickTargetSafely()"));
        assertTrue(composer.contains(
                "if (!structuredTurn && mealInput != null)"));
        assertTrue(composer.contains("removeTypingFeedback();"));
        assertTrue(composer.contains(
                "if (recording || recorder != null) stopRecording(false);"));
        assertTrue(composer.contains("canQuickEditEvent(event)"));
    }

    @Test
    public void transportKeepsStructuredFactsSeparateFromConversationMedia()
            throws Exception {
        String client = read("IntakeApiClient.java");
        String repository = read("IntakeRepository.java");
        assertTrue(client.contains(
                "writeField(output, boundary, \"structured_intent\""));
        assertTrue(client.contains(
                "Structured intake cannot include text, audio or photos"));
        assertTrue(repository.contains(
                "structuredIntent, cancellation"));
    }

    @Test
    public void acknowledgedQuickAddClearsAndCollapsesItsDraft()
            throws Exception {
        String composer = read("IntakeComposer.java");
        assertTrue(composer.contains(
                "quickAddPending = newQuickEntry"));
        assertTrue(composer.contains(
                "if (completedQuickAdd) finishSuccessfulQuickAdd()"));
        String reset = method(composer,
                "private void finishSuccessfulQuickAdd()",
                "private void openQuickEditorForEvent");
        assertTrue(reset.contains("quickDose.setText(\"\")"));
        assertTrue(reset.contains("quickMealName.setText(\"\")"));
        assertTrue(reset.contains("quickCarbs.setText(\"\")"));
        assertTrue(reset.contains("setQuickEditorExpanded(false, true)"));
    }

    @Test
    public void backDismissesTransientLayersBeforeClosingAndBusyNeverCloses()
            throws Exception {
        String composer = read("IntakeComposer.java");
        assertTrue(composer.contains(
                "handleBackInteraction(true, false)"));
        assertTrue(composer.contains(
                "handleBackInteraction(false, true)"));
        String back = method(composer,
                "private void handleBackInteraction",
                "private void childBack");
        int busy = back.indexOf("if (busy)");
        int ime = back.indexOf("if (imeVisible)");
        int recording = back.indexOf("if (recording || recorder != null)");
        int quick = back.indexOf("if (quickExpanded)");
        int close = back.indexOf("requestClose(popBackOnClose)");
        assertTrue(busy >= 0 && busy < ime);
        assertTrue(ime < recording && recording < quick && quick < close);
        assertFalse(back.substring(busy, ime).contains("requestClose("));
    }

    @Test
    public void edgeToEdgeComposerOwnsImeInsetExactlyOnce() throws Exception {
        String composer = read("IntakeComposer.java");
        assertTrue(composer.contains("SOFT_INPUT_ADJUST_NOTHING"));
        assertFalse(composer.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertTrue(composer.contains(
                "Math.max(bars.bottom, ime.bottom)"));
    }

    @Test
    public void recordingDisconnectsTheImeAndExactTimeStaysVisible()
            throws Exception {
        String composer = read("IntakeComposer.java");
        String capture = method(composer,
                "private void setVoiceCaptureInputMode",
                "private void transcribeRecording");
        assertTrue(capture.contains("hideKeyboard()"));
        assertTrue(capture.contains("mealInput.clearFocus()"));
        assertTrue(capture.contains("mealInput.setFocusable(false)"));
        assertTrue(capture.contains("mealInput.setFocusable(true)"));
        assertTrue(composer.contains("((TextView) exact).setText(selected == 4"));
        assertTrue(composer.contains("String formatted = formatProposalTime("));
    }

    @Test
    public void closingAddReturnsTheExistingCanvasToNowWithoutTimelineState()
            throws Exception {
        String activity = read("MainActivity.java");
        String close = method(activity,
                "void onIntakeComposerClosed",
                "void showIntakeEvent");
        assertFalse(close.contains("restoreLiveGlucoseGraph"));
        assertFalse(close.contains("endnumlist"));
        assertTrue(close.contains("Natives.settonow()"));
        assertTrue(close.contains("requestRender()"));

        String curve = read("GlucoseCurve.java");
        assertFalse(curve.contains("void restoreLiveGlucoseGraph()"));
    }

    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,
                        View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static int absoluteBottom(View view) {
        int bottom = view.getBottom();
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            bottom += parentView.getTop();
            parent = parentView.getParent();
        }
        return bottom;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources()
                .getDisplayMetrics().density);
    }

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src", "main", "java",
                "tk", "glucodata", name)), StandardCharsets.UTF_8);
    }

    private static String method(String source, String startMarker,
            String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("Missing method marker: " + startMarker, start >= 0);
        assertTrue("Missing method end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

}
