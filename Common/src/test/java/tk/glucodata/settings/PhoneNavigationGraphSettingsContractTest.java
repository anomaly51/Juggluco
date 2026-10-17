package tk.glucodata.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the phone-only More, dashboard and Graph display navigation contract. */
public class PhoneNavigationGraphSettingsContractTest {
    @Test
    public void graphDisplayOwnsAllElevenFormerMorePreferences() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        String[] handlers={
                "Natives.setshowscans(isChecked)",
                "Natives.setshowcalibratedscans(isChecked)",
                "Natives.setshowstream(isChecked)",
                "Natives.setshowcalibratedstream(isChecked)",
                "Natives.setshowhistories(isChecked)",
                "Natives.setshowcalibratedhistories(isChecked)",
                "Natives.setshownumbers(isChecked)",
                "Natives.setshowmeals(isChecked)",
                "Floating.setfloatglucose(context,isChecked)",
                "Natives.setInvertColors(isChecked)",
                "Natives.setsystemui(isChecked)"
        };
        assertTrue(settings.contains("PHONE_GRAPH_DISPLAY_TOGGLE_COUNT=11"));
        assertTrue(settings.contains("settings_graph_data_disclosure"));
        assertTrue(settings.contains("settings_ranges_disclosure_summary"));
        assertTrue(settings.contains("settings_chart_disclosure_summary"));
        assertTrue(settings.contains("settings_appearance_disclosure_summary"));
        assertTrue(settings.contains("clinicalDisclosure(context"));
        for(String handler:handlers)
            assertTrue("Graph display lost handler " + handler,settings.contains(handler));
    }

    @Test
    public void moreHasNoAddTimelineOrGraphPreferenceViews() throws Exception {
        String menu=source("src/mobile/res/layout/menus.xml");
        String[] removedIds={
                "newamount",
                "list","statistics",
                "now","search","date","dayback","daylater","weekback","weeklater",
                "scans","calibratedscans","stream","calibratedstream","history",
                "calibratedhistory","amounts","meals","glucosefloat","darkmode","systemui"
        };
        for(String id:removedIds)
            assertFalse("Removed More view remains: " + id,
                    menu.contains("@+id/" + id));

        String menuSource=source("src/mobile/java/tk/glucodata/Menus.java");
        assertFalse(menuSource.contains("addnumberview"));
        assertFalse(menuSource.contains("startdatepick"));
        assertFalse(menuSource.contains("startsearch"));
        assertFalse(menuSource.contains("Natives.prevday"));
        assertFalse(menuSource.contains("Natives.nextday"));
    }

    @Test
    public void phoneMoreKeepsOnlyTheRemainingLegacyBindingsHidden()
            throws Exception {
        String menu=source("src/mobile/res/layout/menus.xml");
        String menuSource=source("src/mobile/java/tk/glucodata/Menus.java");
        String[] hiddenIds={
                "lastscan","watch","talk","mirror","export","close"
        };
        for(String id:hiddenIds) {
            int start=menu.indexOf("android:id=\"@+id/" + id + "\"");
            assertTrue("Legacy handler ID disappeared: " + id,start>=0);
            int end=menu.indexOf("/>",start);
            assertTrue("Legacy phone More action is not gone: " + id,
                    end>start && menu.substring(start,end).contains("android:visibility=\"gone\""));
            assertTrue("Legacy binding disappeared: " + id,
                    menuSource.contains("findViewById(R.id." + id + ")"));
        }
        assertFalse(menuSource.contains("findViewById(R.id.list)"));
        assertFalse(menuSource.contains("findViewById(R.id.statistics)"));
        assertFalse(menuSource.contains("Stats.mkstats"));
        assertFalse(menuSource.contains("Natives.makepercentages"));
        assertTrue(menuSource.contains("BuildConfig.SiBionics==1"));
        assertTrue(menuSource.contains("PhotoScan.scan(act,REQUEST_BARCODE)"));
        assertTrue(menuSource.contains("aboutview.setVisibility(View.GONE)"));
        assertFalse(menuSource.contains("c.doabout(act)"));
    }

    @Test
    public void moreAlwaysUsesAnEndSidePanelAndOpensAlertsDirectly()
            throws Exception {
        String menu=source("src/mobile/res/layout/menus.xml");
        String menuSource=source("src/mobile/java/tk/glucodata/Menus.java");
        assertTrue(menu.contains("@+id/alerts"));
        assertTrue(menu.contains("android:layout_gravity=\"end\""));
        assertTrue(menu.contains("android:layout_height=\"match_parent\""));
        assertFalse(menu.contains("@+id/modern_menu_sheet_handle"));
        assertTrue(menuSource.contains("Gravity.END"));
        assertFalse(menuSource.contains("availableDp<600"));
        assertFalse(menuSource.contains("bottomSheet"));
        assertFalse(menuSource.contains("Gravity.BOTTOM"));
        assertFalse(menuSource.contains("modern_menu_bottom_sheet"));
        assertTrue(menuSource.contains("Settings.showGlucoseAlerts(act"));
    }

    @Test
    public void primaryChromeKeepsOneBackendFirstAddAction() throws Exception {
        String dashboardLayout=source("src/main/res/layout/modern_dashboard_chrome.xml");
        String dashboardSource=source("src/main/java/tk/glucodata/DashboardChrome.java");
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_add\""));
        assertFalse(dashboardSource.contains("void addRecord()"));
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_overview"));
        assertFalse(dashboardSource.contains("R.id.modern_dashboard_overview"));
        assertTrue(dashboardSource.contains("DESTINATION_HOME"));
        assertTrue(dashboardSource.contains("DESTINATION_MORE"));
        assertTrue(dashboardLayout.contains("@+id/modern_dashboard_add_intake"));
        assertTrue(dashboardSource.contains("activity.showIntakeComposer()"));
        assertTrue(dashboardSource.contains("R.id.modern_dashboard_menu"));
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_records"));
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_statistics"));
        assertFalse(dashboardSource.contains("R.id.modern_dashboard_records"));
        assertFalse(dashboardSource.contains("R.id.modern_dashboard_statistics"));
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_sensor"));
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_forecast"));
        assertFalse(dashboardLayout.contains("modern_dashboard_target_band"));
    }

    @Test
    public void forecastDetailsMovedFromHomeIntoGlucoseSettings()
            throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        String mainActivity=source("src/main/java/tk/glucodata/MainActivity.java");
        assertTrue(settings.contains("R.string.settings_forecast_title"));
        assertTrue(settings.contains("phoneSettingsPageSection(context,R.string.settings_forecast_section)"));
        assertTrue(settings.contains("phoneSettingsGroup(context,forecastAction)"));
        assertTrue(settings.contains("context.showForecastDetails()"));
        assertTrue(mainActivity.contains("public void showForecastDetails()"));
        assertTrue(mainActivity.contains("PredictiveAlertNotifier.EXTRA_OPEN_FORECAST"));
    }

    private static String source(String relative) throws IOException {
        Path path=Paths.get(relative);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
