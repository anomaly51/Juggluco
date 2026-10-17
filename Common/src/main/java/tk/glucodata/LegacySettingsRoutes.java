/*      This file is part of Juggluco, an Android app to receive and display
 *      glucose values from continuous glucose monitors.
 */
package tk.glucodata;

/**
 * Phone settings bridges to older tools whose implementations are supplied by
 * the active form-factor source set.
 */
public final class LegacySettingsRoutes {
    private LegacySettingsRoutes() {}

    public static boolean showLastScan(MainActivity activity,Runnable dismissSettings) {
        if(!Natives.showlastscan())
            return false;
        dismissSettings.run();
        activity.lightBars(!Natives.getInvertColors());
        activity.requestRender();
        return true;
    }

    public static void showWatch(MainActivity activity) {
        Watch.show(activity,false);
    }
}
