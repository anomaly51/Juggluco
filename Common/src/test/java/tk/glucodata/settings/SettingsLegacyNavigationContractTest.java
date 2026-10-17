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
    public void settingsNeverRestoresMoreWhileConfigurationBackStackDrains() {
        assertTrue(Settings.shouldRestoreMoreAfterClose(true,false));
        assertFalse(Settings.shouldRestoreMoreAfterClose(true,true));
        assertFalse(Settings.shouldRestoreMoreAfterClose(false,false));
        assertFalse(Settings.shouldRestoreMoreAfterClose(false,true));
    }

    @Test
    public void configurationDrainAlsoClearsDeferredMoreState() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        String menus=source("src/mobile/java/tk/glucodata/Menus.java");
        assertTrue(menus.contains("public static void cancelReturnToMenu()"));
        assertTrue(menus.contains("cancelReturnToMenu();"));
        assertTrue(settings.contains("Menus.cancelReturnToMenu()"));
    }

    @Test
    public void legacyChildKeepsOnlyTheSevenRemainingDestinationsReachable()
            throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        assertTrue(settings.contains("PHONE_LEGACY_ACTION_COUNT=7"));
        assertTrue(settings.contains("private static void clinicalLegacySettings"));
        String[] handlers={
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
        assertFalse(settings.contains("LegacySettingsRoutes.showList"));
        assertFalse(settings.contains("LegacySettingsRoutes.showStatistics"));
    }

    @Test
    public void removedGraphModesHaveNoSettingsRoute() throws Exception {
        String routes=source("src/main/java/tk/glucodata/LegacySettingsRoutes.java");
        assertFalse(routes.contains("Natives.makenumbers();"));
        assertFalse(routes.contains("showList"));
        assertFalse(routes.contains("showStatistics"));
        assertFalse(routes.contains("getnumcontrol"));
        assertFalse(routes.contains("Stats.mkstats"));
        assertFalse(routes.contains("Natives.makepercentages"));
        assertTrue(routes.indexOf("dismissSettings.run();",
                routes.indexOf("showLastScan"))<routes.lastIndexOf("activity.requestRender();"));
        assertTrue(routes.contains("Watch.show(activity,false)"));
    }

    @Test
    public void rootIsASixCategoryTaskMapWithoutTimelineOrInsights()
            throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        int start=settings.indexOf("Button mirror=getbutton(context,R.string.mirror)");
        int end=settings.indexOf("views=null;",start);
        String phoneRoot=settings.substring(start,end);
        assertTrue(settings.contains("PHONE_SETTINGS_CATEGORY_COUNT=6"));
        for(String category:new String[]{
                "glucoseEntry","alertsEntry","connectionsEntry",
                "dataEntry","behaviorEntry","advancedEntry"}) {
            assertTrue(phoneRoot.contains(category+".setOnClickListener"));
        }
        assertTrue(phoneRoot.contains("PhoneSettingsPage glucosePage"));
        assertTrue(phoneRoot.contains("PhoneSettingsPage alertsPage"));
        assertTrue(phoneRoot.contains("PhoneSettingsPage connectionsPage"));
        assertTrue(phoneRoot.contains("PhoneSettingsPage dataPage"));
        assertTrue(phoneRoot.contains("PhoneSettingsPage behaviorPage"));
        assertTrue(phoneRoot.contains("PhoneSettingsPage advancedPage"));
        assertTrue(phoneRoot.contains("phoneSettingsGroup(context,personalConnection,intakeBackend,exchanges,mirror)"));
        assertTrue(phoneRoot.contains("PersonalConnectionSettings.show(context)"));
        assertTrue(phoneRoot.contains("IntakeBackendSettings.show(context)"));
        assertTrue(phoneRoot.contains("phoneSettingsGroup(context,alarmbut,numalarm)"));
        assertTrue(phoneRoot.contains("LegacySettingsRoutes.showLastScan(context,dismissSettingsForNative)"));
        assertTrue(phoneRoot.contains("new LabelsClass(context).mklabellayout(dataPage.screen)"));
        assertTrue(phoneRoot.contains("FloatingConfig.show(context,behaviorPage.screen)"));
        assertFalse(phoneRoot.contains("LegacySettingsRoutes.showList"));
        assertFalse(phoneRoot.contains("LegacySettingsRoutes.showStatistics"));
        assertFalse(phoneRoot.contains("phoneSettingsGroup(context,legacy)"));
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
    public void categoryPagesOwnOnePredictableBackStep() throws Exception {
        String settings=source("src/main/java/tk/glucodata/settings/Settings.java");
        int start=settings.indexOf("private static final class PhoneSettingsPage");
        int end=settings.indexOf("private static TextView phoneSettingsIntro",start);
        String page=settings.substring(start,end).replaceAll("\\s+"," ");
        assertTrue(page.contains("hub.setVisibility(GONE)"));
        assertTrue(page.contains("context.setonback(this::close)"));
        assertTrue(page.contains("hub.setVisibility(VISIBLE)"));
        assertTrue(page.contains("removeContentView(screen)"));
        assertTrue(page.contains("back.setOnClickListener(view->context.doonback())"));
        assertTrue(page.contains("void show(View source)"));
        assertTrue(page.contains("returnFocus=source"));
        assertTrue(page.contains("hub.post(focus::requestFocus)"));
        assertTrue(page.contains("animatePhoneSettingsPageIn(screen)"));
    }

    @Test
    public void englishAndRussianLabelsDescribeTheNewSections() throws Exception {
        String english=source("src/main/res/values/settings_child_strings.xml");
        String russian=source("src/main/res/values-ru/settings_child_strings.xml");
        for(String key:new String[]{"settings_section_connections","settings_section_technical",
                "settings_hub_intro","settings_category_glucose",
                "settings_category_alerts","settings_category_connections",
                "settings_category_data","settings_category_behavior",
                "settings_category_advanced",
                "settings_legacy_title","settings_legacy_last_scan_hint",
                "settings_legacy_watch_hint","settings_legacy_talk_hint",
                "settings_legacy_export_hint","settings_legacy_floating_hint",
                "settings_legacy_reminders_hint","settings_legacy_labels_hint"}) {
            assertTrue("Missing EN " + key,english.contains("name=\"" + key + "\""));
            assertTrue("Missing RU " + key,russian.contains("name=\"" + key + "\""));
        }
        for(String removed:new String[]{"settings_legacy_list_hint",
                "settings_legacy_statistics_hint"}) {
            assertFalse("Stale EN " + removed,
                    english.contains("name=\"" + removed + "\""));
            assertFalse("Stale RU " + removed,
                    russian.contains("name=\"" + removed + "\""));
        }
    }

    private static String source(String relative) throws IOException {
        Path path=Paths.get(relative);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
