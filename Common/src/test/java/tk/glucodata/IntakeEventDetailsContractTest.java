package tk.glucodata;

import static android.view.View.GONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the modern marker-details and backend-owned deletion flow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeEventDetailsContractTest {
    @Test
    public void detailSheetStartsSafeAndHasAccessibleTouchTargets() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_details, null, false);

        View confirmation = root.findViewById(
                R.id.intake_event_delete_confirmation);
        View progress = root.findViewById(
                R.id.intake_event_delete_progress);
        assertEquals(GONE, confirmation.getVisibility());
        assertEquals(GONE, progress.getVisibility());

        assertTouchTarget(context, root.findViewById(
                R.id.intake_event_details_close));
        assertTouchTarget(context, root.findViewById(
                R.id.intake_event_details_delete));
        assertTouchTarget(context, root.findViewById(
                R.id.intake_event_delete_cancel));
        assertTouchTarget(context, root.findViewById(
                R.id.intake_event_delete_confirm));

        assertHasAccessibleLabel(root.findViewById(
                R.id.intake_event_details_close));
        assertHasAccessibleLabel(root.findViewById(
                R.id.intake_event_details_delete));
        assertHasAccessibleLabel(root.findViewById(
                R.id.intake_event_delete_cancel));
        assertHasAccessibleLabel(root.findViewById(
                R.id.intake_event_delete_confirm));
        assertHasAccessibleLabel(progress);
    }

    @Test
    public void markerClassificationRecognizesConfiguredInsulins() {
        IntakeEvent novoRapid = new IntakeEvent("rapid-id", 1L, "", 0f,
                "manual", 3.5f, "rapid", "NovoRapid", "", 0f);
        IntakeEvent tresiba = new IntakeEvent("long-id", 1L, "", 0f,
                "manual", 12f, "long", "Tresiba", "", 0f);

        assertTrue(IntakeEventDetailsSheet.isRapidInsulin(novoRapid));
        assertFalse(IntakeEventDetailsSheet.isLongInsulin(novoRapid));
        assertTrue(IntakeEventDetailsSheet.isLongInsulin(tresiba));
        assertFalse(IntakeEventDetailsSheet.isRapidInsulin(tresiba));
        assertEquals("12", IntakeEventDetailsSheet.formatNumber(12f));
    }

    @Test
    public void deletionUsesTheCanonicalBackendAndRefreshChain()
            throws Exception {
        String client = source("IntakeApiClient.java");
        String repository = source("IntakeRepository.java");
        String main = source("MainActivity.java");
        String dashboard = source("DashboardChrome.java");
        String sheet = source("IntakeEventDetailsSheet.java");

        String transport = between(client, "JSONObject deleteIntake",
                "private JSONObject requestJson");
        assertTrue(transport.contains("URLEncoder.encode"));
        assertTrue(transport.contains(
                "requestJson(\"DELETE\", \"/v1/intakes/\" + encoded"));
        assertFalse(transport.contains("/intake-events"));

        String deletion = between(repository, "void deleteEvent",
                "private void mergeConfirmedEvent");
        assertTrue(deletion.contains("api.deleteIntake(eventId)"));
        assertTrue(deletion.contains("removeConfirmedEvent(eventId)"));
        assertTrue(repository.contains("replaceEvents(remaining)"));

        String markerOpen = between(main, "void showIntakeEvent",
                "void onIntakeEventDetailsClosed");
        assertTrue(markerOpen.contains("new IntakeEventDetailsSheet"));
        assertFalse(markerOpen.contains("AlertDialog"));
        assertFalse(markerOpen.contains("NumberView"));

        String listener = between(dashboard, "intakeListener = events ->",
                "forecastListener = state ->");
        assertTrue(listener.contains("pushIntakeEventsToGraph(events)"));
        assertTrue(listener.contains(
                "forecastRepository.refreshAfterConfirmedIntake()"));
        assertTrue(listener.contains("activity.requestRender()"));

        String deleteUi = between(sheet, "private void deleteConfirmed()",
                "private void setBusy");
        assertTrue(deleteUi.contains("repository.deleteEvent(event"));
        assertTrue(deleteUi.contains("error.setVisibility(VISIBLE)"));
        assertFalse("Raw backend errors must not be rendered to the user",
                deleteUi.contains("error.setText(message)"));
    }

    private static void assertTouchTarget(Context context, View view) {
        assertNotNull(view);
        int declared = view.getLayoutParams() == null ? 0
                : view.getLayoutParams().height;
        assertTrue(Math.max(view.getMinimumHeight(), declared)
                >= dp(context, 48));
    }

    private static void assertHasAccessibleLabel(View view) {
        assertNotNull(view);
        CharSequence label = view.getContentDescription();
        if ((label == null || label.length() == 0)
                && view instanceof TextView) {
            label = ((TextView) view).getText();
        }
        assertNotNull(label);
        assertTrue(label.length() > 0);
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertTrue(first >= 0 && last > first);
        return value.substring(first, last);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }
}
