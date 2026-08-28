package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DashboardChromeContractTest {
    private static final int[] NAVIGATION_IDS = {
            R.id.modern_dashboard_overview,
            R.id.modern_dashboard_add_intake,
            R.id.modern_dashboard_menu
    };

    private static final int[] NAVIGATION_TEXT_IDS = {
            R.string.dashboard_overview,
            R.string.intake_add_short,
            R.string.dashboard_more
    };

    private static final int[] NAVIGATION_DESCRIPTION_IDS = {
            R.string.dashboard_overview,
            R.string.intake_add_short,
            R.string.dashboard_more
    };

    private static final int[] RANGE_IDS = {
            R.id.modern_dashboard_range_3h,
            R.id.modern_dashboard_range_6h,
            R.id.modern_dashboard_range_8h,
            R.id.modern_dashboard_range_12h,
            R.id.modern_dashboard_range_24h
    };

    private static final int[] RANGE_TEXT_IDS = {
            R.string.dashboard_3_hours,
            R.string.dashboard_6_hours,
            R.string.dashboard_8_hours,
            R.string.dashboard_12_hours,
            R.string.dashboard_24_hours
    };

    private Context context;
    private View root;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
        root = LayoutInflater.from(context)
                .inflate(R.layout.modern_dashboard_chrome, null, false);
    }

    @Test
    public void dashboardIsAnOpaqueShellWithDedicatedGraphHost() {
        assertNotNull(root.getBackground());
        assertFalse(root.getBackground() instanceof ColorDrawable
                && ((ColorDrawable) root.getBackground()).getColor() == 0);

        assertNotNull(root.findViewById(R.id.modern_dashboard_app_bar));
        assertNotNull(root.findViewById(R.id.modern_dashboard_content));
        assertNotNull(root.findViewById(R.id.modern_dashboard_hero));
        assertNotNull(root.findViewById(R.id.modern_dashboard_graph_card));
        assertNotNull(root.findViewById(R.id.modern_dashboard_graph_header));
        assertNotNull(root.findViewById(R.id.modern_dashboard_graph_controls));

        FrameLayout graphHost = root.findViewById(
                R.id.modern_dashboard_graph_host);
        assertNotNull(graphHost);
        assertEquals(0, graphHost.getChildCount());
        assertEquals(0, graphHost.getLayoutParams().height);
        assertTrue(((LinearLayout.LayoutParams) graphHost.getLayoutParams()).weight > 0f);
    }

    @Test
    public void dashboardUsesNeutralClinicalPalette() {
        assertEquals(Color.rgb(8, 10, 10),
                resolvedColor(android.R.attr.windowBackground));
        assertEquals(Color.rgb(78, 203, 131),
                resolvedColor(android.R.attr.colorAccent));
    }

    @Test
    public void clinicalSummaryAndTrendContextAreImmediatelyVisible() {
        TextView date = root.findViewById(R.id.modern_dashboard_date);
        TextView backend = root.findViewById(
                R.id.modern_dashboard_backend_status);
        View backendAction = root.findViewById(
                R.id.modern_dashboard_backend_action);
        TextView rangeState = root.findViewById(
                R.id.modern_dashboard_range_state);
        TextView targetRange = root.findViewById(
                R.id.modern_dashboard_target_range);
        View graphHeader = root.findViewById(
                R.id.modern_dashboard_graph_header);
        Button sensor = root.findViewById(R.id.modern_dashboard_sensor);
        View appBar = root.findViewById(R.id.modern_dashboard_app_bar);

        assertNotNull(date);
        assertNotNull(backend);
        assertNotNull(backendAction);
        assertNotNull(rangeState);
        assertNotNull(targetRange);
        assertNotNull(graphHeader);
        assertSame(appBar, sensor.getParent());
        assertEquals(context.getString(R.string.dashboard_backend_checking),
                backend.getText().toString());
        assertTrue(backendAction.isClickable());
        assertTrue(backendAction.isFocusable());
        assertTrue(backendAction.getMinimumHeight() >= dp(48));
        assertFalse(backend.isClickable());
        assertEquals(context.getString(R.string.dashboard_state_unknown),
                rangeState.getText().toString());
        assertEquals(context.getString(R.string.dashboard_target_unavailable),
                targetRange.getText().toString());
    }

    @Test
    public void primaryNavigationContainsThreeBalancedLabeledActions() {
        LinearLayout navigation = root.findViewById(R.id.modern_dashboard_actions);
        assertNotNull(navigation);
        assertEquals(3, navigation.getChildCount());
        assertNull(root.findViewById(R.id.modern_dashboard_records));
        assertNull(root.findViewById(R.id.modern_dashboard_statistics));

        int minimumTarget = dp(48);
        for (int index = 0; index < NAVIGATION_IDS.length; index++) {
            View action = root.findViewById(NAVIGATION_IDS[index]);
            assertNotNull("Missing navigation action at index " + index, action);
            assertTrue(action instanceof Button);
            assertSame(navigation, action.getParent());

            Button button = (Button) action;
            assertEquals(context.getString(NAVIGATION_TEXT_IDS[index]),
                    button.getText().toString());
            assertEquals(context.getString(NAVIGATION_DESCRIPTION_IDS[index]),
                    button.getContentDescription().toString());
            assertFalse(button.getText().toString().trim().isEmpty());
            assertTrue(button.isClickable());
            assertTrue(button.isFocusable());
            assertTrue(button.getMinimumWidth() >= minimumTarget);
            assertTrue(button.getMinimumHeight() >= minimumTarget);

            ViewGroup.LayoutParams params = action.getLayoutParams();
            assertEquals(0, params.width);
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height);
            assertEquals(1.0f,
                    ((LinearLayout.LayoutParams) params).weight, 0.0f);
        }
    }

    @Test
    public void heroStartsWithHonestLoadingStateAndSensorCallToAction() {
        TextView value = root.findViewById(R.id.modern_dashboard_value);
        TextView status = root.findViewById(R.id.modern_dashboard_status);
        TextView freshness = root.findViewById(R.id.modern_dashboard_freshness);
        Button sensor = root.findViewById(R.id.modern_dashboard_sensor);

        assertEquals(context.getString(R.string.dashboard_empty_value),
                value.getText().toString());
        assertEquals(context.getString(R.string.dashboard_status_loading),
                status.getText().toString());
        assertEquals(View.GONE, freshness.getVisibility());
        assertEquals(context.getString(R.string.dashboard_sensor_status),
                sensor.getText().toString());
        assertEquals(context.getString(R.string.dashboard_sensor_status),
                sensor.getContentDescription().toString());
        assertTrue(sensor.getMinimumHeight() >= dp(48));
    }

    @Test
    public void graphHeaderOffersNowAndFiveAccessibleRanges() {
        LinearLayout controls = root.findViewById(
                R.id.modern_dashboard_graph_controls);
        assertEquals(dp(48), controls.getLayoutParams().height);

        View hint = root.findViewById(R.id.modern_dashboard_graph_hint);
        assertSame(controls, hint.getParent());
        assertEquals(View.GONE, hint.getVisibility());

        Button now = root.findViewById(R.id.modern_dashboard_now);
        assertNotNull(now);
        assertSame(controls, now.getParent());
        assertEquals(context.getString(R.string.now), now.getText().toString());
        assertTrue(now.getMinimumHeight() >= dp(48));

        LinearLayout ranges = root.findViewById(R.id.modern_dashboard_ranges);
        HorizontalScrollView scroller = root.findViewById(
                R.id.modern_dashboard_range_scroller);
        assertNotNull(scroller);
        assertTrue(scroller.isFillViewport());
        assertEquals(android.view.Gravity.CENTER, ranges.getGravity());
        assertEquals(RANGE_IDS.length, ranges.getChildCount());
        for (int index = 0; index < RANGE_IDS.length; index++) {
            Button range = root.findViewById(RANGE_IDS[index]);
            assertNotNull(range);
            assertSame(ranges, range.getParent());
            assertEquals(context.getString(RANGE_TEXT_IDS[index]),
                    range.getText().toString());
            assertNotNull(range.getContentDescription());
            assertFalse(range.getContentDescription().toString().trim().isEmpty());
            assertTrue(range.getMinimumHeight() >= dp(48));
            assertTrue(range.getMinimumWidth() >= dp(48));
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int resolvedColor(int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(root.getContext().getTheme().resolveAttribute(
                attribute, value, true));
        return value.data;
    }
}
