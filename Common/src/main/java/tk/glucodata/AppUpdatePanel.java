package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Live, localized update status. Closing this panel does not cancel a download. */
final class AppUpdatePanel {
    private AppUpdatePanel() {}

    static boolean working(String state) {
        return "checking".equals(state) || "downloading".equals(state)
                || "verifying".equals(state) || "preparing".equals(state)
                || "installing".equals(state);
    }

    static boolean installStep(String state) {
        return "ready".equals(state) || "permission".equals(state) || "confirmation".equals(state);
    }

    static int headline(String state) {
        switch (state) {
            case "ready": return R.string.app_update_ready_title;
            case "permission": return R.string.app_update_permission_title;
            case "confirmation": return R.string.app_update_confirmation_title;
            case "retry": return R.string.app_update_retry_title;
            case "current": return R.string.app_update_current_title;
            case "": return R.string.app_update_idle_title;
            default: return AppUpdates.statusText(state);
        }
    }

    static int detail(String state, boolean automatic, boolean alarm, boolean onHome) {
        if (installStep(state) && alarm) return R.string.app_update_wait_alarm;
        if (installStep(state) && !onHome) return R.string.app_update_wait_home;
        if ("ready".equals(state)) return automatic
                ? R.string.app_update_ready_auto : R.string.app_update_ready_manual;
        if ("confirmation".equals(state)) return R.string.app_update_confirmation_help;
        if ("installing".equals(state) || "preparing".equals(state))
            return R.string.app_update_install_help;
        if ("retry".equals(state)) return automatic
                ? R.string.app_update_retry : R.string.app_update_retry_manual;
        if ("downloading".equals(state) || "verifying".equals(state))
            return R.string.app_update_download_help;
        if ("checking".equals(state)) return R.string.app_update_check_help;
        return AppUpdates.statusText(state);
    }

    static int percent(long bytes, long total) {
        return total <= 0 ? 0 : (int) (100 * Math.min(Math.max(0, bytes), total) / total);
    }

    static String transferText(Context context, long bytes, long total) {
        return context.getString(R.string.app_update_progress, percent(bytes, total),
                Math.max(0, bytes) / 1048576.0, Math.max(0, total) / 1048576.0);
    }

    static String banner(Context context) {
        SharedPreferences p = AppUpdates.prefs(context);
        String state = p.getString("state", "");
        if (!state.isEmpty() && !"current".equals(state)) {
            String title = context.getString(headline(state));
            if ("downloading".equals(state) && AppUpdates.totalBytes > 0)
                title += " · " + percent(AppUpdates.downloadedBytes, AppUpdates.totalBytes) + "%";
            return title + "\n" + context.getString(R.string.app_update_open_details);
        }
        return p.getBoolean("update_unseen", false)
                ? context.getString(R.string.app_update_success, BuildConfig.DISPLAY_VERSION)
                    + "\n" + context.getString(R.string.app_update_open_details) : "";
    }

    static AlertDialog show(Activity activity, Runnable dismissed) {
        SharedPreferences p = AppUpdates.prefs(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = ClinicalUi.dp(activity, 20);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(ClinicalUi.window(activity));
        TextView title = ClinicalUi.body(activity, activity.getString(R.string.app_update_title));
        title.setTextSize(22);
        title.setTextColor(ClinicalUi.primaryText(activity));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        add(content, title, 0);
        TextView versions = ClinicalUi.body(activity, "");
        versions.setTag("update_versions");
        add(content, versions, 16);
        TextView status = ClinicalUi.body(activity, "");
        status.setTag("update_status");
        status.setTextSize(19);
        status.setTextColor(ClinicalUi.accent(activity));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 24);
        TextView explanation = ClinicalUi.body(activity, "");
        explanation.setTag("update_explanation");
        add(content, explanation, 8);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ClinicalUi.accent(activity)));
        progress.setIndeterminateTintList(ColorStateList.valueOf(ClinicalUi.accent(activity)));
        progress.setTag("update_progress");
        progress.setMax(100);
        add(content, progress, 16);
        TextView bytes = ClinicalUi.body(activity, "");
        bytes.setTag("update_bytes");
        add(content, bytes, 4);

        Button action = ClinicalUi.button(activity, "", ClinicalUi.ButtonRole.PRIMARY);
        action.setTag("update_action");
        add(content, action, 20);
        Switch automatic = new Switch(activity);
        automatic.setTag("update_automatic");
        automatic.setText(R.string.app_update_auto_label);
        automatic.setTextColor(ClinicalUi.primaryText(activity));
        automatic.setTextSize(16);
        automatic.setMinHeight(ClinicalUi.dp(activity, 56));
        automatic.setSwitchPadding(ClinicalUi.dp(activity, 12));
        automatic.setChecked(p.getBoolean("enabled", true));
        add(content, automatic, 16);
        TextView autoHelp = ClinicalUi.body(activity, "");
        autoHelp.setTag("update_auto_help");
        add(content, autoHelp, 0);
        Button close = ClinicalUi.button(activity, activity.getString(R.string.app_update_close),
                ClinicalUi.ButtonRole.SECONDARY);
        add(content, close, 20);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(scroll).create();
        boolean[] receiptSeen = {false};
        Runnable render = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) { dialog.dismiss(); return; }
            String state = p.getString("state", "");
            if ("permission".equals(state) && AppUpdates.canInstall(activity)) state = "ready";
            boolean enabled = p.getBoolean("enabled", true);
            boolean busy = working(state) || AppUpdates.busy();
            boolean permission = !AppUpdates.canInstall(activity) && installStep(state);
            boolean alarm = installStep(state) && AppUpdates.alarmActive(activity);
            boolean onHome = MainActivity.onbacknr() == 0;
            String versionText = activity.getString(R.string.app_update_installed_version,
                    BuildConfig.DISPLAY_VERSION, BuildConfig.VERSION_CODE);
            long target = p.getLong("target", 0);
            if (target > BuildConfig.VERSION_CODE && !"current".equals(state))
                versionText += "\n" + activity.getString(R.string.app_update_available_version,
                        p.getString("target_name", ""), target);
            setText(versions, versionText);
            boolean receipt = p.getBoolean("update_unseen", false) && !busy
                    && (state.isEmpty() || "current".equals(state));
            receiptSeen[0] |= receipt;
            setText(status, receipt ? activity.getString(R.string.app_update_success, BuildConfig.DISPLAY_VERSION)
                    : activity.getString(headline(state)));
            setText(explanation, activity.getString(receipt ? R.string.app_update_reopen
                    : detail(state, enabled, alarm, onHome)));
            boolean downloading = "downloading".equals(state);
            progress.setVisibility(working(state) ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(!downloading || AppUpdates.totalBytes <= 0);
            progress.setProgress(percent(AppUpdates.downloadedBytes, AppUpdates.totalBytes));
            bytes.setVisibility(downloading && AppUpdates.totalBytes > 0 ? View.VISIBLE : View.GONE);
            if (downloading) setText(bytes, transferText(activity,
                    AppUpdates.downloadedBytes, AppUpdates.totalBytes));
            int button = permission ? R.string.app_update_allow
                    : "confirmation".equals(state) ? R.string.app_update_continue
                    : installStep(state) ? R.string.app_update_install
                    : "retry".equals(state) ? R.string.app_update_try_again : R.string.app_update_check;
            setText(action, activity.getString(busy ? R.string.app_update_in_progress : button));
            action.setEnabled(!busy && (!installStep(state) || (!alarm && onHome)));
            action.setAlpha(action.isEnabled() ? 1f : 0.5f);
            automatic.setEnabled(!"preparing".equals(state) && !"installing".equals(state));
            if (automatic.isChecked() != enabled) automatic.setChecked(enabled);
            setText(autoHelp, activity.getString(enabled ? R.string.app_update_auto_on : R.string.app_update_paused));
        };
        action.setOnClickListener(v -> {
            String state = p.getString("state", "");
            if (installStep(state)) {
                if (!AppUpdates.canInstall(activity)) {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                } else AppUpdates.installNow(activity);
            } else AppUpdates.check(activity, true, null);
            render.run();
        });
        automatic.setOnCheckedChangeListener((button, checked) -> {
            if (checked != p.getBoolean("enabled", true)) AppUpdates.setAutomatic(activity, checked);
            render.run();
        });
        close.setOnClickListener(v -> dialog.dismiss());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                render.run();
                if (dialog.isShowing()) content.postDelayed(this, 250);
            }
        };
        dialog.setOnDismissListener(d -> {
            content.removeCallbacks(tick);
            if (receiptSeen[0]) p.edit().putBoolean("update_unseen", false).apply();
            dismissed.run();
        });
        dialog.show();
        // Cap width on the unfolded Fold; ScrollView keeps every action reachable
        // in landscape, a narrow cover display and large accessibility font sizes.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(ClinicalUi.dp(activity, 480),
                    activity.getResources().getDisplayMetrics().widthPixels - ClinicalUi.dp(activity, 32)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        tick.run();
        return dialog;
    }

    private static void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ClinicalUi.dp(parent.getContext(), gap);
        parent.addView(child, params);
    }

    private static void setText(TextView view, CharSequence text) {
        if (!view.getText().toString().contentEquals(text)) view.setText(text);
    }
}
