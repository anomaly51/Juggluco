package tk.glucodata.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the phone Settings information architecture requested for v8. */
public class SettingsLegacyNavigationContractTest {
    @Test
    public void legacyChildKeepsAllNineOriginalDestinationsReachable() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        assertTrue(settings.contains("PHONE_LEGACY_ACTION_COUNT=9"));
        assertTrue(settings.contains("private static void clinicalLegacySettings"));
        String[] handlers={
                "LegacySettingsRoutes.showList(context,dismissSettings)",
                "LegacySettingsRoutes.showStatistics(context,dismissSettings)",
                "LegacySettingsRoutes.showLastScan(context,dismissSettings)",
                "LegacySettingsRoutes.showWatch(context)",
                "tk.glucodata.Talker.config(context,false)",
                "curve.dialogs.showexport(context,curve.getWidth(),curve.getHeight(),screen)",
                "tk.glucodata.FloatingConfig.show(context,screen)",
                "new tk.glucodata.setNumAlarm().mkviews(context,screen)",
                "new LabelsClass(context).mklabellayout(screen)"
        };
        for(String handler:handlers)
            assertTrue("Legacy lost handler " + handler,settings.contains(handler));
    }

    @Test
    public void graphModesDismissBothSettingsLayersBeforeOpening() throws Exception {
        String routes=source("src/main/java/tk/glucodata/LegacySettingsRoutes.java");
        assertTrue(routes.indexOf("dismissSettings.run();")<
                routes.indexOf("Natives.makenumbers();"));
        assertTrue(routes.indexOf("dismissSettings.run();",
                routes.indexOf("showStatistics"))<routes.indexOf("Stats.mkstats(activity);"));
        assertTrue(routes.indexOf("dismissSettings.run();",
                routes.indexOf("showLastScan"))<routes.lastIndexOf("activity.requestRender();"));

        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        String dismiss=settings.substring(settings.indexOf("Runnable dismissSettings"),
                settings.indexOf("list.setOnClickListener",settings.indexOf("Runnable dismissSettings")));
        assertTrue(dismiss.contains("context.poponback();"));
        assertTrue(dismiss.contains("closeLegacy.run();"));
        assertTrue(dismiss.contains("settings.finish();"));
        assertTrue(routes.contains("Watch.show(activity,false)"));
    }

    @Test
    public void rootKeepsDailyControlsAndMovesSpecialistToolsOut() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        int start=settings.indexOf("Button mirror=getbutton(context,R.string.mirror)");
        int end=settings.indexOf("views=null;",start);
        String phoneRoot=settings.substring(start,end);
        assertTrue(phoneRoot.contains("intakeBackend,exchanges,mirror"));
        assertTrue(phoneRoot.contains("IntakeBackendSettings.show(context)"));
        assertTrue(phoneRoot.contains("phoneSettingsGroup(context,legacy)"));
        assertTrue(phoneRoot.contains("phoneSettingsGroup(context,alarmbut)"));
        assertTrue(phoneRoot.contains("phoneSettingsGroup(context,logview)"));
        assertFalse(phoneRoot.contains("supportGroup"));
        assertFalse(phoneRoot.contains("phoneSettingsGroup(context,help"));
        assertFalse(phoneRoot.contains("preferenceRows.add(changelabels)"));
        assertFalse(phoneRoot.contains("floatconfig"));
        assertFalse(phoneRoot.contains("alarmbut,numalarm"));
    }

    @Test
    public void mirrorAndExportAreNoLongerDuplicatedInsideExchangeData() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        String exchanges=settings.substring(settings.indexOf("private static void clinicalExchanges"),
                settings.indexOf("static private void exchanges"));
        assertFalse(exchanges.contains("LinearLayout export="));
        assertFalse(exchanges.contains("LinearLayout mirror="));
        assertTrue(exchanges.contains("ClinicalUi.card(context,meters)"));
    }

    @Test
    public void englishAndRussianLabelsDescribeTheNewSections() throws Exception {
        String english=source("src/main/res/values/settings_child_strings.xml");
        String russian=source("src/main/res/values-ru/settings_child_strings.xml");
        for(String key:new String[]{"settings_section_connections","settings_section_technical",
                "settings_legacy_title","settings_legacy_list_hint",
                "settings_legacy_statistics_hint","settings_legacy_last_scan_hint",
                "settings_legacy_watch_hint","settings_legacy_talk_hint",
                "settings_legacy_export_hint","settings_legacy_floating_hint",
                "settings_legacy_reminders_hint","settings_legacy_labels_hint"}) {
            assertTrue("Missing EN " + key,english.contains("name=\"" + key + "\""));
            assertTrue("Missing RU " + key,russian.contains("name=\"" + key + "\""));
        }
    }

    private static String source(String relative) throws IOException {
        Path path=Paths.get(relative);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
