package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ClinicalUiContractTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
    }

    @Test
    public void componentKitBuildsAccessibleScreenHierarchy() {
        LinearLayout content = ClinicalUi.verticalContent(context);
        Button close = ClinicalUi.button(
                context, "Close", ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout header = ClinicalUi.header(context, "Display", close);
        LinearLayout action = ClinicalUi.actionRow(
                context, "Target range", "Used for graph status colors");
        LinearLayout card = ClinicalUi.card(context, action);
        content.addView(header);
        content.addView(card);
        ScrollView screen = ClinicalUi.scrollScreen(context, content);

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                screen.getChildAt(0).getLayoutParams().width);
        assertEquals(2, content.getChildCount());
        assertTrue(header.getChildAt(0) instanceof TextView);
        assertTrue(close.getMinimumHeight() >= dp(48));
        assertTrue(action.getMinimumHeight() >= dp(48));
        assertNotNull(card.getBackground());
        assertNotNull(action.getBackground());
        assertTrue(action.getBackground() instanceof RippleDrawable);
    }

    @Test
    public void primaryAndDangerActionsHaveDistinctSemanticColors() {
        Button primary = ClinicalUi.button(
                context, "Save", ClinicalUi.ButtonRole.PRIMARY);
        Button danger = ClinicalUi.button(
                context, "Delete", ClinicalUi.ButtonRole.DANGER);

        assertNotNull(primary.getBackground());
        assertNotNull(danger.getBackground());
        assertTrue(primary.getCurrentTextColor() != danger.getCurrentTextColor());
        assertTrue(primary.getMinimumHeight() >= dp(48));
        assertTrue(danger.getMinimumHeight() >= dp(48));
    }

    @Test
    public void disabledButtonShapeNeverTreatsDialogWindowDrawableAsAColor() throws Exception {
        for (String name : new String[]{
                "modern_component_button.xml",
                "modern_component_button_oval.xml",
                "modern_component_text_field.xml"
        }) {
            Path path = Paths.get("src", "mobile", "res", "drawable", name);
            if (!Files.exists(path)) {
                path = Paths.get("Common").resolve(path);
            }
            String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            assertTrue(xml.contains("<solid android:color=\"?attr/colorButtonBackground\" />"));
            assertFalse(xml.contains("<solid android:color=\"?android:attr/windowBackground\" />"));
        }
    }

    private int dp(int value) {
        return ClinicalUi.dp(context, value);
    }
}
