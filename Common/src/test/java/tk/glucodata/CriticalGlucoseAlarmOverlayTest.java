package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalGlucoseAlarmOverlayTest {
    private static final String CRITICAL_PREFS =
            "critical_glucose_alarm_v1";

    private Application application;
    private NotificationManager notifications;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager notificationShadow = shadowOf(notifications);
        notificationShadow.setNotificationsEnabled(true);
        notificationShadow.setNotificationPolicyAccessGranted(true);
        ShadowPowerManager power = shadowOf((PowerManager)
                application.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(true);
        ShadowKeyguardManager keyguard = shadowOf((KeyguardManager)
                application.getSystemService(Context.KEYGUARD_SERVICE));
        keyguard.setKeyguardLocked(false);
        clearController();
        notifications.cancelAll();
        CriticalGlucoseAlarm.ensureChannels(application);
        ShadowSettings.setCanDrawOverlays(false);
        idleMain();
    }

    @After
    public void denyOverlayAgain() throws Exception {
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        if (session != null) {
            CriticalGlucoseAlarm.acknowledge(application, session.token);
        }
        CriticalGlucoseAlarmOverlay.sessionChanged(application, null);
        idleMain();
        clearController();
        notifications.cancelAll();
        ShadowSettings.setCanDrawOverlays(false);
    }

    @Test
    public void permissionIsExplicitAndFailClosedByDefault() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSettings.setCanDrawOverlays(false);
        assertFalse(CriticalGlucoseAlarmOverlay.hasPermission(context));
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(CriticalGlucoseAlarmOverlay.hasPermission(context));
        String diagnostics = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalAlarmDiagnostics.java"));
        assertTrue(diagnostics.contains("Settings.canDrawOverlays(context)"));
        assertTrue(diagnostics.contains("ACTION_MANAGE_OVERLAY_PERMISSION"));
    }

    @Test
    public void windowTypeMatchesPlatformContract() {
        assertEquals(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                CriticalGlucoseAlarmOverlay.windowTypeForSdk(25));
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                CriticalGlucoseAlarmOverlay.windowTypeForSdk(26));
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                CriticalGlucoseAlarmOverlay.windowTypeForSdk(36));
    }

    @Test
    public void overlayRevalidatesTokenAndNeverOwnsAlarmState()
            throws Exception {
        String source = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarmOverlay.java"));
        assertTrue(source.contains("CriticalGlucoseAlarm.session(app, token)"));
        assertTrue(source.contains("MAIN.post(() -> applySession"));
        assertTrue(source.contains("CriticalGlucoseAlarm.acknowledge(app, token)"));
        assertFalse(source.contains("CriticalGlucoseAlarm.snooze(app, token"));
        assertTrue(source.contains("FLAG_SECURE"));
        assertFalse(source.contains("Ringtone"));
        assertFalse(source.contains("NotificationManager"));

        String controller = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarm.java"));
        assertTrue(controller.contains("notifySurfaces(Context context"));
        assertTrue(controller.contains(
                "CriticalGlucoseAlarmOverlay.sessionChanged(context"));
        assertTrue(controller.contains("notifySurfaces(context, null)"));
    }

    @Test
    public void failedGraphLaunchNeverHidesLiveExactToken() {
        ShadowSettings.setCanDrawOverlays(true);
        String token = showActualLow();
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        Context failing = new ContextWrapper(application) {
            @Override
            public void startActivity(Intent intent) {
                throw new IllegalStateException("no graph Activity");
            }
        };
        assertFalse(CriticalGlucoseAlarmOverlay.launchGraph(failing, token));
        idleMain();

        assertNull(CriticalGlucoseAlarmOverlay.graphTokenForTest());
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    public void terminalOrStaleTokenCannotLaunchOrHideGraph() {
        String token = showActualLow();
        assertTrue(CriticalGlucoseAlarm.acknowledge(application, token));
        idleMain();
        final boolean[] started = {false};
        Context recording = new ContextWrapper(application) {
            @Override
            public void startActivity(Intent intent) {
                started[0] = true;
            }
        };

        assertFalse(CriticalGlucoseAlarmOverlay.launchGraph(recording, token));
        idleMain();
        assertFalse(started[0]);
        assertNull(CriticalGlucoseAlarmOverlay.graphTokenForTest());
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    public void graphRequestStaysVisibleUntilPageExistsAndSnoozeResetsScope()
            throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        String token = showActualLow();
        idleMain();
        Context successful = new ContextWrapper(application) {
            @Override
            public void startActivity(Intent intent) {
                // Successful dispatch; lifecycle/page attachment is purposely
                // delayed to exercise the launch-before-hide contract.
            }
        };

        assertTrue(CriticalGlucoseAlarmOverlay.launchGraph(successful, token));
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.graphTokenForTest());
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        assertTrue(CriticalGlucoseAlarm.snooze(application, token, 60_000L));
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.graphTokenForTest());
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        CriticalGlucoseAlarm.Session snoozed =
                CriticalGlucoseAlarm.session(application, token);
        snoozed.snoozeUntilMs = 0L;
        persistSession(snoozed);
        CriticalGlucoseAlarm.resume(application, token);
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    public void screenAndUnlockBroadcastsResyncOnlyTheLiveToken() {
        ShadowSettings.setCanDrawOverlays(true);
        ShadowPowerManager power = shadowOf((PowerManager)
                application.getSystemService(Context.POWER_SERVICE));
        ShadowKeyguardManager keyguard = shadowOf((KeyguardManager)
                application.getSystemService(Context.KEYGUARD_SERVICE));
        power.setIsInteractive(false);
        keyguard.setKeyguardLocked(true);

        String token = showActualLow();
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        power.setIsInteractive(true);
        keyguard.setKeyguardLocked(false);
        application.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        power.setIsInteractive(false);
        keyguard.setKeyguardLocked(true);
        application.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        assertTrue(CriticalGlucoseAlarm.acknowledge(application, token));
        power.setIsInteractive(true);
        keyguard.setKeyguardLocked(false);
        application.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    public void activityResumeAfterPermissionGrantShowsLiveSession() {
        ShadowSettings.setCanDrawOverlays(false);
        String token = showActualLow();
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        ShadowSettings.setCanDrawOverlays(true);
        Robolectric.buildActivity(Activity.class).create().start().resume();
        idleMain();

        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    @Config(sdk = 25, application = Application.class)
    public void preOreoObserverPathResyncsWithoutModernReceiverApi() {
        shadowOf(application).grantPermissions(
                Manifest.permission.SYSTEM_ALERT_WINDOW);
        ShadowSettings.setCanDrawOverlays(true);
        String token = showActualLow();
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        application.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idleMain();
        assertNull(CriticalGlucoseAlarmOverlay.visibleTokenForTest());

        application.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idleMain();
        assertEquals(token, CriticalGlucoseAlarmOverlay.visibleTokenForTest());
    }

    @Test
    public void lifecycleImplementationTracksTheActualForecastPage()
            throws Exception {
        String overlay = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalGlucoseAlarmOverlay.java"));
        assertTrue(overlay.contains("ActivityLifecycleCallbacks"));
        assertTrue(overlay.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(overlay.contains("Intent.ACTION_USER_PRESENT"));
        assertTrue(overlay.contains(
                "findViewById(R.id.forecast_details_page)"));
        assertTrue(overlay.contains("graphWasForeground"));
        assertTrue(overlay.contains("CriticalGlucoseAlarm.session(app, token)"));
    }

    private String showActualLow() {
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                61f, "Measured low", true));
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);
        assertTrue(session != null && session.token != null
                && !session.token.isEmpty());
        return session.token;
    }

    private void persistSession(CriticalGlucoseAlarm.Session session)
            throws Exception {
        Method save = CriticalGlucoseAlarm.class.getDeclaredMethod(
                "save", Context.class, CriticalGlucoseAlarm.Session.class);
        save.setAccessible(true);
        save.invoke(null, application, session);
        Field active = CriticalGlucoseAlarm.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, session);
    }

    private void clearController() throws Exception {
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        Field active = CriticalGlucoseAlarm.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, null);
        Field initialized = CriticalGlucoseAlarm.class.getDeclaredField(
                "initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, false);
    }

    private static void idleMain() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }
}
