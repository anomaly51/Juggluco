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

    private final MainActivity activity;
    private final Runnable onClose;
    private FrameLayout root;
    private LinearLayout readinessCard;
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
        new FullScreenAlertSettingsPage(activity, onClose).show();
    }

    private void show() {
        CriticalGlucoseAlarm.ensureChannels(activity);
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

        TextView intro = ClinicalUi.body(activity,
                activity.getString(R.string.fullscreen_alert_settings_intro));
        intro.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 8));
        content.addView(intro);

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

        ScrollView scroll = ClinicalUi.scrollScreen(activity, content);
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
        MainActivity.setonback(() -> close(false));
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
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
                snapshot.dndPolicyAccess
                        ? R.string.critical_alarm_dnd_ready
                        : R.string.critical_alarm_dnd_needed,
                () -> CriticalAlarmDiagnostics.openDndSettings(activity));
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
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        ViewParent parent = root.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(root);
        activity.lightBars(false);
        if (onClose != null) onClose.run();
    }

    private static boolean forcedFullScreenReady(
            CriticalAlarmDiagnostics.Snapshot snapshot) {
        return snapshot.notificationAccess() && snapshot.dndPolicyAccess
                && snapshot.fullScreenAccess && snapshot.overlayAccess
                && allChannelsReady(snapshot);
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
