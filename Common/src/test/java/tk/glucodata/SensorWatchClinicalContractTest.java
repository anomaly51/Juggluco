package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import tk.glucodata.settings.Shortcuts;

/** Guards validation and one-column routing for sensor, calibration and watch flows. */
public class SensorWatchClinicalContractTest {
    @Test
    public void sensorWarmupMappingKeepsNativeRangeAndOrdering() {
        assertEquals(0,Sensors.progressToValue(0,0));
        assertEquals(255,Sensors.progressToValue(1000,0));
        int previous=-1;
        for(int progress=0;progress<=1000;progress+=25) {
            int value=Sensors.progressToValue(progress,0);
            assertTrue(value>=previous);
            assertTrue(value>=0&&value<=255);
            previous=value;
        }
        assertTrue(Math.abs(Sensors.valueToProgress(60,0)
                -Sensors.valueToProgress(Sensors.progressToValue(
                        Sensors.valueToProgress(60,0),0),0))<=8);
    }

    @Test
    public void watchAndEditorValidationRejectsInvalidInputs() {
        assertTrue(Wearos.validWatchNodePosition(0,1));
        assertTrue(Wearos.validWatchNodePosition(2,3));
        assertFalse(Wearos.validWatchNodePosition(-1,3));
        assertFalse(Wearos.validWatchNodePosition(3,3));

        assertTrue(GarminStatus.validGarminAppId("0123456789ABCDEF0123456789abcdef"));
        assertFalse(GarminStatus.validGarminAppId("0123"));
        assertFalse(GarminStatus.validGarminAppId("Z123456789ABCDEF0123456789ABCDE"));

        assertTrue(Shortcuts.validShortcutDraft("Insulin","2.5"));
        assertFalse(Shortcuts.validShortcutDraft("","2.5"));
        assertFalse(Shortcuts.validShortcutDraft("Insulin"," "));
    }

    @Test
    public void phoneFlowsUseClinicalFullScreenOneColumnShells() throws Exception {
        String sensors=mainSource("tk/glucodata/Sensors.java");
        assertTrue(sensors.contains("buildPhoneView"));
        assertTrue(sensors.contains("clinical_sensor_details_title"));

        String calibrations=mainSource("tk/glucodata/CalibrateList.java");
        assertTrue(calibrations.contains("clinical_calibration_history_title"));
        assertTrue(calibrations.contains("new LinearLayoutManager(act)"));
        assertFalse(calibrations.contains("new GridLayoutManager"));

        String wear=mobileSource("tk/glucodata/Wearos.java");
        assertTrue(wear.contains("clinical_wear_title"));
        assertTrue(wear.contains("ConnectionUi.fullScreen"));

        String garmin=mobileSource("tk/glucodata/GarminStatus.java");
        assertTrue(garmin.contains("clinical_garmin_status_title"));
        assertTrue(garmin.contains("clinical_garmin_id_title"));

        String shortcuts=mobileSource("tk/glucodata/settings/Shortcuts.java");
        assertTrue(shortcuts.contains("clinical_shortcuts_title"));
        assertTrue(shortcuts.contains("ConnectionUi.confirmSheet"));
    }

    private static String mainSource(String relative) throws IOException {
        return source(Paths.get("src","main","java").resolve(relative));
    }

    private static String mobileSource(String relative) throws IOException {
        return source(Paths.get("src","mobile","java").resolve(relative));
    }

    private static String source(Path relative) throws IOException {
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
