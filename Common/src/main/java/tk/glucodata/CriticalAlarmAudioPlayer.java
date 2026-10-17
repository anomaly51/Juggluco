package tk.glucodata;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Process-local, token-bound sound owner for one critical alarm episode.
 *
 * <p>The notification is the acknowledgement surface; this class is the sole
 * audio and vibration owner. Playback is deliberately independent from a
 * notification shade being opened. Every mutating entry point is synchronized
 * so a stale watchdog or acknowledgement cannot stop a replacement episode.</p>
 */
final class CriticalAlarmAudioPlayer {
    private static final AudioAttributes ALARM_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
    private static final AudioManager.OnAudioFocusChangeListener
            FOCUS_CHANGE_LISTENER = focusChange -> {
                // A critical alarm remains active until its session is
                // acknowledged. Focus changes are recorded by Android but do
                // not silently acknowledge or pause the episode.
            };

    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static AudioManager audioManager;
    private static AudioFocusRequest audioFocusRequest;
    private static String ownerToken;
    private static int ownerSoundRes;
    private static long[] ownerVibrationPattern;
    private static boolean focusGranted;
    private static int lastFocusRequestResult =
            AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    private static boolean foregroundPromoted;

    private CriticalAlarmAudioPlayer() {}

    /**
     * Starts a new episode or verifies an idempotent same-episode start.
     * A different token or sound strictly replaces the previous owner.
     */
    static synchronized boolean start(Context context, String token,
            int soundRes, long[] vibrationPattern) {
        Context app = applicationContext(context);
        if (app == null || !validToken(token) || soundRes == 0) return false;

        if (token.equals(ownerToken) && soundRes == ownerSoundRes
                && playerIsPlayingLocked()) {
            foregroundPromoted = promoteForeground();
            return foregroundPromoted
                    || !requiresForegroundProtection(app);
        }

        // Android 15+ only grants audio focus to a top app or an FGS. Promotion
        // is best effort: a failed focus request must not itself suppress a
        // life-critical attempt to play USAGE_ALARM audio.
        boolean promoted = promoteForeground();
        if (!promoted && requiresForegroundProtection(app)) {
            if (Log.doLog) Log.e("CriticalAlarmAudio",
                    "Refusing unprotected background alarm playback");
            return false;
        }
        releaseRuntimeLocked();
        ownerToken = token;
        ownerSoundRes = soundRes;
        ownerVibrationPattern = copyPattern(vibrationPattern);
        foregroundPromoted = promoted;

        if (startRuntimeLocked(app)) return true;

        clearOwnerLocked();
        demoteForeground();
        return false;
    }

    /**
     * Repairs playback only for the exact current owner. It never steals audio
     * for a stale token and never changes the selected sound implicitly.
     */
    static synchronized boolean ensurePlaying(Context context, String token,
            int soundRes, long[] vibrationPattern) {
        Context app = applicationContext(context);
        if (app == null || !validToken(token)
                || !token.equals(ownerToken) || soundRes != ownerSoundRes) {
            return false;
        }
        if (playerIsPlayingLocked()) {
            // Re-prove this on every watchdog tick. The service can be killed
            // and recreated while the application process and player survive.
            foregroundPromoted = promoteForeground();
            return foregroundPromoted
                    || !requiresForegroundProtection(app);
        }

        releaseRuntimeLocked();
        ownerVibrationPattern = copyPattern(vibrationPattern);
        foregroundPromoted = promoteForeground();
        if (!foregroundPromoted && requiresForegroundProtection(app)) {
            // Keep the immutable token metadata for a later watchdog retry,
            // but never claim that an Android 15+ background repair started.
            return false;
        }
        if (startRuntimeLocked(app)) return true;

        // Retain only immutable ownership metadata so the next token-bound
        // watchdog tick may retry. No MediaPlayer, focus or vibration resource
        // survives a failed repair.
        releaseRuntimeLocked();
        foregroundPromoted = false;
        demoteForeground();
        return false;
    }

    /** Stops only the exact owner; stale ACK/cleanup commands are harmless. */
    static synchronized boolean stop(Context context, String token) {
        if (!validToken(token) || !token.equals(ownerToken)) return false;
        releaseRuntimeLocked();
        clearOwnerLocked();
        demoteForeground();
        return true;
    }

    /** Recovery-only boundary for invalid persisted state or process teardown. */
    static synchronized boolean stopAll(Context context) {
        boolean hadOwner = ownerToken != null || player != null
                || vibrator != null || audioManager != null
                || foregroundPromoted;
        releaseRuntimeLocked();
        clearOwnerLocked();
        demoteForeground();
        return hadOwner;
    }

    static synchronized boolean owns(String token) {
        return validToken(token) && token.equals(ownerToken);
    }

    static synchronized boolean isPlaying(String token) {
        return owns(token) && playerIsPlayingLocked();
    }

    private static boolean startRuntimeLocked(Context app) {
        requestAudioFocusLocked(app);
        MediaPlayer candidate = null;
        try {
            candidate = new MediaPlayer();
            candidate.setAudioAttributes(ALARM_ATTRIBUTES);
            candidate.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
            candidate.setLooping(true);
            candidate.setOnErrorListener(
                    CriticalAlarmAudioPlayer::onPlaybackError);
            candidate.setOnCompletionListener(
                    CriticalAlarmAudioPlayer::onPlaybackCompleted);
            candidate.setDataSource(app, resourceUri(app, ownerSoundRes));
            candidate.prepare();

            // Publish the exact candidate before start. A synchronous OEM error
            // callback can then release it without leaving an unreachable
            // native player behind.
            player = candidate;
            candidate.start();
            if (player != candidate || !candidate.isPlaying()) {
                throw new IllegalStateException(
                        "Critical alarm MediaPlayer did not enter playing state");
            }
            startVibrationLocked(app, ownerVibrationPattern);
            if (Log.doLog) {
                Log.i("CriticalAlarmAudio", "playing token="
                        + Integer.toHexString(ownerToken.hashCode())
                        + " soundRes=" + ownerSoundRes
                        + " focusResult=" + lastFocusRequestResult
                        + " foreground=" + foregroundPromoted);
            }
            return true;
        } catch (Throwable failure) {
            Log.stack("CriticalAlarmAudio", "start", failure);
            if (player == candidate) player = null;
            releasePlayer(candidate);
            cancelVibrationLocked();
            abandonAudioFocusLocked();
            return false;
        }
    }

    private static void requestAudioFocusLocked(Context app) {
        focusGranted = false;
        lastFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        audioFocusRequest = null;
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(ALARM_ATTRIBUTES)
                        .setOnAudioFocusChangeListener(FOCUS_CHANGE_LISTENER)
                        .build();
                lastFocusRequestResult = audioManager.requestAudioFocus(
                        audioFocusRequest);
            } else {
                lastFocusRequestResult = audioManager.requestAudioFocus(
                        FOCUS_CHANGE_LISTENER, AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
            }
            focusGranted = lastFocusRequestResult
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable failure) {
            // Focus denial is observable but fail-open for the playback attempt.
            Log.stack("CriticalAlarmAudio", "requestAudioFocus", failure);
            focusGranted = false;
            lastFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            audioFocusRequest = null;
        }
    }

    private static boolean onPlaybackError(MediaPlayer failed, int what,
            int extra) {
        synchronized (CriticalAlarmAudioPlayer.class) {
            if (player == failed) {
                player = null;
                releasePlayer(failed);
                cancelVibrationLocked();
                abandonAudioFocusLocked();
                foregroundPromoted = false;
                demoteForeground();
                if (Log.doLog) Log.e("CriticalAlarmAudio",
                        "MediaPlayer error what=" + what + " extra=" + extra);
            } else {
                releasePlayer(failed);
            }
        }
        // The error was consumed; the token-bound watchdog owns retry policy.
        return true;
    }

    private static void onPlaybackCompleted(MediaPlayer completed) {
        synchronized (CriticalAlarmAudioPlayer.class) {
            if (player != completed) {
                releasePlayer(completed);
                return;
            }
            // Completion is unexpected for a looping player. Release it so
            // ensurePlaying() can perform a clean reconstruction.
            player = null;
            releasePlayer(completed);
            cancelVibrationLocked();
            abandonAudioFocusLocked();
            foregroundPromoted = false;
            demoteForeground();
        }
    }

    private static void startVibrationLocked(Context context, long[] pattern) {
        cancelVibrationLocked();
        if (!validVibrationPattern(pattern)) return;
        try {
            vibrator = vibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                vibrator = null;
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                        ALARM_ATTRIBUTES);
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Throwable failure) {
            Log.stack("CriticalAlarmAudio", "vibrate", failure);
            cancelVibrationLocked();
        }
    }

    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager)
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private static void releaseRuntimeLocked() {
        MediaPlayer oldPlayer = player;
        player = null;
        releasePlayer(oldPlayer);
        cancelVibrationLocked();
        abandonAudioFocusLocked();
    }

    private static void releasePlayer(MediaPlayer value) {
        if (value == null) return;
        try {
            value.setOnErrorListener(null);
            value.setOnCompletionListener(null);
        } catch (Throwable ignored) {}
        try {
            value.stop();
        } catch (Throwable ignored) {}
        try {
            value.reset();
        } catch (Throwable ignored) {}
        try {
            value.release();
        } catch (Throwable ignored) {}
    }

    private static void cancelVibrationLocked() {
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (Throwable ignored) {}
        vibrator = null;
    }

    private static void abandonAudioFocusLocked() {
        if (audioManager != null && focusGranted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(FOCUS_CHANGE_LISTENER);
                }
            } catch (Throwable failure) {
                Log.stack("CriticalAlarmAudio", "abandonAudioFocus", failure);
            }
        }
        audioManager = null;
        audioFocusRequest = null;
        focusGranted = false;
    }

    private static boolean playerIsPlayingLocked() {
        if (player == null) return false;
        try {
            return player.isPlaying();
        } catch (Throwable failure) {
            MediaPlayer failed = player;
            player = null;
            releasePlayer(failed);
            cancelVibrationLocked();
            abandonAudioFocusLocked();
            foregroundPromoted = false;
            demoteForeground();
            return false;
        }
    }

    private static boolean promoteForeground() {
        try {
            if (keeprunning.hasCriticalAlarmAudioForeground()) return true;
            return keeprunning.promoteCriticalAlarmAudio();
        } catch (Throwable failure) {
            Log.stack("CriticalAlarmAudio", "promote foreground", failure);
            return false;
        }
    }

    /** Android 15+ grants background audio focus only to a top app or FGS. */
    private static boolean requiresForegroundProtection(Context context) {
        if (Build.VERSION.SDK_INT < 35) return false;
        ActivityManager.RunningAppProcessInfo state =
                new ActivityManager.RunningAppProcessInfo();
        try {
            ActivityManager.getMyMemoryState(state);
            return state.importance
                    > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (RuntimeException unavailable) {
            // Fail closed: do not claim a life-critical background delivery
            // when Android would reject focus and may kill the process.
            return true;
        }
    }

    private static void demoteForeground() {
        try {
            keeprunning.demoteCriticalAlarmAudio();
        } catch (Throwable failure) {
            Log.stack("CriticalAlarmAudio", "demote foreground", failure);
        }
    }

    private static void clearOwnerLocked() {
        ownerToken = null;
        ownerSoundRes = 0;
        ownerVibrationPattern = null;
        foregroundPromoted = false;
    }

    private static Context applicationContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static boolean validToken(String token) {
        return token != null && !token.isEmpty();
    }

    private static long[] copyPattern(long[] pattern) {
        return pattern == null ? null : pattern.clone();
    }

    private static boolean validVibrationPattern(long[] pattern) {
        if (pattern == null || pattern.length == 0) return false;
        boolean hasPulse = false;
        for (long duration : pattern) {
            if (duration < 0L) return false;
            if (duration > 0L) hasPulse = true;
        }
        return hasPulse;
    }

    private static Uri resourceUri(Context context, int soundRes) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + context.getPackageName() + '/' + soundRes);
    }

    // Package-private state probes keep Robolectric tests out of reflection and
    // are intentionally read-only.
    static synchronized String ownerTokenForTests() { return ownerToken; }
    static synchronized int ownerSoundResForTests() { return ownerSoundRes; }
    static synchronized boolean focusGrantedForTests() { return focusGranted; }
    static synchronized int lastFocusRequestResultForTests() {
        return lastFocusRequestResult;
    }
    static synchronized boolean foregroundPromotedForTests() {
        return foregroundPromoted;
    }
    static synchronized MediaPlayer playerForTests() { return player; }
    static synchronized boolean vibratorActiveForTests() {
        return vibrator != null;
    }
}
