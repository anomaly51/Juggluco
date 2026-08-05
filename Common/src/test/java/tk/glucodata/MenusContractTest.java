package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
public class MenusContractTest {
    /** Only current phone More destinations belong in the side sheet. */
    private static final int[] REQUIRED_MENU_IDS = {
            R.id.menusview,
            R.id.menus,
            R.id.sensor,
            R.id.settings,
            R.id.about,
            R.id.menu_app_title,
            R.id.menu_app_section
    };

    private static final int[] HIDDEN_LEGACY_MENU_IDS = {
            R.id.list,
            R.id.statistics,
            R.id.lastscan,
            R.id.watch,
            R.id.talk,
            R.id.mirror,
            R.id.export,
            R.id.close
    };

    private Context context;
    private View root;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
        root = LayoutInflater.from(context).inflate(R.layout.menus, null, false);
    }

    @Test
    public void requiredMenuIdsRemainInflatable() {
        assertEquals(7, REQUIRED_MENU_IDS.length);
        for (int id : REQUIRED_MENU_IDS) {
            assertNotNull("Missing phone More view for resource ID " + id,
                    root.findViewById(id));
        }
    }

    @Test
    public void legacyActionsAreAbsentFromTheVisiblePhoneMoreSurface() {
        assertEquals(8, HIDDEN_LEGACY_MENU_IDS.length);
        for (int id : HIDDEN_LEGACY_MENU_IDS) {
            View action = root.findViewById(id);
            assertNotNull("Legacy handler lost its resource ID " + id, action);
            assertEquals("Legacy phone More action is still visible: " + id,
                    View.GONE, action.getVisibility());
        }
        assertEquals(View.VISIBLE, root.findViewById(R.id.sensor).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.settings).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.about).getVisibility());
    }

    @Test
    public void menuIsAnOutlinedSideSheetWithScrollableContent() {
        assertTrue(root instanceof FrameLayout);

        View backdrop = root.findViewById(R.id.modern_menu_backdrop);
        LinearLayout panel = root.findViewById(R.id.modern_menu_panel);
        assertNotNull(backdrop);
        assertNotNull(panel);
        assertTrue(backdrop.isClickable());
        assertTrue(panel.isClickable());
        assertTrue(!panel.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                panel.getImportantForAccessibility());
        assertNotNull(panel.getBackground());
        assertSame(root, panel.getParent());

        FrameLayout.LayoutParams panelParams =
                (FrameLayout.LayoutParams) panel.getLayoutParams();
        assertEquals(Gravity.START, panelParams.gravity);
        assertEquals(dp(360), panelParams.width);

        ScrollView scrollView = findDescendant(panel, ScrollView.class);
        assertNotNull(scrollView);
        assertTrue(scrollView.isFillViewport());
        assertEquals(1, scrollView.getChildCount());
        assertTrue(scrollView.getChildAt(0) instanceof LinearLayout);
    }

    @Test
    public void visibleActionsMeetTheTouchContract() {
        int minimum = dp(48);
        int[] actions = {
                R.id.sensor, R.id.settings, R.id.about
        };
        for (int id : actions) {
            View action = root.findViewById(id);
            assertNotNull(action);
            assertTrue("Menu action is too small: " + id,
                    action.getMinimumHeight() >= minimum);
        }

    }

    @Test
    public void moreContainsNoAddTimelineOrGraphDisplayControls() {
        int[] hiddenLabels = {
                R.string.new_amount, R.string.now, R.string.search,
                R.string.date, R.string.day_back, R.string.day_later,
                R.string.week_back, R.string.week_later
        };
        for (int label : hiddenLabels) {
            assertFalse("Removed More action is still visible: " + label,
                    containsText(root, context.getString(label)));
        }
        assertNull(findDescendant(root, SwitchCompat.class));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static <T extends View> T findDescendant(View view, Class<T> type) {
        if (type.isInstance(view)) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T match = findDescendant(group.getChildAt(index), type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static boolean containsText(View view, CharSequence expected) {
        if (view instanceof TextView
                && expected.toString().contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) {
                    return true;
                }
            }
        }
        return false;
    }
}
