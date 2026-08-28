package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Upgrade regressions for retired native phone alarm sources. */
public class UnifiedPhoneAlarmRuntimeTest {
    @Test
    public void upgradedPhoneCodesFoldOrDisappearBeforeDispatch() {
        assertEquals(4, SuperGattCallback.unifiedPhoneAlarmCode(16, false));
        assertEquals(5, SuperGattCallback.unifiedPhoneAlarmCode(17, false));
        assertEquals(0, SuperGattCallback.unifiedPhoneAlarmCode(18, false));
        assertEquals(0, SuperGattCallback.unifiedPhoneAlarmCode(19, false));
        assertEquals(0, SuperGattCallback.unifiedPhoneAlarmCode(3, false));
        assertEquals(6, SuperGattCallback.unifiedPhoneAlarmCode(6, false));
        assertEquals(7, SuperGattCallback.unifiedPhoneAlarmCode(7, false));
    }

    @Test
    public void wearableKeepsEveryLegacyAlarmCode() {
        int[] legacy = {3, 16, 17, 18, 19};
        for (int code : legacy) {
            assertEquals(code,
                    SuperGattCallback.unifiedPhoneAlarmCode(code, true));
        }
    }

    @Test
    public void nativePhoneEvaluatorHasOnlyCurrentThresholdSources()
            throws Exception {
        String cpp = source(Paths.get("src", "main", "cpp", "g.cpp"));
        String evaluator = between(cpp, "static jlong getalarmonly(",
                "int getalarmcode(");
        String wearable = between(evaluator, "#ifdef WEAROS", "#else");
        String phone = between(evaluator, "#else", "#endif");

        assertTrue(wearable.contains("veryhighAlarm"));
        assertTrue(wearable.contains("verylowAlarm"));
        assertTrue(wearable.contains("prehighAlarm"));
        assertTrue(wearable.contains("prelowAlarm"));
        assertTrue(wearable.contains("availableAlarm"));

        assertTrue(phone.contains("settings->highAlarm"));
        assertTrue(phone.contains("settings->lowAlarm"));
        assertFalse(phone.contains("veryhighAlarm"));
        assertFalse(phone.contains("verylowAlarm"));
        assertFalse(phone.contains("prehighAlarm"));
        assertFalse(phone.contains("prelowAlarm"));
        assertFalse(phone.contains("availableAlarm"));
        assertFalse(phone.contains("hist->waiting"));
    }

    @Test
    public void hubNormalizationDisablesFlagsWithoutReplacingThresholds()
            throws Exception {
        String settings = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Settings.java"));
        String normalization = between(settings,
                "private static void clinicalNormalizeUnifiedAlarms()",
                "static private void alarmsettings");
        assertTrue(normalization.contains(
                "Natives.setalarms(Natives.alarmlow(),Natives.alarmhigh(),"));
        assertTrue(normalization.contains(
                "Natives.hasalarmlow(),Natives.hasalarmhigh(),false,"));
        assertTrue(normalization.contains(
                "Natives.alarmverylow(),Natives.alarmveryhigh(),"));
        assertTrue(normalization.contains("false,false,false,false"));
        assertTrue(normalization.contains(
                "Natives.alarmprelow(),\n            Natives.alarmprehigh()"));
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing source boundary: " + start + " -> " + end,
                from >= 0 && to > from);
        return source.substring(from, to);
    }
}
