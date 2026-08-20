package tk.glucodata;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Dedicated acknowledgement surface for a critical glucose alarm. */
public final class CriticalGlucoseAlarmActivity extends Activity {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<CriticalGlucoseAlarmActivity> resumed =
            new WeakReference<>(null);
    private String token;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        boolean locked = false;
        KeyguardManager keyguard = (KeyguardManager)
                getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null) locked = keyguard.isKeyguardLocked();

        int accent = session.low() ? 0xFFE65B65 : 0xFFF2B84B;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF080A0A);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView badge = text(session.test()
                        ? getString(R.string.critical_alarm_test_badge)
                        : session.actual()
                        ? getString(R.string.critical_alarm_actual_badge)
                        : getString(R.string.critical_alarm_predictive_badge),
                14, accent, true);
        badge.setGravity(Gravity.CENTER);
        root.addView(badge, matchWrap(0));

        TextView direction = text(session.low()
                        ? getString(R.string.critical_alarm_low_direction)
                        : getString(R.string.critical_alarm_high_direction),
                38, Color.WHITE, true);
        direction.setGravity(Gravity.CENTER);
        root.addView(direction, matchWrap(18));

        TextView title = text(session.title, 22, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(12));

        String visibleValue = locked && !session.test()
                ? getString(R.string.critical_alarm_unlock_for_value)
                : session.value;
        TextView value = text(visibleValue, locked ? 16 : 32,
                accent, !locked);
        value.setGravity(Gravity.CENTER);
        root.addView(value, matchWrap(16));

        String visibleBody = locked && !session.test()
                ? getString(R.string.critical_alarm_lockscreen_body)
                : session.body;
        TextView body = text(visibleBody, 16, 0xFFCFD6D2, false);
        body.setGravity(Gravity.CENTER);
        root.addView(body, matchWrap(12));

        TextView instruction = text(getString(
                        R.string.critical_alarm_instruction),
                14, 0xFF939B97, false);
        instruction.setGravity(Gravity.CENTER);
        root.addView(instruction, matchWrap(22));

        Button acknowledge = button(getString(
                R.string.critical_alarm_ack_button), accent, Color.BLACK);
        acknowledge.setOnClickListener(view -> {
            if (CriticalGlucoseAlarm.acknowledge(this, token)) finishSafely();
        });
        root.addView(acknowledge, matchHeight(58, 0));

        Button snooze = button(getString(
                R.string.critical_alarm_snooze_button), 0xFF202624,
                Color.WHITE);
        snooze.setOnClickListener(view -> {
            if (CriticalGlucoseAlarm.snooze(this, token, 5L * 60_000L)) {
                finishSafely();
            }
        });
        root.addView(snooze, matchHeight(56, 12));

        Button graph = button(getString(
                R.string.critical_alarm_open_graph_button), 0xFF151918,
                0xFFCFD6D2);
        graph.setOnClickListener(view -> {
            startActivity(CriticalGlucoseAlarm.openGraphIntent(this));
            moveTaskToBack(true);
        });
        root.addView(graph, matchHeight(56, 12));

        direction.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        setContentView(scroll);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(foreground);
        button.setBackgroundColor(background);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(56));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int heightDp, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.topMargin = dp(topMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
