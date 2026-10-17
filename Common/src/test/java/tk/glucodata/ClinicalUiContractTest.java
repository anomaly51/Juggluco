package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

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
        assertEquals(context.getResources().getDimensionPixelSize(
                        R.dimen.lifeprompt_phone_gutter),
                content.getPaddingLeft());
        assertFalse(screen.isVerticalScrollBarEnabled());
        assertTrue(header.getChildAt(0) instanceof TextView);
        assertTrue(close.getMinimumHeight() >= dp(48));
        assertTrue(action.getMinimumHeight() >= dp(48));
        assertEquals("Target range, Used for graph status colors",
                action.getContentDescription());
        assertTrue(action.isFocusable());
        assertTrue(action.getChildAt(1) instanceof ImageView);
        assertFalse(action.getChildAt(1) instanceof TextView);
        assertNotNull(((ImageView) action.getChildAt(1)).getDrawable());
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
    public void componentKitUsesCompactSystemMonospaceAndTieredGeometry() {
        TextView title = ClinicalUi.title(context, "Glucose");
        TextView body = ClinicalUi.body(context, "Updated 20 seconds ago");
        Button action = ClinicalUi.button(
                context, "Open", ClinicalUi.ButtonRole.SECONDARY);

        assertEquals(Typeface.create("monospace", Typeface.BOLD),
                title.getTypeface());
        assertEquals(Typeface.create("monospace", Typeface.NORMAL),
                body.getTypeface());
        assertEquals(Typeface.create("monospace", Typeface.NORMAL),
                action.getTypeface());
        assertEquals(ClinicalUi.RETRO_CONTROL_CORNER_RADIUS_DP,
                ClinicalUi.RETRO_CORNER_RADIUS_DP);
        assertEquals(2, ClinicalUi.RETRO_CONTROL_CORNER_RADIUS_DP);
        assertEquals(4, ClinicalUi.RETRO_CARD_CORNER_RADIUS_DP);
        assertTrue(action.getMinimumHeight() >= dp(48));
    }

    @Test
    public void headingsAreExposedAndKeepTerminalHierarchy() {
        TextView title = ClinicalUi.title(context, "Settings");
        TextView section = ClinicalUi.sectionLabel(context, "Alerts");

        assertTrue(ViewCompat.isAccessibilityHeading(title));
        assertTrue(ViewCompat.isAccessibilityHeading(section));
        assertEquals(ClinicalUi.primaryText(context), title.getCurrentTextColor());
        assertEquals(ClinicalUi.accent(context), section.getCurrentTextColor());
        assertTrue(title.getTextSize() > section.getTextSize());
        assertTrue(section.getLetterSpacing() > title.getLetterSpacing());
    }

    @Test
    public void cardsAndControlsUseDifferentCornerRadii() {
        LinearLayout card = ClinicalUi.card(context);
        GradientDrawable cardShape = (GradientDrawable) card.getBackground();
        StateListDrawable controlStates = stateLayer(
                ClinicalUi.controlSurface(context));
        controlStates.setState(new int[]{android.R.attr.state_enabled});
        GradientDrawable controlShape =
                (GradientDrawable) controlStates.getCurrent();

        assertEquals(dp(ClinicalUi.RETRO_CARD_CORNER_RADIUS_DP),
                cardShape.getCornerRadius(), 0.01f);
        assertEquals(dp(ClinicalUi.RETRO_CONTROL_CORNER_RADIUS_DP),
                controlShape.getCornerRadius(), 0.01f);
        assertTrue(cardShape.getCornerRadius() > controlShape.getCornerRadius());
    }

    @Test
    public void interactiveRowsExposeStableHighContrastFocusLayer() {
        LinearLayout row = ClinicalUi.actionRow(context, "Display", null);
        StateListDrawable states = stateLayer(row.getBackground());
        states.setState(new int[]{android.R.attr.state_enabled});
        Drawable normal = states.getCurrent();
        states.setState(new int[]{android.R.attr.state_enabled,
                android.R.attr.state_focused});
        Drawable focused = states.getCurrent();

        assertNotSame(normal, focused);
        assertTrue(normal instanceof GradientDrawable);
        assertTrue(focused instanceof GradientDrawable);
        assertEquals(((GradientDrawable) normal).getCornerRadius(),
                ((GradientDrawable) focused).getCornerRadius(), 0.01f);
        assertTrue(ClinicalUi.FOCUS_STROKE_DP >= 2);
        assertTrue(ColorUtils.calculateContrast(
                ClinicalUi.accent(context),
                context.getColor(R.color.lifeprompt_surface_container_high)) >= 3.0d);
    }

    @Test
    public void componentKitReadsSemanticLifePromptResources() {
        assertEquals(context.getColor(R.color.lifeprompt_background),
                ClinicalUi.window(context));
        assertEquals(context.getColor(R.color.lifeprompt_on_background),
                ClinicalUi.primaryText(context));
        assertEquals(context.getColor(R.color.lifeprompt_on_surface_variant),
                ClinicalUi.secondaryText(context));
        assertEquals(context.getColor(R.color.lifeprompt_primary),
                ClinicalUi.accent(context));
        assertEquals(context.getColor(R.color.lifeprompt_error),
                ClinicalUi.danger(context));
    }

    @Test
    public void disabledLegacyToggleCannotBeChangedThroughItsStyledRow() {
        CheckDirectionBox source = new CheckDirectionBox(context);
        source.setText("Unavailable setting");
        source.setChecked(false);
        source.setEnabled(false);

        LinearLayout row = ClinicalUi.toggleRow(context, source, null);

        assertFalse(row.isEnabled());
        row.performClick();
        assertFalse(source.isChecked());
    }

    @Test
    public void readableWidthPolicyKeepsPhonesAndCapsLargeWindows() {
        assertEquals(0, ClinicalUi.readableHorizontalGutterDp(599, 0));
        assertEquals(20, ClinicalUi.readableHorizontalGutterDp(360, 20));
        assertEquals(20, ClinicalUi.readableHorizontalGutterDp(599, 20));
        assertEquals(32, ClinicalUi.readableHorizontalGutterDp(600, 20));
        assertEquals(32, ClinicalUi.readableHorizontalGutterDp(904, 20));
        assertEquals(80, ClinicalUi.readableHorizontalGutterDp(1000, 20));
        assertEquals(380, ClinicalUi.readableHorizontalGutterDp(1600, 20));
        assertTrue(1000 - 2 * ClinicalUi.readableHorizontalGutterDp(1000, 20)
                <= ClinicalUi.READABLE_MAX_CONTENT_DP);
        assertTrue(1600 - 2 * ClinicalUi.readableHorizontalGutterDp(1600, 20)
                <= ClinicalUi.READABLE_MAX_CONTENT_DP);
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

    private static StateListDrawable stateLayer(Drawable drawable) {
        assertTrue(drawable instanceof RippleDrawable);
        Drawable content = ((RippleDrawable) drawable).getDrawable(0);
        assertTrue(content instanceof StateListDrawable);
        return (StateListDrawable) content;
    }
}
