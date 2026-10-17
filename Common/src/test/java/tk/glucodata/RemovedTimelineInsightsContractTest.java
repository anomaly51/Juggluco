package tk.glucodata;

import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards removal of the former Timeline and Insights top-level features. */
public class RemovedTimelineInsightsContractTest {
    @Test
    public void dashboardAndMoreExposeNoRemovedDestination() throws Exception {
        String dashboard = source("main", "java", "tk", "glucodata",
                "DashboardChrome.java");
        String dashboardLayout = source("main", "res", "layout",
                "modern_dashboard_chrome.xml");
        String menu = source("mobile", "java", "tk", "glucodata",
                "Menus.java");
        String menuLayout = source("mobile", "res", "layout", "menus.xml");

        for (String removed : new String[]{
                "modern_dashboard_records", "modern_dashboard_statistics",
                "dashboard_timeline", "dashboard_insights",
                "showRecords()", "showStatistics()"
        }) {
            assertFalse("Stale dashboard destination: " + removed,
                    dashboard.contains(removed));
            assertFalse("Stale dashboard layout destination: " + removed,
                    dashboardLayout.contains(removed));
        }
        assertFalse(menu.contains("findViewById(R.id.list)"));
        assertFalse(menu.contains("findViewById(R.id.statistics)"));
        assertFalse(menu.contains("Stats.mkstats"));
        assertFalse(menu.contains("Natives.makepercentages"));
        assertFalse(menuLayout.contains("@+id/list"));
        assertFalse(menuLayout.contains("@+id/statistics"));
    }

    @Test
    public void settingsAndConfigurationHaveNoRemovedRoute() throws Exception {
        String main = source("main", "java", "tk", "glucodata",
                "MainActivity.java");
        String curve = source("main", "java", "tk", "glucodata",
                "GlucoseCurve.java");
        String routes = source("main", "java", "tk", "glucodata",
                "LegacySettingsRoutes.java");
        String settings = source("main", "java", "tk", "glucodata",
                "settings", "Settings.java");

        assertFalse(main.contains("keepTimeline"));
        assertFalse(main.contains("onRecordsTimelineConfigurationChanged"));
        assertFalse(curve.contains("showRecordsTimeline"));
        assertFalse(curve.contains("getnumcontrol"));
        assertFalse(curve.contains("Stats.mkstats"));
        assertFalse(routes.contains("showList"));
        assertFalse(routes.contains("showStatistics"));
        assertFalse(routes.contains("Stats.mkstats"));
        assertFalse(settings.contains("LegacySettingsRoutes.showList"));
        assertFalse(settings.contains("LegacySettingsRoutes.showStatistics"));
    }

    @Test
    public void dedicatedPhoneImplementationsAndResourcesAreDeleted()
            throws Exception {
        assertFalse(Files.exists(path("main", "java", "tk", "glucodata",
                "RecordsTimelineUi.java")));
        assertFalse(Files.exists(path("mobile", "java", "tk", "glucodata",
                "Stats.java")));
        assertFalse(Files.exists(path("wear", "java", "tk", "glucodata",
                "Stats.java")));
        assertFalse(Files.exists(path("small", "java", "tk", "glucodata",
                "Stats.java")));
        assertFalse(Files.exists(path("main", "res", "values",
                "records_timeline_ids.xml")));
        assertFalse(Files.exists(path("main", "res", "drawable",
                "modern_dashboard_records.xml")));
        assertFalse(Files.exists(path("main", "res", "drawable",
                "modern_dashboard_statistics.xml")));

        String english = optionalSource("main", "res", "values",
                "add_records_strings.xml");
        String russian = optionalSource("main", "res", "values-ru",
                "add_records_strings.xml");
        for (String removed : new String[]{
                "records_subtitle", "records_add", "records_add_description",
                "records_search_description", "records_first",
                "records_first_description", "records_previous",
                "records_previous_description", "records_next",
                "records_next_description", "records_latest",
                "records_latest_description", "records_latest_active",
                "records_latest_active_description", "records_earlier",
                "records_earlier_description", "records_later",
                "records_later_description", "records_oldest",
                "records_range_description",
                "records_state_latest", "records_state_browsing",
                "records_state_oldest", "records_period_through",
                "records_period_empty", "records_empty_title",
                "records_empty_subtitle", "records_timeline_context",
                "records_timeline_context_accessibility"
        }) {
            assertFalse("Stale EN Timeline string: " + removed,
                    english.contains("name=\"" + removed + "\""));
            assertFalse("Stale RU Timeline string: " + removed,
                    russian.contains("name=\"" + removed + "\""));
        }
    }

    @Test
    public void removedScreensHaveNoJavaOrJniEntryPoints() throws Exception {
        String natives = source("main", "java", "tk", "glucodata",
                "Natives.java");
        String javaCurveBridge = source("main", "cpp", "curve",
                "javacurve.cpp");
        String numberListBridge = source("main", "cpp", "curve",
                "shownums.cpp");

        for (String removed : new String[]{
                "makenumbers", "makepercentages", "numcontrol",
                "endnumlist", "forwardnumlist", "backwardnumlist",
                "firstpage", "lastpage", "numpagebounds",
                "setnumlistemptytext", "summarygraph", "analysedays",
                "getAnalysedays", "getAnalysehistory", "endstats",
                "percentileEndtime"
        }) {
            assertFalse("Stale Java native entry point: " + removed,
                    natives.contains("native ") && natives.contains(removed + "("));
            assertFalse("Stale JNI curve entry point: " + removed,
                    javaCurveBridge.contains("fromjava(" + removed + ")"));
            assertFalse("Stale JNI list entry point: " + removed,
                    numberListBridge.contains("fromjava(" + removed + ")"));
        }

        for (String removed : new String[]{
                "setTimelineEvents", "timelineEventAt", "timelineEventsAt"
        }) {
            assertFalse("Stale Timeline graph bridge: " + removed,
                    natives.contains(removed + "("));
            assertFalse("Stale Timeline JNI bridge: " + removed,
                    javaCurveBridge.contains("fromjava(" + removed + ")"));
        }
    }

    @Test
    public void activeCopyDoesNotPointToRemovedScreens() throws Exception {
        String intake = source("main", "res", "values",
                "intake_v10_strings.xml");
        String settings = source("main", "res", "values",
                "settings_child_strings.xml");

        assertFalse(intake.contains("Add to timeline"));
        assertFalse(intake.contains("Check the timeline"));
        assertFalse(settings.contains("Timeline, statistics"));
    }

    private static String source(String... relative) throws Exception {
        return new String(Files.readAllBytes(path(relative)),
                StandardCharsets.UTF_8);
    }

    private static String optionalSource(String... relative) throws Exception {
        Path path = path(relative);
        return Files.exists(path) ? new String(Files.readAllBytes(path),
                StandardCharsets.UTF_8) : "";
    }

    private static Path path(String... relative) {
        Path path = Paths.get("src", relative);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return path;
    }
}
