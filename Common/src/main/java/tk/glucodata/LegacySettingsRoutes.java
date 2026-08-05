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

    public static boolean showList(MainActivity activity,Runnable dismissSettings) {
        GlucoseCurve curve=Applic.app.curve;
        if(curve==null)
            return false;
        dismissSettings.run();
        activity.lightBars(!Natives.getInvertColors());
        Natives.makenumbers();
        activity.requestRender();
        curve.getnumcontrol(activity);
        return true;
    }

    public static boolean showStatistics(MainActivity activity,Runnable dismissSettings) {
        if(!Natives.makepercentages())
            return false;
        dismissSettings.run();
        activity.lightBars(!Natives.getInvertColors());
        activity.requestRender();
        Stats.mkstats(activity);
        return true;
    }

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
