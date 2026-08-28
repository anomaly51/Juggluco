package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Small, fail-safe helpers used while handing a critical alarm to Android.
 *
 * <p>The ringer switch does not mute {@link AudioManager#STREAM_ALARM}, but
 * the alarm stream itself can still be set to zero. A critical episode raises
 * only that stream to a bounded audible floor and restores the previous value
 * when the episode ends. No ringer mode or Do Not Disturb filter is changed.
 * Android and the user's notification-policy grant remain the final authority
 * over audio delivery.</p>
 */
final class CriticalAlarmAudioReliability {
    static final String PREFS_NAME = "critical_alarm_audio_reliability_v1";

    private static final String KEY_ORIGINAL_VOLUME = "original_alarm_volume";
    private static final String KEY_FORCED_VOLUME = "forced_alarm_volume";
    private static final String KEY_RESTORE_PENDING = "restore_pending";
    private static final String KEY_RESTORE_TOKEN = "restore_token";
    private static final int NO_SAVED_VOLUME = -1;
    private static final long DELIVERY_WAKE_LOCK_MS = 15_000L;
    private static final long OEM_ROUNDED_STEP_WINDOW_MS = 1_200L;

    private CriticalAlarmAudioReliability() {}

    /**
     * Makes a zero or barely audible alarm stream usable for this episode.
     * The original value is persisted before the global stream is touched so
     * a later cold start can still restore it after process death.
     */
    static synchronized boolean ensureAudibleAlarmVolume(Context context) {
        return ensureAudibleAlarmVolume(context,
                CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT);
    }

    static synchronized boolean ensureAudibleAlarmVolume(Context context,
            int minimumPercent) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        AudioManager audio = (AudioManager)
                app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.isVolumeFixed()) return false;

        final int current;
        final int maximum;
        try {
            current = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        } catch (RuntimeException unavailable) {
            return false;
        }
        SharedPreferences preferences = preferences(app);
        int target = minimumAudibleIndex(maximum, minimumPercent);
        if (target <= 0 || current >= target) {
            // A newer critical episode that is already audible must not inherit
            // an old post-ACK restore. The user may also have raised the stream
            // manually after that ACK, so leave the current value untouched.
            if (preferences.getBoolean(KEY_RESTORE_PENDING, false)) {
                clearSavedVolume(preferences);
            }
            return current > 0;
        }

        int savedOriginal = preferences.getInt(KEY_ORIGINAL_VOLUME,
                NO_SAVED_VOLUME);
        boolean firstAdjustment = savedOriginal == NO_SAVED_VOLUME;
        SharedPreferences.Editor editor = preferences.edit()
                .putInt(KEY_FORCED_VOLUME, target)
                .remove(KEY_RESTORE_PENDING)
                .remove(KEY_RESTORE_TOKEN);
        if (firstAdjustment) editor.putInt(KEY_ORIGINAL_VOLUME, current);
        // Persist first: a crash after setStreamVolume must not strand the
        // user's alarm volume at our temporary level forever.
        if (!editor.commit()) return false;

        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0);
            int appliedVolume = audio.getStreamVolume(
                    AudioManager.STREAM_ALARM);
            boolean applied = appliedVolume >= target;
            if (applied && appliedVolume != target) {
                preferences.edit().putInt(KEY_FORCED_VOLUME,
                        appliedVolume).commit();
            }
            if (!applied && firstAdjustment) clearSavedVolume(preferences);
            return applied;
        } catch (SecurityException | IllegalArgumentException denied) {
            if (firstAdjustment) clearSavedVolume(preferences);
            return false;
        } catch (RuntimeException unavailable) {
            if (firstAdjustment) clearSavedVolume(preferences);
            return false;
        }
    }

    /**
     * Restores only a value that still equals the one this class applied.
     * A manual volume change made during the alarm is therefore respected.
     */
    static synchronized boolean restoreAlarmVolume(Context context) {
        RestorePlan plan = prepareAlarmVolumeRestore(context, "");
        if (plan == null || !applyPreparedAlarmVolumeRestore(context, plan)) {
            return false;
        }
        clearSavedVolume(preferences(context.getApplicationContext()));
        return true;
    }

    /**
     * Validates manual-volume ownership before notification cancellation.
     * Applying the returned plan afterwards is safe even if an OEM changes the
     * stream while tearing down its alarm-channel audio owner.
     */
    static synchronized RestorePlan prepareAlarmVolumeRestore(Context context,
            String ownerToken) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        SharedPreferences preferences = preferences(app);
        int original = preferences.getInt(KEY_ORIGINAL_VOLUME,
                NO_SAVED_VOLUME);
        int forced = preferences.getInt(KEY_FORCED_VOLUME, NO_SAVED_VOLUME);
        if (original == NO_SAVED_VOLUME || forced == NO_SAVED_VOLUME) {
            clearSavedVolume(preferences);
            return null;
        }

        AudioManager audio = (AudioManager)
                app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.isVolumeFixed()) return null;
        try {
            int current = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            boolean pending = preferences.getBoolean(KEY_RESTORE_PENDING,
                    false);
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int safeOriginal = Math.max(0, Math.min(original, maximum));
            boolean samsungRoundedStep = isSamsungRoundedForcedStep(current,
                    safeOriginal, forced, Build.MANUFACTURER);
            if ((!pending && current != forced && !samsungRoundedStep)
                    || (pending && current != safeOriginal
                    && current != forced && !samsungRoundedStep)) {
                // The user or another audio owner deliberately changed it.
                clearSavedVolume(preferences);
                return null;
            }
            String pendingToken = preferences.getString(KEY_RESTORE_TOKEN, "");
            String token = pending && pendingToken != null
                    && !pendingToken.isEmpty() ? pendingToken
                    : ownerToken == null ? "" : ownerToken;
            return new RestorePlan(safeOriginal, forced, token,
                    SystemClock.elapsedRealtime()
                            + OEM_ROUNDED_STEP_WINDOW_MS);
        } catch (SecurityException | IllegalArgumentException denied) {
            return null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static synchronized boolean applyPreparedAlarmVolumeRestore(
            Context context, RestorePlan plan) {
        if (context == null || plan == null) return false;
        Context app = context.getApplicationContext();
        SharedPreferences preferences = preferences(app);
        if (!planMatches(preferences, plan)) return false;
        AudioManager audio = (AudioManager)
                app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.isVolumeFixed()) return false;
        if (!preferences.edit().putBoolean(KEY_RESTORE_PENDING, true)
                .putString(KEY_RESTORE_TOKEN, plan.ownerToken).commit()) {
            return false;
        }
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM,
                    plan.originalVolume, 0);
            return audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    == plan.originalVolume;
        } catch (SecurityException | IllegalArgumentException denied) {
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Re-applies only a recognizable OEM reassertion, never an arbitrary value. */
    static synchronized boolean retryPreparedAlarmVolumeRestore(
            Context context, RestorePlan plan, boolean finalAttempt) {
        if (context == null || plan == null) return false;
        Context app = context.getApplicationContext();
        SharedPreferences preferences = preferences(app);
        if (!planMatches(preferences, plan)
                || !preferences.getBoolean(KEY_RESTORE_PENDING, false)
                || !plan.ownerToken.equals(preferences.getString(
                KEY_RESTORE_TOKEN, ""))) return false;
        AudioManager audio = (AudioManager)
                app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.isVolumeFixed()) return false;
        try {
            int current = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            if (current != plan.originalVolume
                    && !looksLikeOemReassertion(current, plan)) {
                // A post-ACK manual change is more important than retrying our
                // restore. It is impossible to distinguish a manual selection
                // exactly equal to the OEM's forced step; all other values are
                // preserved here.
                clearSavedVolume(preferences);
                return false;
            }
            if (current != plan.originalVolume) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM,
                        plan.originalVolume, 0);
            }
            boolean restored = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    == plan.originalVolume;
            if (restored && finalAttempt) clearSavedVolume(preferences);
            return restored;
        } catch (SecurityException | IllegalArgumentException denied) {
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean planMatches(SharedPreferences preferences,
            RestorePlan plan) {
        return preferences.getInt(KEY_ORIGINAL_VOLUME, NO_SAVED_VOLUME)
                == plan.originalVolume
                && preferences.getInt(KEY_FORCED_VOLUME, NO_SAVED_VOLUME)
                == plan.forcedVolume;
    }

    private static boolean looksLikeOemReassertion(int current,
            RestorePlan plan) {
        if (current == plan.forcedVolume) return true;
        // Samsung may quantize the just-released channel-owned step down by
        // one. Treat that as OEM-owned only in the immediate teardown window;
        // afterwards the same value is considered a user's manual choice.
        return SystemClock.elapsedRealtime()
                <= plan.roundedStepDeadlineElapsedMs
                && plan.forcedVolume > plan.originalVolume
                && current != plan.originalVolume
                && current == plan.forcedVolume - 1;
    }

    /**
     * Samsung can expose its still-owned alarm-channel step one index below
     * the value just applied by {@link AudioManager#setStreamVolume}. This was
     * reproduced on the Fold device before ACK, so the short post-ACK clock
     * window cannot identify it. Limit that exception to Samsung and exactly
     * one step; every other during-alarm change remains user-owned.
     */
    static boolean isSamsungRoundedForcedStep(int current, int original,
            int forced, String manufacturer) {
        return manufacturer != null
                && "samsung".equalsIgnoreCase(manufacturer.trim())
                && forced > original
                && current != original
                && current == forced - 1;
    }

    static final class RestorePlan {
        final int originalVolume;
        final int forcedVolume;
        final String ownerToken;
        final long roundedStepDeadlineElapsedMs;

        RestorePlan(int originalVolume, int forcedVolume, String ownerToken,
                long roundedStepDeadlineElapsedMs) {
            this.originalVolume = originalVolume;
            this.forcedVolume = forcedVolume;
            this.ownerToken = ownerToken == null ? "" : ownerToken;
            this.roundedStepDeadlineElapsedMs = roundedStepDeadlineElapsedMs;
        }
    }

    static PowerManager.WakeLock acquireDeliveryWakeLock(Context context) {
        if (context == null) return null;
        try {
            PowerManager power = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (power == null) return null;
            PowerManager.WakeLock wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Juggluco::CriticalAlarmDelivery");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(DELIVERY_WAKE_LOCK_MS);
            return wakeLock;
        } catch (RuntimeException denied) {
            return null;
        }
    }

    static void releaseDeliveryWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
            // A timed wake lock may already have been released by Android.
        }
    }

    static int minimumAudibleIndex(int maximum) {
        return minimumAudibleIndex(maximum,
                CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT);
    }

    static int minimumAudibleIndex(int maximum, int minimumPercent) {
        if (maximum <= 0) return 0;
        int safePercent = CriticalAlertPreferences.validMinimumVolumePercent(
                minimumPercent) ? minimumPercent
                : CriticalAlertPreferences.DEFAULT_MINIMUM_VOLUME_PERCENT;
        return Math.max(1, Math.min(maximum,
                (int) Math.ceil(maximum * safePercent / 100d)));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void clearSavedVolume(SharedPreferences preferences) {
        preferences.edit()
                .remove(KEY_ORIGINAL_VOLUME)
                .remove(KEY_FORCED_VOLUME)
                .remove(KEY_RESTORE_PENDING)
                .remove(KEY_RESTORE_TOKEN)
                .commit();
    }
}
