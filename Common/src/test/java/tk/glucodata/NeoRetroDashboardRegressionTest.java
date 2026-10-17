package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards the deliberately planar neo-retro dashboard composition. This test
 * owns presentation contracts only; graph data and glucose decisions belong
 * to their existing behavioural suites.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class,
        qualifiers = "en-rUS-mdpi")
public class NeoRetroDashboardRegressionTest {
    private static final int[] RETAINED_IDS = {
            R.id.modern_dashboard_chrome,
            R.id.modern_dashboard_content,
            R.id.modern_dashboard_body,
            R.id.modern_dashboard_graph_card,
            R.id.modern_dashboard_hero,
            R.id.modern_dashboard_hero_content,
            R.id.modern_dashboard_reading_column,
            R.id.modern_dashboard_hero_reading,
            R.id.modern_dashboard_value_row,
            R.id.modern_dashboard_value,
            R.id.modern_dashboard_trend,
            R.id.modern_dashboard_unit,
            R.id.modern_dashboard_meta,
            R.id.modern_dashboard_range_state,
            R.id.modern_dashboard_freshness,
            R.id.modern_dashboard_graph_header,
            R.id.modern_dashboard_target_range,
            R.id.modern_dashboard_graph_controls,
            R.id.modern_dashboard_graph_hint,
            R.id.modern_dashboard_range_scroller,
            R.id.modern_dashboard_ranges,
            R.id.modern_dashboard_range_3h,
            R.id.modern_dashboard_range_6h,
            R.id.modern_dashboard_range_8h,
            R.id.modern_dashboard_range_12h,
            R.id.modern_dashboard_range_24h,
            R.id.modern_dashboard_plot,
            R.id.modern_dashboard_graph_host,
            R.id.modern_dashboard_now,
            R.id.modern_dashboard_actions,
            R.id.modern_dashboard_add_intake,
            R.id.modern_dashboard_menu
    };

    private static final int[] NAVIGATION_IDS = {
            R.id.modern_dashboard_add_intake,
            R.id.modern_dashboard_menu
    };

    private static final int[] RANGE_IDS = {
            R.id.modern_dashboard_range_3h,
            R.id.modern_dashboard_range_6h,
            R.id.modern_dashboard_range_8h,
            R.id.modern_dashboard_range_12h,
            R.id.modern_dashboard_range_24h
    };

    private Context context;
    private View root;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        root = LayoutInflater.from(context).inflate(
                R.layout.modern_dashboard_chrome, null, false);
    }

    @Test
    public void layoutKeepsEveryDashboardHookAndTwoLargeNavActions() {
        for (int id : RETAINED_IDS) {
            assertNotNull("Dashboard ID was removed: " + id,
                    root.findViewById(id));
        }

        LinearLayout navigation = root.findViewById(
                R.id.modern_dashboard_actions);
        assertEquals(2, navigation.getChildCount());
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                navigation.getLayoutParams().width);
        assertEquals(dp(64), navigation.getLayoutParams().height);
        LinearLayout.LayoutParams navigationParams =
                (LinearLayout.LayoutParams) navigation.getLayoutParams();
        assertEquals(0, navigationParams.getMarginStart());
        assertEquals(0, navigationParams.getMarginEnd());

        for (int index = 0; index < NAVIGATION_IDS.length; index++) {
            Button action = root.findViewById(NAVIGATION_IDS[index]);
            assertSame(navigation, action.getParent());
            assertEquals(NAVIGATION_IDS[index],
                    navigation.getChildAt(index).getId());
            assertTrue(action.isClickable());
            assertTrue(action.isFocusable());
            assertTrue(action.getMinimumWidth() >= dp(48));
            assertTrue(action.getMinimumHeight() >= dp(48));
        }
        LinearLayout.LayoutParams addParams = (LinearLayout.LayoutParams)
                navigation.getChildAt(0).getLayoutParams();
        LinearLayout.LayoutParams moreParams = (LinearLayout.LayoutParams)
                navigation.getChildAt(1).getLayoutParams();
        assertTrue(addParams.weight > moreParams.weight);
    }

    @Test
    public void graphAndBottomNavigationArePlanarNotRoundedOuterCards()
            throws Exception {
        View graphCard = root.findViewById(
                R.id.modern_dashboard_graph_card);
        assertFalse("A planar graph must not clip to a decorative outline",
                graphCard.getClipToOutline());

        String dashboard = source("layout", "modern_dashboard_chrome.xml");
        if (dashboard.contains(
                "@drawable/lifeprompt_dashboard_graph_card")) {
            String graphBackground = source("drawable",
                    "lifeprompt_dashboard_graph_card.xml");
            assertFalse("Graph canvas must not have an outer stroke",
                    graphBackground.contains("<stroke"));
            assertFalse("Graph canvas must not be a rounded card",
                    graphBackground.contains("<corners"));
        }

        String navBackground = source("drawable",
                "lifeprompt_dashboard_nav_background.xml");
        assertTrue("Bottom navigation needs one top hairline",
                navBackground.contains("<layer-list"));
        assertTrue(navBackground.contains("lifeprompt_outline"));
        assertFalse("A top divider is not an enclosing border",
                navBackground.contains("<stroke"));
        assertFalse("The full-width dock must not become another card",
                navBackground.contains("<corners"));
    }

    @Test
    public void rangeSelectionUsesSubtleContainerAndKeepsLargeHitArea()
            throws Exception {
        View rangeBar = root.findViewById(
                R.id.modern_dashboard_graph_controls);
        assertEquals(dp(48), rangeBar.getLayoutParams().height);
        for (int id : RANGE_IDS) {
            Button range = root.findViewById(id);
            assertTrue(range.getMinimumWidth() >= dp(48));
            assertTrue(range.getMinimumHeight() >= dp(48));
        }

        String selected = source("drawable",
                "lifeprompt_dashboard_segmented_item.xml");
        assertTrue(selected.contains("android:state_selected=\"true\""));
        assertTrue("Selection should use the quiet terminal container",
                selected.contains(
                        "android:color=\"@color/lifeprompt_primary_container\""));
        assertTrue("Selection needs a crisp two-dp terminal underline",
                selected.contains(
                        "android:gravity=\"bottom\" android:height=\"2dp\""));
        assertTrue(selected.contains(
                "android:color=\"@color/lifeprompt_primary\""));
    }

    @Test
    public void addIsAnActionWithoutBorrowingHighGlucoseColor()
            throws Exception {
        Button add = root.findViewById(R.id.modern_dashboard_add_intake);
        assertTrue((add.getTypeface().getStyle() & Typeface.BOLD) != 0);

        String action = source("drawable",
                "lifeprompt_dashboard_primary_action.xml");
        assertFalse(action.contains("@color/lifeprompt_glucose_high"));
        assertFalse("The Add surface should not be a full alarm-like slab",
                action.contains(
                        "<solid android:color=\"@color/lifeprompt_primary\""));
        assertTrue("Add still needs a recognisable action container",
                action.contains("@color/lifeprompt_primary_container")
                        || action.contains(
                        "@color/lifeprompt_secondary_container"));
        assertTrue("Add needs a primary outline/focus cue",
                action.contains(
                        "android:color=\"@color/lifeprompt_primary\""));

        assertActionAndHighTokensStayDistinct(context);
        Configuration night = new Configuration(
                context.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = context.createConfigurationContext(night);
        assertActionAndHighTokensStayDistinct(nightContext);
    }

    private static void assertActionAndHighTokensStayDistinct(
            Context context) {
        assertNotEquals(ContextCompat.getColor(context,
                        R.color.lifeprompt_glucose_high),
                ContextCompat.getColor(context, R.color.lifeprompt_primary));
    }

    private int dp(int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }

    private static String source(String resourceType, String name)
            throws Exception {
        Path path = Paths.get("src", "main", "res", resourceType, name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
