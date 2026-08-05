package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

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
import java.util.Arrays;
import java.util.List;

/** Regression contract for zoom-aware, lossless intake clusters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeEventClusterContractTest {
    @Test
    public void clusterLayoutsHaveReadableRowsAndAccessibleCloseTarget() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_cluster, null, false);
        View item = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_cluster_item, null, false);

        View close = root.findViewById(R.id.intake_event_cluster_close);
        assertNotNull(close);
        assertTrue(close.getLayoutParams().height >= dp(context, 48));
        assertNotNull(close.getContentDescription());
        assertTrue(close.getContentDescription().length() > 0);
        assertTrue(item.getMinimumHeight() >= dp(context, 48));
        assertNotNull(item.findViewById(
                R.id.intake_event_cluster_item_absorption));
    }

    @Test
    public void keyResolutionDeduplicatesAndOrdersWithoutLosingEvents()
            throws Exception {
        IntakeEvent later = event("cluster-later", 2_000L, "Cake", .92f);
        IntakeEvent first = event("cluster-first", 1_000L, "Porridge", .24f);
        IntakeRepository repository = IntakeRepository.get(
                RuntimeEnvironment.getApplication());
        int[] assigned = repository.assignRenderKeys(
                Arrays.asList(later, first));

        List<IntakeEventClusterSheet.Entry> resolved =
                IntakeEventClusterSheet.resolve(repository,
                        new int[]{assigned[0], assigned[1], assigned[0], 0});

        assertEquals(2, resolved.size());
        assertSame(first, resolved.get(0).event);
        assertSame(later, resolved.get(1).event);
        assertEquals(assigned[1], resolved.get(0).renderKey);
        assertEquals(assigned[0], resolved.get(1).renderKey);
    }

    @Test
    public void nativeAndGestureContractsReturnTheWholeCluster()
            throws Exception {
        String natives = javaSource("Natives.java");
        String graph = javaSource("GlucoseCurve.java");
        String main = javaSource("MainActivity.java");
        String bridge = cppSource("javacurve.cpp");
        String renderer = cppSource("curve.cpp");
        String helper = cppSource("intakemarkers.hpp");

        assertTrue(natives.contains("int[] timelineEventsAt(float x,float y)"));
        assertTrue(bridge.contains("fromjava(timelineEventsAt)"));
        assertTrue(bridge.contains("intakeTimelineEventsAt(x,y)"));
        assertTrue(graph.contains("eventKeys.length>1"));
        assertTrue(graph.contains("showIntakeEventCluster(eventKeys)"));
        assertTrue(main.contains("IntakeEventClusterSheet.resolve"));
        assertTrue(renderer.contains("std::vector<std::int32_t> keys"));
        assertTrue(renderer.contains("hits.push_back({keys"));
        assertTrue(renderer.contains("clusterMaximumSpan"));
        assertTrue(renderer.contains("visualCollisionDistance"));
        assertTrue(renderer.contains("intakemarkers::joins_cluster"));
        assertTrue(renderer.contains(
                "renderRevision==intakeTimelineRevision"));
        assertTrue(helper.contains("nextX-firstX<=maximumSpan"));
    }

    @Test
    public void clusterRowIncludesAbsorptionAndRoutesToExistingDetails()
            throws Exception {
        String sheet = javaSource("IntakeEventClusterSheet.java");
        assertTrue(sheet.contains("event.hasAbsorptionSpeed()"));
        assertTrue(sheet.contains("CarbAbsorptionUi.compact"));
        assertTrue(sheet.contains("activity.showIntakeEvent(renderKey)"));
        assertTrue(sheet.contains(".comparingLong"));
        assertTrue(sheet.contains(
                "DateFormat.getDateTimeInstance(DateFormat.MEDIUM"));
        assertTrue(sheet.contains(
                "DateFormat.MEDIUM, Locale.getDefault()"));
        assertTrue(sheet.contains("void onEventDetailsClosed()"));
        String open = between(sheet, "private void openDetails",
                "/** Refreshes/removes");
        assertFalse(open.contains("close(true)"));
    }

    private static IntakeEvent event(String id, long time, String meal,
            float absorption) throws Exception {
        return IntakeEvent.fromJson(new JSONObject()
                .put("id", id)
                .put("occurred_at_ms", time)
                .put("meal_text", meal)
                .put("carbs_g", 20.0)
                .put("absorption_speed", absorption));
    }

    private static String javaSource(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String cppSource(String name) throws Exception {
        Path path = Paths.get("src", "main", "cpp", "curve", name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertTrue(first >= 0 && last > first);
        return value.substring(first, last);
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }
}
