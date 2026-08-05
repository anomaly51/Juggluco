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
        assertTrue(settings.contains("settings_graph_readings_section"));
        assertTrue(settings.contains("settings_graph_records_section"));
        assertTrue(settings.contains("settings_graph_presentation_section"));
        for(String handler:handlers)
            assertTrue("Graph display lost handler " + handler,settings.contains(handler));
    }

    @Test
    public void moreHasNoAddTimelineOrGraphPreferenceViews() throws Exception {
        String menu=source("src/mobile/res/layout/menus.xml");
        String[] removedIds={
                "newamount",
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
    public void phoneMoreHidesLegacyDestinationsButKeepsTheirBindings() throws Exception {
        String menu=source("src/mobile/res/layout/menus.xml");
        String menuSource=source("src/mobile/java/tk/glucodata/Menus.java");
        String[] hiddenIds={
                "list","statistics","lastscan","watch","talk","mirror","export","close"
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
        assertTrue(menuSource.contains("BuildConfig.SiBionics==1"));
        assertTrue(menuSource.contains("PhotoScan.scan(act,REQUEST_BARCODE)"));
        assertTrue(menuSource.contains("aboutview.setVisibility(View.GONE)"));
        assertFalse(menuSource.contains("c.doabout(act)"));
    }

    @Test
    public void primaryChromeKeepsOneBackendFirstAddAction() throws Exception {
        String dashboardLayout=source("src/main/res/layout/modern_dashboard_chrome.xml");
        String dashboardSource=source("src/main/java/tk/glucodata/DashboardChrome.java");
        assertFalse(dashboardLayout.contains("@+id/modern_dashboard_add\""));
        assertFalse(dashboardSource.contains("void addRecord()"));
        assertTrue(dashboardSource.contains("R.id.modern_dashboard_overview"));
        assertTrue(dashboardLayout.contains("@+id/modern_dashboard_add_intake"));
        assertTrue(dashboardSource.contains("activity.showIntakeComposer()"));
        assertTrue(dashboardSource.contains("R.id.modern_dashboard_menu"));
    }

    private static String source(String relative) throws IOException {
        Path path=Paths.get(relative);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
