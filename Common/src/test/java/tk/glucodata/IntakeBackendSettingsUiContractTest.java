package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Guards the shared retro-modern settings contract for the backend page. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IntakeBackendSettingsUiContractTest {
    private Context context;
    private View root;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        root = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_backend_settings, null, false);
    }

    @Test
    public void backendSettingsUseReadableTypeAndFlexibleTouchTargets() {
        TextView title = root.findViewById(R.id.intake_backend_title);
        EditText url = root.findViewById(R.id.intake_backend_url);
        EditText token = root.findViewById(R.id.intake_backend_token);
        Button test = root.findViewById(R.id.intake_backend_test);
        Button save = root.findViewById(R.id.intake_backend_save);

        assertTrue(title.isAccessibilityHeading());
        assertTrue(title.getTextSize() >= sp(24));
        for (View control : new View[]{url, token, test, save}) {
            assertTrue(control.getMinimumHeight() >= dp(48));
        }
    }

    @Test
    public void asynchronousStatusIsAnnouncedWithoutMovingFocus() {
        TextView status = root.findViewById(R.id.intake_backend_test_status);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                status.getAccessibilityLiveRegion());
    }

    @Test
    public void controllerReappliesAdaptiveInsetsOnFoldWidthChanges()
            throws Exception {
        Path source = Paths.get("src", "main", "java", "tk", "glucodata",
                "IntakeBackendSettings.java");
        if (!Files.exists(source)) source = Paths.get("Common").resolve(source);
        String code = new String(Files.readAllBytes(source),
                StandardCharsets.UTF_8);
        assertTrue(code.contains("ClinicalUi.readableHorizontalGutter"));
        assertTrue(code.contains("ClinicalUi.reapplyInsetsOnWidthChanges(root)"));
        assertTrue(code.contains("ViewCompat.setAccessibilityPaneTitle"));
    }

    private int dp(int value) {
        return ClinicalUi.dp(context, value);
    }

    private float sp(int value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }
}
