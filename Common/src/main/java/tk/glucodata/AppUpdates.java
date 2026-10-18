package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Phone-only updater. It never stops sensors/alarms to make an update eligible. */
public final class AppUpdates {
    static final String PREFS = "android_app_updates_v1";
    static final int JOB = 884100, NOTICE = 884101, CONNECTED_JOB = 884102;
    static final String CHANNEL = "app_updates_v1";
    static final String RESULT = "tk.glucodata.APP_UPDATE_RESULT";
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static boolean initialized;
    private static long lastForegroundCheck;
    private static boolean dialogShowing;
    private static AlertDialog settingsDialog;
    private static final AppUpdateGate gate = new AppUpdateGate();
    private static boolean wasOnline;
    private static final long CHECK_INTERVAL = 5 * 60 * 1000L;
    private static final Runnable foregroundTick = new Runnable() {
        @Override public void run() {
            Activity activity = foreground.get();
            if (!(activity instanceof MainActivity)) { gate.dismiss(); return; }
            boolean online = AppUpdateGate.internet(activity);
            boolean reconnected = online && !wasOnline;
            wasOnline = online;
            if (reconnected) lastForegroundCheck = 0; // Retain the retry if a transfer is still busy.
            refreshGate(activity);
            long now = android.os.SystemClock.elapsedRealtime();
            if ((online || Build.VERSION.SDK_INT < 23) && !busy()
                    && (reconnected || lastForegroundCheck == 0 || now - lastForegroundCheck >= CHECK_INTERVAL)) {
                lastForegroundCheck = now;
                check(activity, false, null);
            }
            // Also catches returning from a same-Activity editor, network loss,
            // and alarms that do not start a separate Activity. No server polling here.
            MAIN.postDelayed(this, 1000);
        }
    };
    private static volatile boolean manualInstall;
    private static volatile File preparedApk;
    private static volatile AppUpdateRelease preparedRelease;
    static volatile long downloadedBytes, totalBytes;
    private static Runnable deferredInstall;

    private AppUpdates() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void initialize(Application app) {
        if (initialized || Applic.isWearable || Build.VERSION.SDK_INT < 21) return;
        initialized = true;
        IO.execute(() -> {
            try {
                reconcile(app);
                String previous = prefs(app).getString("state", "");
                if ("checking".equals(previous) || "downloading".equals(previous)
                        || "verifying".equals(previous) || "preparing".equals(previous)) {
                    int session = prefs(app).getInt("session", -1);
                    if (session >= 0) abandon(app, session);
                    state(app, failureState(previous));
                }
            } catch (Exception ignored) { state(app, "retry"); }
        });
        schedule(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                foreground = new WeakReference<>(activity);
                if (!(activity instanceof MainActivity)) return;
                refreshGate(activity);
                MAIN.removeCallbacks(foregroundTick);
                MAIN.postDelayed(foregroundTick, 1000);
                boolean permissionNow = canInstall(app);
                String state = prefs(app).getString("state", "");
                boolean wasWaiting = "permission".equals(state);
                long now = android.os.SystemClock.elapsedRealtime();
                if ((AppUpdateGate.internet(app) || Build.VERSION.SDK_INT < 23)
                        && (now - lastForegroundCheck > CHECK_INTERVAL || lastForegroundCheck == 0
                        || (permissionNow && wasWaiting) || "ready".equals(state))) {
                    lastForegroundCheck = now;
                    wasOnline = AppUpdateGate.internet(app);
                    // Let the sensor UI initialize before doing any updater UI work.
                    MAIN.postDelayed(() -> check(app, false, null), 1500);
                }
            }
            @Override public void onActivityPaused(Activity activity) {
                if (foreground.get() == activity) {
                    foreground.clear();
                    MAIN.removeCallbacks(foregroundTick);
                    gate.dismiss();
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    static void schedule(Context context) {
        if (Build.VERSION.SDK_INT < 21) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        // Turning off automatic downloads does not turn off release discovery.
        try {
            JobInfo existing = Build.VERSION.SDK_INT >= 24 ? scheduler.getPendingJob(JOB) : null;
            if (existing == null) scheduler.schedule(new JobInfo.Builder(JOB,
                    new ComponentName(context, AppUpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(6L * 60 * 60 * 1000)
                    .setBackoffCriteria(15L * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
            // Queued at app startup/boot: if offline, runs when network becomes
            // available, subject to Android's background scheduling limits.
            scheduler.schedule(new JobInfo.Builder(CONNECTED_JOB,
                    new ComponentName(context, AppUpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(10000)
                    .setPersisted(true)
                    .setBackoffCriteria(15L * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (RuntimeException ignored) {
            // Foreground checks remain available when background jobs are restricted.
        }
    }

    static void check(Context context, boolean manual, Runnable finished) {
        check(context, manual, finished, new AtomicBoolean());
    }

    static void check(Context context, boolean manual, Runnable finished, AtomicBoolean cancelled) {
        check(context, manual, finished, cancelled,
                url -> (java.net.HttpURLConnection) url.openConnection());
    }

    static void check(Context context, boolean manual, Runnable finished, AtomicBoolean cancelled,
            AppUpdateDownload.Connections connections) {
        Context app = context.getApplicationContext();
        if (Applic.isWearable || !BUSY.compareAndSet(false, true)) {
            if (finished != null) MAIN.post(finished);
            return;
        }
        IO.execute(() -> {
            Runnable next = null;
            String previousState = prefs(app).getString("state", "");
            int pending = -1;
            try {
                reconcile(app);
                previousState = prefs(app).getString("state", "");
                pending = prefs(app).getInt("session", -1);
                if (pending >= 0 && manual) {
                    // A deliberate retry may replace a stuck/declined confirmation, never automatic loops.
                    abandon(app, pending);
                    pending = -1;
                }
                long previousTarget = prefs(app).getLong("target", 0);
                if (pending < 0) state(app, "checking");
                downloadedBytes = totalBytes = 0;
                AppUpdateDownload transfer = new AppUpdateDownload(cancelled, connections, (bytes, total) -> {
                            downloadedBytes = bytes;
                            totalBytes = total;
                        });
                String feed = transfer.manifest();
                AppUpdateRelease release = feed == null ? null : AppUpdateRelease.select(feed,
                        app.getPackageName(), BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE,
                        Build.SUPPORTED_ABIS, Build.VERSION.SDK_INT,
                        AppUpdateVerifier.version(AppUpdateVerifier.installed(app)));
                rememberRelease(app, release);
                if (release == null) {
                    // A withdrawn release must not leave a permanent cached lock.
                    // Do not interrupt a session Android has already committed.
                    if (pending >= 0) return;
                    preparedApk = null;
                    preparedRelease = null;
                    manualInstall = false;
                    state(app, "current");
                    return;
                }
                if (preparedRelease != null && (preparedRelease.versionCode != release.versionCode
                        || !preparedRelease.sha256.equals(release.sha256))) {
                    preparedRelease = null;
                    preparedApk = null;
                }
                if (pending >= 0) return; // Keep the existing OS confirmation/session.
                if (!manual && !prefs(app).getBoolean("enabled", true)) {
                    state(app, preparedRelease != null && preparedRelease.versionCode == release.versionCode
                            ? (canInstall(app) ? "ready" : "permission") : "available");
                    return;
                }
                if (!manual && previousTarget == release.versionCode
                        && System.currentTimeMillis() < prefs(app).getLong("retry_after", 0)) {
                    state(app, previousState);
                    return;
                }
                // Do not download/stage a second copy on every foreground check.
                if (preparedRelease != null && preparedApk != null && preparedApk.isFile()
                        && preparedRelease.versionCode == release.versionCode
                        && preparedRelease.sha256.equals(release.sha256)) {
                    state(app, canInstall(app) ? "ready" : "permission");
                    File cachedApk = preparedApk;
                    AppUpdateRelease cachedRelease = preparedRelease;
                    next = () -> prepareInstall(app, cachedApk, cachedRelease);
                    return;
                }
                preparedRelease = null;
                preparedApk = null;
                state(app, "downloading");
                File apk = transfer.apk(release, new File(app.getNoBackupFilesDir(), "app-updates"));
                if (cancelled.get()) { state(app, "retry"); return; }
                state(app, "verifying");
                AppUpdateVerifier.verify(app, apk, release);
                preparedApk = apk;
                preparedRelease = release;
                if (!canInstall(app)) {
                    state(app, "permission");
                    permissionNotice(app);
                    return;
                }
                // Keep the prepared file while an alarm, editor or other UI is active.
                state(app, "ready");
                next = () -> { if (!cancelled.get()) prepareInstall(app, apk, release); };
            } catch (Exception error) {
                if (pending < 0) {
                    manualInstall = false;
                    state(app, failureState(prefs(app).getString("state", "")));
                } // A feed outage must not overwrite a pending Android confirmation.
                prefs(app).edit().putLong("retry_after",
                        System.currentTimeMillis() + 15L * 60 * 1000).apply();
                android.util.Log.w("AppUpdates", "Update postponed: " + error.getClass().getSimpleName());
            } finally {
                BUSY.set(false);
                if (next != null) MAIN.post(next);
                if (finished != null) MAIN.post(finished);
            }
        });
    }

    static void state(Context context, String value) {
        prefs(context).edit().putString("state", value).apply();
        MAIN.post(() -> refreshGate(foreground.get()));
    }

    /** Only a successfully parsed, compatible HTTPS feed may create/clear a gate. */
    static void rememberRelease(Context context, AppUpdateRelease release) {
        SharedPreferences.Editor edit = prefs(context).edit();
        if (release == null) edit.remove("required_version").remove("target_confirmed");
        else edit.putLong("required_version", release.versionCode).putBoolean("target_confirmed", true);
        // The target of a committed installer session is immutable even if a
        // newer release appears (or the feed is withdrawn) while Android waits.
        if (prefs(context).getInt("session", -1) < 0) {
            if (release == null) edit.remove("target").remove("target_name");
            else edit.putLong("target", release.versionCode).putString("target_name", release.versionName);
        }
        edit.apply();
        MAIN.post(() -> refreshGate(foreground.get()));
    }

    private static void refreshGate(Activity activity) {
        if (!(activity instanceof MainActivity)) { gate.dismiss(); return; }
        boolean required = AppUpdateGate.required(activity);
        boolean alarm = required && alarmActive(activity);
        // A More drawer has no draft. Close just that drawer, never drain editors.
        if (required && !alarm && Menus.isShowing() && MainActivity.onbacknr() == 1)
            MainActivity.doonback();
        boolean show = AppUpdateGate.canPresent(required, alarm,
                MainActivity.onbacknr() != 0 || Menus.isShowing());
        if (show && settingsDialog != null) settingsDialog.dismiss();
        gate.render(activity, show);
    }

    /** Close the gap between release discovery and the next foreground UI tick. */
    static boolean interceptNavigation(Activity activity) {
        if (!AppUpdateGate.required(activity) || alarmActive(activity)) return false;
        refreshGate(activity);
        return true;
    }

    static String failureState(String phase) {
        return "checking".equals(phase) ? "check_failed" : "retry";
    }

    static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    static boolean alarmActive(Context context) {
        try {
            return Applic.stopprogram > 0 || CriticalGlucoseAlarm.currentSession(context) != null
                    || Natives.getisalarm();
        } catch (Throwable unavailable) {
            return true; // Cannot prove sensor/alarm state: fail closed.
        }
    }

    static boolean safeSurface(Context context) {
        Activity activity = foreground.get();
        return activity instanceof MainActivity && !activity.isFinishing()
                && MainActivity.onbacknr() == 0 && !Menus.isShowing() && (!dialogShowing || manualInstall)
                && !alarmActive(context);
    }

    static boolean installAllowed(boolean automatic, boolean manual) {
        return automatic || manual;
    }

    static boolean busy() { return BUSY.get(); }

    static void setAutomatic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
        schedule(context);
        if (enabled) check(context, false, null);
    }

    /** Explicit install also works with automatic updates off; never bypasses an alarm/editor. */
    static void installNow(Activity activity) {
        if (busy() || alarmActive(activity) || MainActivity.onbacknr() != 0) return;
        manualInstall = true;
        String current = prefs(activity).getString("state", "");
        if ("confirmation".equals(current)) {
            // The OS-owned PendingIntent survives a process restart. The receiving
            // Activity validates its nonce/session again before opening Android UI.
            PendingIntent pending = PendingIntent.getActivity(activity, NOTICE,
                    new Intent(activity, AppUpdateConfirmationActivity.class),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pending != null) {
                try { pending.send(); return; }
                catch (PendingIntent.CanceledException ignored) {}
            }
        }
        if (preparedRelease != null && preparedApk != null
                && prefs(activity).getInt("session", -1) < 0) {
            prepareInstall(activity.getApplicationContext(), preparedApk, preparedRelease);
        } else check(activity, true, null);
    }

    private static void deferInstall(Context context, File apk, AppUpdateRelease release) {
        if (deferredInstall != null) MAIN.removeCallbacks(deferredInstall);
        if (!(foreground.get() instanceof MainActivity)) return;
        deferredInstall = () -> prepareInstall(context, apk, release);
        MAIN.postDelayed(deferredInstall, 1000);
    }

    private static void prepareInstall(Context context, File apk, AppUpdateRelease release) {
        if (apk == null || release == null) return;
        if (preparedRelease != release) return; // A newer check superseded this deferred attempt.
        if (!installAllowed(prefs(context).getBoolean("enabled", true), manualInstall)
                || prefs(context).getInt("session", -1) >= 0) return;
        if (!canInstall(context)) {
            state(context, "permission");
            permissionNotice(context);
            return;
        }
        if (!safeSurface(context) || !installationNetworkAllowed(context)) {
            state(context, "ready");
            // More and the input editor share MainActivity; returning from them
            // does not trigger onResume. Poll only while that Activity is visible.
            deferInstall(context, apk, release);
            return;
        }
        // Stage off the UI thread. Re-check alarm/editor state immediately before commit.
        if (!BUSY.compareAndSet(false, true)) { deferInstall(context, apk, release); return; }
        state(context, "preparing");
        IO.execute(() -> {
            int id = -1;
            boolean handedToMain = false;
            try {
                AppUpdateVerifier.verify(context, apk, release);
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = sessionParams(context, release);
                id = installer.createSession(params);
                String token = UUID.randomUUID().toString();
                if (!prefs(context).edit().putInt("session", id).putString("nonce", token)
                        .putLong("session_at", System.currentTimeMillis()).commit())
                    throw new IllegalStateException("Cannot persist install session");
                try (PackageInstaller.Session session = installer.openSession(id);
                        FileInputStream input = new FileInputStream(apk);
                        OutputStream output = session.openWrite("base.apk", 0, release.size)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
                    session.fsync(output);
                }
                int sessionId = id;
                MAIN.post(() -> {
                    try {
                        if (!installAllowed(prefs(context).getBoolean("enabled", true), manualInstall)
                                || !safeSurface(context) || !installationNetworkAllowed(context)) {
                            abandon(context, sessionId);
                            state(context, "ready");
                            deferInstall(context, apk, release);
                            return;
                        }
                        state(context, "installing");
                        // A mutable explicit PendingIntent is required for installer result extras.
                        Intent result = new Intent(context, AppUpdateReceiver.class)
                                .setAction(RESULT).setData(Uri.parse("juggluco-update://result/" + token));
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                        PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result, flags);
                        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                            session.commit(callback.getIntentSender());
                        }
                    } catch (Exception error) {
                        manualInstall = false;
                        abandon(context, sessionId);
                        state(context, "retry");
                    } finally { BUSY.set(false); }
                });
                handedToMain = true;
            } catch (Exception error) {
                manualInstall = false;
                if (id >= 0) abandon(context, id);
                state(context, "retry");
            } finally { if (!handedToMain) BUSY.set(false); }
        });
    }

    private static boolean installationNetworkAllowed(Context context) {
        return manualInstall || Build.VERSION.SDK_INT < 23 || AppUpdateGate.internet(context);
    }

    static PackageInstaller.SessionParams sessionParams(Context context,
            AppUpdateRelease release) throws Exception {
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(release.size);
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(PackageManagerCompat.USER_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            // Shared upstream test signatures must not silently replace a medical application.
            boolean privateKey = AppUpdateVerifier.privateSigningIdentity(AppUpdateVerifier.installed(context));
            params.setRequireUserAction(privateKey
                    ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        return params;
    }

    static void abandon(Context context, int session) {
        try { context.getPackageManager().getPackageInstaller().abandonSession(session); }
        catch (RuntimeException ignored) {}
        prefs(context).edit().remove("session").remove("nonce").remove("session_at").apply();
        cancelNotice(context);
    }

    static void reconcile(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        long installed = AppUpdateVerifier.version(AppUpdateVerifier.installed(context));
        recordInstalledVersion(context, installed);
        int session = prefs.getInt("session", -1);
        if (session < 0) return;
        if (installed >= prefs.getLong("target", Long.MAX_VALUE)) {
            prefs.edit().remove("session").remove("nonce").remove("session_at")
                    .remove("retry_after").putString("state", "current").apply();
            cancelNotice(context);
            File cache = new File(context.getNoBackupFilesDir(), "app-updates");
            File[] completed = cache.listFiles();
            if (completed != null) for (File file : completed)
                if (file.isFile() && file.getName().matches("[0-9a-f]{64}\\.apk")) file.delete();
        } else if (context.getPackageManager().getPackageInstaller().getSessionInfo(session) == null
                || System.currentTimeMillis() - prefs.getLong("session_at", 0) > 24L * 60 * 60 * 1000) {
            abandon(context, session);
            state(context, "retry");
        }
    }

    /** A receipt means the installed package changed, not merely that an APK downloaded. */
    static void recordInstalledVersion(Context context, long installed) {
        SharedPreferences p = prefs(context);
        long previous = p.getLong("observed_version", installed);
        boolean legacyPending = !p.contains("observed_version") && p.getInt("session", -1) >= 0
                && installed >= p.getLong("target", Long.MAX_VALUE);
        SharedPreferences.Editor edit = p.edit().putLong("observed_version", installed);
        if (installed > previous || legacyPending)
            edit.putLong("updated_version", installed).putBoolean("update_unseen", true);
        edit.apply();
    }

    static boolean validCallback(Context context, Intent intent) {
        SharedPreferences p = prefs(context);
        String nonce = p.getString("nonce", "");
        return RESULT.equals(intent.getAction()) && !nonce.isEmpty()
                && ("juggluco-update://result/" + nonce).equals(intent.getDataString())
                && p.getInt("session", -1) >= 0
                && p.getInt("session", -1) == intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2);
    }

    static void result(Context context, Intent intent) {
        if (!validCallback(context, intent)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int session = prefs(context).getInt("session", -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) { abandon(context, session); state(context, "retry"); return; }
            state(context, "confirmation");
            Intent launch = new Intent(context, AppUpdateConfirmationActivity.class)
                    .putExtra("confirmation", confirmation).putExtra("session", session)
                    .putExtra("nonce", prefs(context).getString("nonce", ""))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notice(context, R.string.app_update_confirm, launch);
            if (safeSurface(context)) context.startActivity(launch);
        } else {
            manualInstall = false;
            if (status == PackageInstaller.STATUS_SUCCESS) {
                try { recordInstalledVersion(context,
                        AppUpdateVerifier.version(AppUpdateVerifier.installed(context))); }
                catch (Exception ignored) {}
            }
            abandon(context, session);
            state(context, status == PackageInstaller.STATUS_SUCCESS ? "current" : "retry");
            if (status != PackageInstaller.STATUS_SUCCESS) prefs(context).edit()
                    .putLong("retry_after", System.currentTimeMillis() + 24L * 60 * 60 * 1000).apply();
        }
    }

    private static void permissionNotice(Context context) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        notice(context, R.string.app_update_permission, settings);
        MAIN.post(() -> {
            long target = prefs(context).getLong("target", 0);
            if (!safeSurface(context) || target == prefs(context).getLong("permission_prompt", -1)) return;
            prefs(context).edit().putLong("permission_prompt", target).apply();
            showSettings(foreground.get());
        });
    }

    static void notice(Context context, int text, Intent action) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26)
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        context.getString(R.string.app_update_title), NotificationManager.IMPORTANCE_LOW));
            // Session cleanup must not erase the successful-update receipt.
            int notificationId = text == R.string.app_update_reopen ? NOTICE + 1 : NOTICE;
            PendingIntent pending = PendingIntent.getActivity(context, notificationId, action,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.app_update_title))
                    .setContentText(context.getString(text)).setContentIntent(pending)
                    .setOnlyAlertOnce(true).setAutoCancel(true).build());
        } catch (RuntimeException ignored) { /* More retains the update controls if notifications are denied. */ }
    }

    static void cancelNotice(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTICE);
    }

    static void acknowledgeReceipt(Context context) {
        prefs(context).edit().putBoolean("update_unseen", false).apply();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTICE + 1);
    }

    public static String versionLabel() {
        return "v" + BuildConfig.DISPLAY_VERSION + " · " + BuildConfig.VERSION_CODE;
    }

    static int statusText(String state) {
        switch (state) {
            case "checking": return R.string.app_update_checking;
            case "downloading": return R.string.app_update_downloading;
            case "verifying": return R.string.app_update_verifying;
            case "preparing": return R.string.app_update_preparing;
            case "ready": return R.string.app_update_ready;
            case "permission": return R.string.app_update_permission;
            case "confirmation": return R.string.app_update_confirm;
            case "installing": return R.string.app_update_installing;
            case "current": return R.string.app_update_current;
            case "available": return R.string.app_update_available_title;
            case "retry": return R.string.app_update_retry;
            case "check_failed": return R.string.app_update_check_failed_help;
            default: return R.string.app_update_automatic_description;
        }
    }

    public static void showSettings(Activity activity) {
        if (activity == null || activity.isFinishing() || dialogShowing) return;
        if (interceptNavigation(activity)) return;
        dialogShowing = true;
        settingsDialog = AppUpdatePanel.show(activity, () -> {
            dialogShowing = false;
            settingsDialog = null;
            if (preparedApk != null && preparedRelease != null)
                prepareInstall(activity.getApplicationContext(), preparedApk, preparedRelease);
        });
    }

    // Avoid loading a newer API constant from code paths on API 21.
    private static final class PackageManagerCompat { static final int USER_INSTALL = 4; }
}

