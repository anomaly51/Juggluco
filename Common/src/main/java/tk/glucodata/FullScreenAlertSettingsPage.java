package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Android readiness page for the one supported delivery: forced full screen. */
public final class FullScreenAlertSettingsPage {
    static final int MIN_TOUCH_TARGET_DP = 48;
    private static FullScreenAlertSettingsPage activePage;

    private final MainActivity activity;
    private final Runnable onClose;
    private FrameLayout root;
    private LinearLayout readinessCard;
    private ScrollView scroll;
    private boolean closed;

    private FullScreenAlertSettingsPage(MainActivity activity,
            Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;
    }

    public static LinearLayout entryCard(android.content.Context context) {
        CriticalAlarmDiagnostics.Snapshot snapshot =
                CriticalAlarmDiagnostics.inspect(context);
        boolean ready = forcedFullScreenReady(snapshot);
        LinearLayout row = ClinicalUi.actionRow(context,
                context.getString(R.string.fullscreen_alert_entry_title),
                context.getString(ready
                        ? R.string.fullscreen_alert_entry_ready
                        : R.string.fullscreen_alert_entry_action));
        row.setMinimumHeight(ClinicalUi.dp(context, 70));
        row.setContentDescription(context.getString(
                R.string.fullscreen_alert_entry_accessibility,
                context.getString(ready
                        ? R.string.fullscreen_alert_entry_ready
                        : R.string.fullscreen_alert_entry_action)));
        return ClinicalUi.card(context, row);
    }

    public static void refreshEntryCard(LinearLayout target,
            android.content.Context context) {
        if (target == null || context == null) return;
        LinearLayout fresh = entryCard(context);
        target.removeAllViews();
        while (fresh.getChildCount() > 0) {
            View child = fresh.getChildAt(0);
            fresh.removeViewAt(0);
            target.addView(child);
        }
    }

    public static void show(MainActivity activity, Runnable onClose) {
        if (activity == null || Applic.isWearable) return;
        FullScreenAlertSettingsPage page = new FullScreenAlertSettingsPage(
                activity, onClose);
        activePage = page;
        page.show();
    }

    static boolean isShowingFor(MainActivity activity) {
        return activePage != null && activePage.activity == activity
                && !activePage.closed && activePage.root != null
                && activePage.root.getParent() != null;
    }

    static void onHostConfigurationChanged(MainActivity activity) {
        if (isShowingFor(activity)) activePage.reinflateForConfiguration();
    }

    private void show() {
        CriticalGlucoseAlarm.ensureChannels(activity);
        inflatePage(true, 0);
    }

    private void inflatePage(boolean registerBack, int restoredScrollY) {
        root = new FrameLayout(activity);
        root.setBackgroundColor(ClinicalUi.window(activity));
        LinearLayout content = ClinicalUi.verticalContent(activity);

        Button close = ClinicalUi.button(activity,
                activity.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        close.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        close.setOnClickListener(view -> close(true));
        content.addView(ClinicalUi.header(activity,
                activity.getString(R.string.fullscreen_alert_settings_title),
                close));

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.fullscreen_alert_behavior_section)));
        content.addView(explanationCard());

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.fullscreen_alert_readiness_section)));
        readinessCard = ClinicalUi.card(activity);
        content.addView(readinessCard);
        rebuildReadiness();

        Button refresh = ClinicalUi.button(activity,
                activity.getString(R.string.predictive_alert_refresh_status),
                ClinicalUi.ButtonRole.SECONDARY);
        refresh.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        refreshParams.topMargin = ClinicalUi.dp(activity, 12);
        refresh.setLayoutParams(refreshParams);
        refresh.setOnClickListener(view -> rebuildReadiness());
        content.addView(refresh);

        scroll = ClinicalUi.scrollScreen(activity, content);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            int readableGutter = ClinicalUi.readableHorizontalGutter(activity,
                    Math.max(0, view.getWidth() - safe.left - safe.right), 20);
            content.setPadding(safe.left + readableGutter,
                    safe.top + ClinicalUi.dp(activity, 8),
                    safe.right + readableGutter,
                    safe.bottom + ClinicalUi.dp(activity, 30));
            return insets;
        });
        ClinicalUi.reapplyInsetsOnWidthChanges(root);
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        if (registerBack) MainActivity.setonback(() -> close(false));
        ViewCompat.requestApplyInsets(root);
        activity.themeLightBars();
        if (restoredScrollY > 0) {
            scroll.post(() -> scroll.scrollTo(0, restoredScrollY));
        }
    }

    private void reinflateForConfiguration() {
        int preservedScrollY = scroll == null ? 0 : scroll.getScrollY();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        remove(root);
        inflatePage(false, preservedScrollY);
    }

    private View explanationCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ClinicalUi.dp(activity, 18), ClinicalUi.dp(activity, 16),
                ClinicalUi.dp(activity, 18), ClinicalUi.dp(activity, 16));
        card.setBackground(ClinicalUi.surface(activity, false, false));

        TextView title = new TextView(activity);
        title.setText(R.string.fullscreen_alert_behavior_title);
        title.setTextColor(ClinicalUi.primaryText(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(title);
        TextView body = ClinicalUi.body(activity,
                activity.getString(R.string.fullscreen_alert_behavior_body));
        body.setPadding(0, ClinicalUi.dp(activity, 6), 0, 0);
        card.addView(body);
        return card;
    }

    private void rebuildReadiness() {
        // DND bypass and channel sound are immutable channel properties. A
        // refresh after policy access is granted must first materialize the
        // newly selected bypass-versioned channels, then inspect those IDs.
        CriticalGlucoseAlarm.ensureChannels(activity);
        CriticalAlarmDiagnostics.Snapshot snapshot =
                CriticalAlarmDiagnostics.inspect(activity);
        readinessCard.removeAllViews();
        boolean channelsReady = allChannelsReady(snapshot);
        addAction(R.string.critical_alarm_notification_access_title,
                snapshot.notificationAccess() && channelsReady
                        ? R.string.critical_alarm_notification_access_ready
                        : snapshot.notificationAccess()
                        ? R.string.critical_alarm_channels_action_needed
                        : R.string.critical_alarm_notification_access_needed,
                () -> {
                    if (Build.VERSION.SDK_INT >= 33 && !snapshot.postPermission) {
                        activity.askNotify();
                    } else {
                        CriticalAlarmDiagnostics.openNotificationSettings(activity);
                    }
                });
        addAction(R.string.critical_alarm_full_screen_title,
                snapshot.fullScreenAccess
                        ? R.string.critical_alarm_full_screen_ready
                        : R.string.critical_alarm_full_screen_needed,
                () -> CriticalAlarmDiagnostics.openFullScreenSettings(activity));
        addAction(R.string.critical_alarm_overlay_title,
                snapshot.overlayAccess
                        ? R.string.critical_alarm_overlay_ready
                        : R.string.critical_alarm_overlay_needed,
                () -> CriticalAlarmDiagnostics.openOverlaySettings(activity));
        addAction(R.string.critical_alarm_dnd_title,
                dndStatusResource(snapshot),
                () -> CriticalAlarmDiagnostics.openDndSettings(activity));
    }

    static int dndStatusResource(CriticalAlarmDiagnostics.Snapshot snapshot) {
        if (!snapshot.dndPolicyAccess) return R.string.critical_alarm_dnd_needed;
        if (snapshot.dndBlocksAllAlarms()) {
            return R.string.critical_alarm_dnd_total_silence;
        }
        if (!snapshot.dndModeAllowsAlarmChannels()) {
            return R.string.critical_alarm_dnd_mode_unknown;
        }
        return R.string.critical_alarm_dnd_ready;
    }

    private void addAction(int titleRes, int statusRes, Runnable action) {
        if (readinessCard.getChildCount() > 0) {
            readinessCard.addView(ClinicalUi.divider(activity));
        }
        LinearLayout row = ClinicalUi.actionRow(activity,
                activity.getString(titleRes), activity.getString(statusRes));
        row.setMinimumHeight(ClinicalUi.dp(activity, 70));
        row.setOnClickListener(view -> action.run());
        readinessCard.addView(row, new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT));
    }

    private void close(boolean popBack) {
        if (closed) return;
        closed = true;
        if (activePage == this) activePage = null;
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        remove(root);
        activity.themeLightBars();
        if (onClose != null) onClose.run();
    }

    private static void remove(View view) {
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
    }

    static boolean forcedFullScreenReady(
            CriticalAlarmDiagnostics.Snapshot snapshot) {
        return snapshot.deviceConfigured();
    }

    private static boolean allChannelsReady(
            CriticalAlarmDiagnostics.Snapshot snapshot) {
        return snapshot.actualChannels.ready()
                && snapshot.actualChannels.bypassDnd
                && snapshot.predictiveChannels.ready()
                && snapshot.predictiveChannels.bypassDnd
                && snapshot.signalLossChannels.ready()
                && snapshot.signalLossChannels.bypassDnd;
    }
}
