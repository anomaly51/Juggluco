package tk.glucodata;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/** Dedicated acknowledgement surface for a critical glucose alarm. */
public final class CriticalGlucoseAlarmActivity extends Activity {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<CriticalGlucoseAlarmActivity> resumed =
            new WeakReference<>(null);
    private String token;
    private CriticalAlarmSurface surface;
    private Boolean lastLocked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getWindow().setHideOverlayWindows(true);
            } catch (SecurityException unavailable) {
                // Some vendor/test manifests can omit the normal permission;
                // obscured-touch filtering on the actions remains enabled.
            }
        }
        getWindow().setStatusBarColor(0xFF090B0D);
        getWindow().setNavigationBarColor(0xFF07090A);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackGuard();
        }
        token = getIntent().getStringExtra(
                CriticalGlucoseAlarmReceiver.EXTRA_TOKEN);
        render();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        token = intent.getStringExtra(
                CriticalGlucoseAlarmReceiver.EXTRA_TOKEN);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = new WeakReference<>(this);
        render();
    }

    @Override
    protected void onPause() {
        CriticalGlucoseAlarmActivity current = resumed.get();
        if (current == this) resumed = new WeakReference<>(null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // Back/Home/opening the graph is not acknowledgement. The user must
        // choose the explicit acknowledge or snooze action.
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void registerBackGuard() {
        // targetSdk 36 routes predictive Back around onBackPressed(). Consume
        // it explicitly: leaving the form must never imply acknowledgement.
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Re-reveal the value and timeline immediately after the user unlocks
        // the device while this full-screen alarm remains in front.
        if (hasFocus && lastLocked != null
                && lastLocked.booleanValue() != isDeviceLocked()) {
            render();
        }
    }

    private void render() {
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.session(this, token);
        if (session == null) {
            // A full-screen intent may be suppressed while this activity is
            // already visible. Adopt the controller's replacement token
            // directly; if the session was ACKed/failed, close stale UI.
            session = CriticalGlucoseAlarm.currentSession(this);
            if (session == null) {
                finishSafely();
                return;
            }
            token = session.token;
        }
        if (session.snoozeUntilMs > System.currentTimeMillis()) {
            finishSafely();
            return;
        }
        boolean locked = isDeviceLocked();
        lastLocked = locked;
        long now = System.currentTimeMillis();
        CriticalAlarmChartData chartData = CriticalAlarmChartData.from(
                session.displayPayload, now);
        if (surface == null) {
            surface = new CriticalAlarmSurface(this);
            setContentView(surface);
        }
        surface.bind(session, locked, chartData,
                new CriticalAlarmSurface.Actions() {
                    @Override
                    public void acknowledge() {
                        if (CriticalGlucoseAlarm.acknowledge(
                                CriticalGlucoseAlarmActivity.this, token)) {
                            finishSafely();
                        }
                    }

                    @Override
                    public void snooze() {
                        if (CriticalGlucoseAlarm.snooze(
                                CriticalGlucoseAlarmActivity.this, token,
                                5L * 60_000L)) {
                            finishSafely();
                        }
                    }

                    @Override
                    public void openGraph() {
                        String currentToken = token;
                        if (CriticalGlucoseAlarmOverlay.launchGraph(
                                CriticalGlucoseAlarmActivity.this,
                                currentToken)) {
                            try {
                                moveTaskToBack(true);
                            } catch (RuntimeException unavailable) {
                                // The graph launch already succeeded. Its
                                // lifecycle now scopes overlay visibility.
                            }
                        }
                    }
                });
    }

    private boolean isDeviceLocked() {
        KeyguardManager keyguard = (KeyguardManager)
                getSystemService(KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private void finishSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    static void sessionChanged(String replacementToken) {
        // Native glucose callbacks may arrive on a worker. Publish first to
        // the main looper, then read and validate the latest resumed Activity;
        // never retain/use a stale WeakReference across the thread boundary.
        MAIN.post(() -> {
            CriticalGlucoseAlarmActivity activity = resumed.get();
            if (activity == null || activity.isFinishing()
                    || activity.isDestroyed()) return;
            if (replacementToken != null && !replacementToken.isEmpty()) {
                activity.token = replacementToken;
            }
            activity.render();
        });
    }
}
