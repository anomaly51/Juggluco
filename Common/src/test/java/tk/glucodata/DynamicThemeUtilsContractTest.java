package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DynamicThemeUtilsContractTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
    }

    @Test
    public void applyThemeStylesCoreControlsWithoutNativeApplication() {
        assertEquals(Application.class,
                RuntimeEnvironment.getApplication().getClass());

        LinearLayout root = new LinearLayout(context);
        Button button = new Button(context);
        EditText editText = new EditText(context);
        Spinner spinner = new Spinner(context);

        clearStyle(button);
        clearStyle(editText);
        clearStyle(spinner);
        root.addView(button);
        root.addView(editText);
        root.addView(spinner);

        DynamicThemeUtils.applyTheme(root, 14, false);

        int touchTarget = dp(48);
        assertEquals(touchTarget, button.getMinimumHeight());
        assertEquals(touchTarget, editText.getMinimumHeight());
        assertEquals(touchTarget, spinner.getMinimumHeight());

        assertNotNull(button.getBackground());
        assertNotNull(editText.getBackground());
        assertNotNull(spinner.getBackground());
        assertTrue(button.getBackground() instanceof InsetDrawable);
        assertTrue(editText.getBackground() instanceof RippleDrawable);
        assertTrue(spinner.getBackground() instanceof LayerDrawable);
    }

    @Test
    public void lightThemeUsesReadableLightSystemBars() {
        Context lightContext = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_Light
        );
        int window = resolveColor(lightContext, android.R.attr.windowBackground);
        assertEquals(window, resolveColor(lightContext, android.R.attr.statusBarColor));
        assertEquals(window, resolveColor(lightContext, android.R.attr.navigationBarColor));
    }

    @Test
    public void overlayThemeModernizesLegacyHierarchyAsOneSystem() {
        LinearLayout modal = new LinearLayout(context);
        TextView action = new TextView(context);
        action.setText("Legacy action");
        action.setTextColor(Color.WHITE);
        action.setClickable(true);
        action.setBackground(null);
        SwitchCompat toggle = new SwitchCompat(context);
        SeekBar seekBar = new SeekBar(context);
        ListView list = new ListView(context);
        modal.addView(action);
        modal.addView(toggle);
        modal.addView(seekBar);
        modal.addView(list);

        DynamicThemeUtils.applyOverlayTheme(
                modal,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT),
                14,
                false
        );

        int touchTarget = dp(48);
        assertNotNull(modal.getBackground());
        assertTrue(modal.getElevation() > 0f);
        assertTrue(modal.getClipToOutline());
        assertNotNull(action.getBackground());
        assertEquals(touchTarget, action.getMinimumHeight());
        assertEquals(touchTarget, toggle.getMinimumHeight());
        assertEquals(touchTarget, seekBar.getMinimumHeight());
        assertNotNull(toggle.getThumbTintList());
        assertNotNull(toggle.getTrackTintList());
        assertNotNull(seekBar.getProgressTintList());
        assertNotNull(list.getDivider());
        assertEquals(dp(1), list.getDividerHeight());
    }

    @Test
    public void fullScreenLegacyOverlayUsesFlatWindowSurface() {
        LinearLayout screen = new LinearLayout(context);
        screen.setElevation(dp(8));

        DynamicThemeUtils.applyOverlayTheme(
                screen,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT),
                14,
                false
        );

        assertNotNull(screen.getBackground());
        assertEquals(0f, screen.getElevation(), 0f);
        assertTrue(!screen.getClipToOutline());
    }

    private static void clearStyle(android.view.View view) {
        view.setMinimumHeight(0);
        view.setBackground(null);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int resolveColor(Context themedContext, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(themedContext.getTheme().resolveAttribute(attribute, value, true));
        if (value.resourceId != 0) {
            return themedContext.getColor(value.resourceId);
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data
                : Color.TRANSPARENT;
    }
}
