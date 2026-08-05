/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from supported glucose sensors.                               */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

package tk.glucodata;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;

/**
 * Central orientation policy shared by every screen that can change the activity's
 * requested orientation.
 *
 * <p>Phones follow the user's system rotation preference and support all four device
 * orientations. Wear OS keeps its established, persisted orientation because watch
 * hardware and its compact layouts have different constraints.</p>
 */
public final class OrientationPolicy {
    private OrientationPolicy() {
    }

    public static int requestedOrientation() {
        // Avoid touching the native settings layer on phones: it may still contain
        // the legacy landscape/reverse-landscape value from an existing install.
        if (!Applic.isWearable) {
            return resolveRequestedOrientation(false, 0);
        }
        return resolveRequestedOrientation(true, Natives.getScreenOrientation());
    }

    static int resolveRequestedOrientation(boolean wearable,
            int persistedWearOrientation) {
        return wearable ? persistedWearOrientation : SCREEN_ORIENTATION_FULL_USER;
    }
}
