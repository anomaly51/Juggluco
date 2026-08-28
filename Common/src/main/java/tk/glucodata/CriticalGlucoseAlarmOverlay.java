package tk.glucodata;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Token-bound projection of the current critical session above an unlocked
 * application. Sound, expiry and episode ownership remain exclusively in
 * {@link CriticalGlucoseAlarm}; this class owns only the visual window.
 */
final class CriticalGlucoseAlarmOverlay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long GRAPH_PROBE_INTERVAL_MS = 200L;
    private static final long GRAPH_LAUNCH_TIMEOUT_MS = 5_000L;

    private static WindowManager windowManager;
    private static CriticalAlarmSurface surface;
    private static String visibleToken;
    private static String graphToken;
    private static boolean graphWasForeground;
    private static long graphLaunchDeadlineMs;
    private static Runnable graphProbe;
    private static Application observedApplication;
    private static boolean lifecycleRegistered;
    private static boolean receiverRegistered;

    private static final BroadcastReceiver SCREEN_RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    Context app = context == null ? null
                            : context.getApplicationContext();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        // Remove immediately. Relying only on a later keyguard
                        // callback can expose the unlocked value on OEM lock
                        // screens while the overlay window remains attached.
                        MAIN.post(CriticalGlucoseAlarmOverlay::removeWindow);
                        return;
                    }
                    MAIN.post(() -> resyncLiveSession(app));
                }
            };

    private static final Application.ActivityLifecycleCallbacks
            ACTIVITY_LIFECYCLE = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle state) {}

                @Override
                public void onActivityStarted(Activity activity) {
                    activityStateChanged(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    activityStateChanged(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {}

                @Override
                public void onActivityStopped(Activity activity) {
                    activityStateChanged(activity);
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity,
                        Bundle state) {}

                @Override
                public void onActivityDestroyed(Activity activity) {
                    activityStateChanged(activity);
                }
            };

    private CriticalGlucoseAlarmOverlay() {}

    static boolean hasPermission(Context context) {
        return CriticalAlarmDiagnostics.overlayAccess(context);
    }

    static void sessionChanged(Context context, String token) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        MAIN.post(() -> applySession(app, token));
    }

    /**
     * Opens the graph before changing overlay state. A failed Activity launch
     * therefore cannot strand a live alarm without its above-app surface.
     */
    static boolean launchGraph(Context context, String token) {
        if (context == null || token == null || token.isEmpty()) return false;
        Context app = context.getApplicationContext();
        if (app == null) return false;
        CriticalGlucoseAlarm.Session live =
                CriticalGlucoseAlarm.session(app, token);
        if (live == null
                || live.snoozeUntilMs > System.currentTimeMillis()) {
            return false;
        }
        try {
            context.startActivity(CriticalGlucoseAlarm.openGraphIntent(context));
        } catch (RuntimeException unavailable) {
            return false;
        }
        // Revalidation is repeated on the main looper after dispatch so ACK,
        // expiry or token replacement racing startActivity cannot suppress a
        // newer/terminal presentation.
        MAIN.post(() -> graphLaunchSucceeded(app, token));
        return true;
    }

    static String visibleTokenForTest() {
        return visibleToken;
    }

    static String graphTokenForTest() {
        return graphToken;
    }

    private static void applySession(Context app, String token) {
        ensureObservers(app);
        if (token == null || token.isEmpty()) {
            clearGraphState();
            removeWindow();
            return;
        }
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.session(app, token);
        if (session == null) {
            if (token.equals(graphToken)) clearGraphState();
            if (token.equals(visibleToken)) removeWindow();
            return;
        }
        if (session.snoozeUntilMs > System.currentTimeMillis()) {
            // Snooze is a new presentation boundary. Opening the graph before
            // snoozing must not suppress the exact token when RESUME fires.
            if (token.equals(graphToken)) clearGraphState();
            if (token.equals(visibleToken)) removeWindow();
            return;
        }
        if (graphToken != null && !token.equals(graphToken)) {
            clearGraphState();
        }
        if (token.equals(graphToken) && graphWasForeground
                && isForecastGraphForeground()) {
            removeWindow();
            return;
        }
        if (token.equals(graphToken) && graphWasForeground) {
            // The in-app forecast page has been closed or MainActivity left
            // the foreground. Restore the still-live exact alarm immediately.
            clearGraphState();
        }
        if (!canShowAboveApps(app)) {
            removeWindow();
            return;
        }
        if (!token.equals(visibleToken)) {
            removeWindow();
        }
        bindAndAttach(app, session, CriticalAlarmChartData.from(
                session.displayPayload, System.currentTimeMillis()));
    }

    private static boolean canShowAboveApps(Context context) {
        if (!hasPermission(context)) return false;
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        PowerManager power = (PowerManager)
                context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    private static void bindAndAttach(Context app,
            CriticalGlucoseAlarm.Session session,
            CriticalAlarmChartData chartData) {
        if (surface == null) {
            surface = new CriticalAlarmSurface(app);
            surface.setFocusableInTouchMode(true);
            surface.setOnKeyListener((view, keyCode, event) ->
                    keyCode == KeyEvent.KEYCODE_BACK);
        }
        String token = session.token;
        surface.bind(session, false, chartData,
                new CriticalAlarmSurface.Actions() {
                    @Override
                    public void acknowledge() {
                        if (CriticalGlucoseAlarm.acknowledge(app, token)) {
                            removeWindow();
                        }
                    }
                });
        if (surface.getParent() == null) {
            WindowManager manager = (WindowManager)
                    app.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) return;
            WindowManager.LayoutParams params = layoutParams();
            try {
                manager.addView(surface, params);
                windowManager = manager;
            } catch (RuntimeException | LinkageError denied) {
                // Notification/FSI remains the mandatory fallback. Never let
                // an OEM overlay failure invalidate the medical alarm.
                if (Log.doLog) Log.e("CriticalAlarmOverlay",
                        "Unable to add overlay: "
                                + denied.getClass().getSimpleName());
                windowManager = null;
                surface = null;
                visibleToken = null;
                return;
            }
        }
        visibleToken = token;
        surface.requestFocus();
    }

    private static WindowManager.LayoutParams layoutParams() {
        int type = windowTypeForSdk(Build.VERSION.SDK_INT);
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SECURE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.dimAmount = .82f;
        params.setTitle("Critical glucose alarm");
        return params;
    }

    static int windowTypeForSdk(int sdk) {
        return sdk >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    private static void graphLaunchSucceeded(Context app, String token) {
        if (app == null || token == null || token.isEmpty()) return;
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.session(app, token);
        if (session == null
                || session.snoozeUntilMs > System.currentTimeMillis()) return;
        clearGraphState();
        graphToken = token;
        graphLaunchDeadlineMs = System.currentTimeMillis()
                + GRAPH_LAUNCH_TIMEOUT_MS;
        probeGraph(app, token);
    }

    private static void probeGraph(Context app, String token) {
        if (app == null || token == null || !token.equals(graphToken)) return;
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.session(app, token);
        if (session == null
                || session.snoozeUntilMs > System.currentTimeMillis()) {
            clearGraphState();
            applySession(app, token);
            return;
        }
        boolean foreground = isForecastGraphForeground();
        if (foreground) {
            graphWasForeground = true;
            removeWindow();
            scheduleGraphProbe(app, token);
            return;
        }
        if (graphWasForeground
                || System.currentTimeMillis() >= graphLaunchDeadlineMs) {
            clearGraphState();
            applySession(app, token);
            return;
        }
        // startActivity() succeeded, but MainActivity/onNewIntent and the
        // forecast page are asynchronous. Keep the existing overlay visible
        // until the requested graph is actually attached and foreground.
        scheduleGraphProbe(app, token);
    }

    private static void scheduleGraphProbe(Context app, String token) {
        if (graphProbe != null) MAIN.removeCallbacks(graphProbe);
        graphProbe = () -> {
            graphProbe = null;
            probeGraph(app, token);
        };
        MAIN.postDelayed(graphProbe, GRAPH_PROBE_INTERVAL_MS);
    }

    private static boolean isForecastGraphForeground() {
        MainActivity activity = MainActivity.thisone;
        if (activity == null || !activity.active || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed())) return false;
        View graph = activity.findViewById(R.id.forecast_details_page);
        return graph != null && graph.getParent() != null
                && graph.getVisibility() == View.VISIBLE;
    }

    private static void clearGraphState() {
        graphToken = null;
        graphWasForeground = false;
        graphLaunchDeadlineMs = 0L;
        if (graphProbe != null) {
            MAIN.removeCallbacks(graphProbe);
            graphProbe = null;
        }
    }

    private static void activityStateChanged(Activity activity) {
        if (activity == null) return;
        Context app = activity.getApplicationContext();
        MAIN.post(() -> {
            if (graphToken != null && activity instanceof MainActivity) {
                probeGraph(app, graphToken);
            } else {
                resyncLiveSession(app);
            }
        });
    }

    private static void resyncLiveSession(Context app) {
        if (app == null) return;
        CriticalGlucoseAlarm.Session live =
                CriticalGlucoseAlarm.currentSession(app);
        applySession(app, live == null ? null : live.token);
    }

    private static void ensureObservers(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (observedApplication != application) {
            releaseObservers();
            observedApplication = application;
        }
        if (!lifecycleRegistered) {
            try {
                application.registerActivityLifecycleCallbacks(
                        ACTIVITY_LIFECYCLE);
                lifecycleRegistered = true;
            } catch (RuntimeException unavailable) {
                // Screen broadcasts and ordinary session updates remain.
            }
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Api33.registerReceiver(application, SCREEN_RECEIVER,
                            filter);
                } else {
                    application.registerReceiver(SCREEN_RECEIVER, filter);
                }
                receiverRegistered = true;
            } catch (RuntimeException unavailable) {
                // Activity lifecycle and the next controller update remain.
            }
        }
    }

    private static void releaseObservers() {
        Application previous = observedApplication;
        if (previous != null && lifecycleRegistered) {
            try {
                previous.unregisterActivityLifecycleCallbacks(
                        ACTIVITY_LIFECYCLE);
            } catch (RuntimeException ignored) {}
        }
        if (previous != null && receiverRegistered) {
            try {
                previous.unregisterReceiver(SCREEN_RECEIVER);
            } catch (RuntimeException ignored) {}
        }
        lifecycleRegistered = false;
        receiverRegistered = false;
        observedApplication = null;
        clearGraphState();
        removeWindow();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33 {
        static void registerReceiver(Application application,
                BroadcastReceiver receiver, IntentFilter filter) {
            application.registerReceiver(receiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private static void removeWindow() {
        CriticalAlarmSurface current = surface;
        WindowManager manager = windowManager;
        surface = null;
        windowManager = null;
        visibleToken = null;
        if (current == null || manager == null) return;
        try {
            manager.removeViewImmediate(current);
        } catch (RuntimeException ignored) {
            try {
                manager.removeView(current);
            } catch (RuntimeException alsoIgnored) {
                if (Log.doLog) Log.e("CriticalAlarmOverlay",
                        "Unable to remove stale overlay");
            }
        }
    }
}
