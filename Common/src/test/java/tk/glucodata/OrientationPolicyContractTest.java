package tk.glucodata;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OrientationPolicyContractTest {
    @Test
    public void phonesAlwaysFollowTheFullUserOrientationPolicy() {
        assertEquals(SCREEN_ORIENTATION_FULL_USER,
                OrientationPolicy.resolveRequestedOrientation(
                        false, SCREEN_ORIENTATION_LANDSCAPE));
        assertEquals(SCREEN_ORIENTATION_FULL_USER,
                OrientationPolicy.resolveRequestedOrientation(
                        false, SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
    }

    @Test
    public void wearablesRetainTheirPersistedOrientation() {
        assertEquals(SCREEN_ORIENTATION_PORTRAIT,
                OrientationPolicy.resolveRequestedOrientation(
                        true, SCREEN_ORIENTATION_PORTRAIT));
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                OrientationPolicy.resolveRequestedOrientation(
                        true, SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
    }
}
