package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression contract for readable non-dashboard content on Fold/tablet. */
public class LargeScreenReadableWidthContractTest {
    @Test
    public void liveScreensAddReadableGutterOnTopOfSafeInsets()
            throws Exception {
        for (String file : new String[]{
                "IntakeComposer.java",
                "ForecastDetailsPage.java",
                "GlucoseAlertSettingsPage.java",
                "PredictiveAlertSettingsPage.java",
                "FullScreenAlertSettingsPage.java"
        }) {
            String source = source(file);
            assertTrue(file, source.contains(
                    "ClinicalUi.readableHorizontalGutter("));
            assertTrue(file, source.contains("safe.left + readableGutter")
                    || source.contains("bars.left + readableGutter"));
            assertTrue(file, source.contains("safe.right + readableGutter")
                    || source.contains("bars.right + readableGutter"));
            assertTrue(file, source.contains("displayCutout()"));
            assertTrue(file, source.contains("WindowInsetsCompat.Type.ime()"));
            assertTrue(file, source.contains(
                    "ClinicalUi.reapplyInsetsOnWidthChanges(root)"));
        }
    }

    @Test
    public void intakeBubblesAreCappedWithoutChangingPhoneRatio()
            throws Exception {
        String source = source("IntakeComposer.java");
        assertTrue(source.contains("windowWidth * 0.84f"));
        assertTrue(source.contains("Math.min((int) (windowWidth * 0.84f), dp(704))"));
    }

    @Test
    public void dashboardAndGraphStayOutsideReadableContentPolicy()
            throws Exception {
        assertFalse(source("DashboardChrome.java").contains(
                "ClinicalUi.readableHorizontalGutter("));
        assertFalse(source("GlucoseCurve.java").contains(
                "ClinicalUi.readableHorizontalGutter("));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
