package tk.glucodata.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards phone-only routing and the persisted display-threshold boundary. */
public class SettingsPhoneChildContractTest {
    @Test
    public void clinicalChildScreensStayPhoneOnly() {
        assertTrue(Settings.useClinicalPhoneChild(false));
        assertFalse(Settings.useClinicalPhoneChild(true));
    }

    @Test
    public void allClinicalControlsMeetAndroidTouchTargetMinimum() {
        assertTrue(Settings.PHONE_SETTINGS_MIN_TOUCH_DP >= 48);
        assertEquals(11,Settings.PHONE_GRAPH_DISPLAY_TOGGLE_COUNT);
    }

    @Test
    public void displayThresholdKeepsNativeAcceptedRange() {
        assertTrue(Settings.isDisplayThresholdValid(0.0f));
        assertTrue(Settings.isDisplayThresholdValid(0.8f));
        assertTrue(Settings.isDisplayThresholdValid(0.35f));
        assertFalse(Settings.isDisplayThresholdValid(-0.001f));
        assertFalse(Settings.isDisplayThresholdValid(0.801f));
        assertFalse(Settings.isDisplayThresholdValid(Float.NaN));
    }

    @Test
    public void phoneGraphSettingsDoNotExposeAnUnsupportedVerticalZoomToggle()
            throws Exception {
        String source=source();
        String phoneDisplay=source.substring(
                source.indexOf("private static void clinicalDisplaySettings"),
                source.indexOf("static private void displaysettings"));
        assertFalse(phoneDisplay.contains("manualGlucose"));
        assertFalse(phoneDisplay.contains("Natives.setfixatey"));
    }

    private static String source() throws IOException {
        Path relative=Paths.get("src","main","java","tk","glucodata",
                "settings","Settings.java");
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
