package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.Application;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Process;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.util.Collections;

/** State-machine regressions for the process-local critical audio owner. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmAudioPlayerTest {
    private static final String FIRST_TOKEN = "episode-one";
    private static final String SECOND_TOKEN = "episode-two";

    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        CriticalAlarmAudioPlayer.stopAll(application);
        ShadowMediaPlayer.resetStaticState();
        addMediaInfo(R.raw.siren);
        addMediaInfo(R.raw.verylow);
    }

    @After
    public void tearDown() {
        CriticalAlarmAudioPlayer.stopAll(application);
        ShadowMediaPlayer.resetStaticState();
    }

    @Test
    public void sameTokenAndSoundIsIdempotent() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        MediaPlayer first = CriticalAlarmAudioPlayer.playerForTests();
        assertNotNull(first);
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(FIRST_TOKEN));

        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));

        assertSame(first, CriticalAlarmAudioPlayer.playerForTests());
        assertTrue(CriticalAlarmAudioPlayer.owns(FIRST_TOKEN));
    }

    @Test
    public void newTokenStrictlyReplacesOldOwner() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        MediaPlayer first = CriticalAlarmAudioPlayer.playerForTests();

        assertTrue(CriticalAlarmAudioPlayer.start(application, SECOND_TOKEN,
                R.raw.verylow, null));

        assertNotSame(first, CriticalAlarmAudioPlayer.playerForTests());
        assertFalse(CriticalAlarmAudioPlayer.owns(FIRST_TOKEN));
        assertTrue(CriticalAlarmAudioPlayer.owns(SECOND_TOKEN));
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(SECOND_TOKEN));
    }

    @Test
    public void staleStopCannotAffectReplacementOwner() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        assertTrue(CriticalAlarmAudioPlayer.start(application, SECOND_TOKEN,
                R.raw.verylow, null));
        MediaPlayer replacement = CriticalAlarmAudioPlayer.playerForTests();

        assertFalse(CriticalAlarmAudioPlayer.stop(application, FIRST_TOKEN));

        assertSame(replacement, CriticalAlarmAudioPlayer.playerForTests());
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(SECOND_TOKEN));
    }

    @Test
    public void ensureRepairsStoppedPlayerWithoutChangingOwner() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        MediaPlayer failed = CriticalAlarmAudioPlayer.playerForTests();
        shadowOf(failed).setState(ShadowMediaPlayer.State.STOPPED);
        assertFalse(CriticalAlarmAudioPlayer.isPlaying(FIRST_TOKEN));

        assertTrue(CriticalAlarmAudioPlayer.ensurePlaying(application,
                FIRST_TOKEN, R.raw.siren, null));

        assertNotSame(failed, CriticalAlarmAudioPlayer.playerForTests());
        assertTrue(CriticalAlarmAudioPlayer.owns(FIRST_TOKEN));
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(FIRST_TOKEN));
    }

    @Test
    public void playerErrorReleasesRuntimeButKeepsTokenForWatchdogRepair() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        MediaPlayer failed = CriticalAlarmAudioPlayer.playerForTests();

        shadowOf(failed).invokeErrorListener(
                MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);

        assertNull(CriticalAlarmAudioPlayer.playerForTests());
        assertTrue(CriticalAlarmAudioPlayer.owns(FIRST_TOKEN));
        assertFalse(CriticalAlarmAudioPlayer.focusGrantedForTests());
        assertTrue(CriticalAlarmAudioPlayer.ensurePlaying(application,
                FIRST_TOKEN, R.raw.siren, null));
        assertTrue(CriticalAlarmAudioPlayer.isPlaying(FIRST_TOKEN));
    }

    @Test
    public void backgroundRepairDoesNotClaimPlaybackWithoutForegroundService() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));
        MediaPlayer failed = CriticalAlarmAudioPlayer.playerForTests();
        shadowOf(failed).setState(ShadowMediaPlayer.State.STOPPED);
        setProcessImportance(ActivityManager.RunningAppProcessInfo
                .IMPORTANCE_SERVICE);

        assertFalse(CriticalAlarmAudioPlayer.ensurePlaying(application,
                FIRST_TOKEN, R.raw.siren, null));

        assertTrue(CriticalAlarmAudioPlayer.owns(FIRST_TOKEN));
        assertNull(CriticalAlarmAudioPlayer.playerForTests());
        setProcessImportance(ActivityManager.RunningAppProcessInfo
                .IMPORTANCE_FOREGROUND);
    }

    @Test
    public void stopReleasesExactOwner() {
        assertTrue(CriticalAlarmAudioPlayer.start(application, FIRST_TOKEN,
                R.raw.siren, null));

        assertTrue(CriticalAlarmAudioPlayer.stop(application, FIRST_TOKEN));

        assertNull(CriticalAlarmAudioPlayer.ownerTokenForTests());
        assertNull(CriticalAlarmAudioPlayer.playerForTests());
        assertFalse(CriticalAlarmAudioPlayer.focusGrantedForTests());
        assertFalse(CriticalAlarmAudioPlayer.vibratorActiveForTests());
    }

    private void addMediaInfo(int soundRes) {
        Uri uri = Uri.parse("android.resource://" + application.getPackageName()
                + '/' + soundRes);
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(application, uri),
                new ShadowMediaPlayer.MediaInfo(60_000, 0));
    }

    private void setProcessImportance(int importance) {
        ActivityManager.RunningAppProcessInfo process =
                new ActivityManager.RunningAppProcessInfo();
        process.pid = Process.myPid();
        process.importance = importance;
        ActivityManager manager = (ActivityManager) application
                .getSystemService(Application.ACTIVITY_SERVICE);
        shadowOf(manager).setProcesses(Collections.singletonList(process));
    }
}
