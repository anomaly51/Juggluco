package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.widget.SwitchCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SensorScreenContractTest {
    private static final int[] LEGACY_IDS = {
            R.id.hori, R.id.grid, R.id.bluestate, R.id.info, R.id.finish,
            R.id.streamhistory, R.id.alarmclock, R.id.resetbutton,
            R.id.divorcebutton, R.id.locationpermission, R.id.scan,
            R.id.sensors, R.id.deviceaddress, R.id.forget, R.id.streaming,
            R.id.clear, R.id.stage, R.id.textView2, R.id.textView3,
            R.id.textView4, R.id.connection, R.id.consuccess, R.id.confail,
            R.id.constatus, R.id.disconnectsensor, R.id.keysuccess,
            R.id.keyfailure, R.id.keyinfo, R.id.glucosesuccess,
            R.id.glucosefailure, R.id.glucoseinfo, R.id.help,
            R.id.usebluetooth, R.id.background, R.id.priority,
            R.id.android13, R.id.close, R.id.rssi
    };

    private Context context;
    private View root;

    @Before
    public void setUp() {
        assumeTrue("Phone-only sensor layout contract", BuildConfig.isWear == 0);
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
        root = LayoutInflater.from(context).inflate(R.layout.bluesensor, null, false);
    }

    @Test
    public void phoneScreenIsVerticalAndKeepsEveryLegacyBinding() {
        assertTrue(root instanceof ScrollView);
        assertFalse(root instanceof HorizontalScrollView);

        LinearLayout content = root.findViewById(R.id.grid);
        assertNotNull(content);
        assertTrue(content.getOrientation() == LinearLayout.VERTICAL);
        assertFalse(containsGridLayout(content));

        for (int id : LEGACY_IDS) {
            assertNotNull("Missing legacy sensor binding: " + id, root.findViewById(id));
        }
    }

    @Test
    public void primaryActionsMeetMinimumTouchTarget() {
        int[] actions = {
                R.id.close, R.id.locationpermission, R.id.info, R.id.forget,
                R.id.finish, R.id.resetbutton, R.id.divorcebutton, R.id.clear,
                R.id.background, R.id.help
        };
        int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
        for (int id : actions) {
            View action = root.findViewById(id);
            assertNotNull(action);
            assertTrue("Touch target is too small: " + id, action.getMinimumHeight() >= minimum);
        }
    }

    @Test
    public void connectionPreferencesUseModernSwitchRows() {
        int[] toggles = {
                R.id.streamhistory, R.id.alarmclock, R.id.usebluetooth,
                R.id.priority, R.id.android13
        };
        int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
        for (int id : toggles) {
            View toggle = root.findViewById(id);
            assertTrue("Expected a modern switch row for " + id,
                    toggle instanceof SwitchCompat);
            assertTrue("Sensor switch is too small: " + id,
                    toggle.getMinimumHeight() >= minimum);
        }
    }

    @Test
    public void emptySensorStateKeepsActionsAndAccessiblePreferences() {
        View empty = LayoutInflater.from(context)
                .inflate(R.layout.modern_sensor_empty, null, false);
        assertTrue(empty instanceof ScrollView);
        assertTrue(((ScrollView) empty).isFillViewport());
        assertNotNull(empty.findViewById(R.id.modern_sensor_empty_card));
        assertNotNull(empty.findViewById(R.id.modern_sensor_bluetooth_state));

        int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
        int[] toggles = {
                R.id.modern_sensor_use_bluetooth,
                R.id.modern_sensor_stream_history
        };
        for (int id : toggles) {
            View toggle = empty.findViewById(id);
            assertTrue("Expected a modern empty-state switch for " + id,
                    toggle instanceof SwitchCompat);
            assertTrue(toggle.getMinimumHeight() >= minimum);
        }

        int[] actions = {R.id.modern_sensor_help, R.id.modern_sensor_close};
        for (int id : actions) {
            View action = empty.findViewById(id);
            assertNotNull(action);
            assertTrue(action.getMinimumHeight() >= minimum);
        }
    }

    private boolean containsGridLayout(View view) {
        if (view instanceof GridLayout) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsGridLayout(group.getChildAt(index))) {
                    return true;
                }
            }
        }
        return false;
    }
}
