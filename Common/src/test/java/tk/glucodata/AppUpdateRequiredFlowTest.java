package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Looper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkInfo;
import static org.junit.Assert.*;

/** Exercises the actual release-check worker with an in-memory HTTPS transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdateRequiredFlowTest {
    Context context;
    ConnectivityManager connectivity;
    NetworkCapabilities capabilities;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        AppUpdates.prefs(context).edit().clear().putBoolean("enabled", false).commit();
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Shadows.shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true));
        capabilities = new NetworkCapabilities();
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Shadows.shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), capabilities);
    }

    JSONObject compatible() throws Exception {
        return AppUpdateReleaseTest.build().put("applicationId", context.getPackageName())
                .put("flavor", BuildConfig.FLAVOR).put("buildType", BuildConfig.BUILD_TYPE)
                .put("abi", Build.SUPPORTED_ABIS[0]).put("versionCode", BuildConfig.VERSION_CODE + 2);
    }

    void checkFeed(int status, String body) throws Exception {
        checkFeed(status, body, 0);
    }

    void checkFeed(int status, String body, int expectedDownloads) throws Exception {
        AtomicInteger feedRequests = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        AppUpdateDownloadTest.Connection response = new AppUpdateDownloadTest.Connection(
                status, body.getBytes(StandardCharsets.UTF_8));
        AppUpdates.check(context, false, null, new AtomicBoolean(), url -> {
            if (AppUpdateRelease.FEED.equals(url.toString())) {
                feedRequests.incrementAndGet();
                return response;
            }
            assertEquals("https://github.com/anomaly51/Juggluco/releases/download/android-v901/app.apk",
                    url.toString());
            downloads.incrementAndGet();
            // Exercise the actual automatic worker, without downloading or installing a real APK.
            throw new IOException("Synthetic APK transport failure");
        });
        AppUpdates.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, feedRequests.get());
        assertEquals("Legacy opt-out must not prevent automatic APK requests", expectedDownloads, downloads.get());
        assertFalse(AppUpdates.busy());
    }

    @Test public void legacyOptOutCannotStopDownloadAndOfflineReconnectAndWithdrawalStillWork() throws Exception {
        assertTrue(AppUpdateGate.internet(context));
        assertFalse(AppUpdateGate.required(context));
        checkFeed(200, AppUpdateReleaseTest.feed(compatible()), 1);
        assertFalse(AppUpdates.prefs(context).contains("enabled"));
        assertEquals("retry", AppUpdates.prefs(context).getString("state", ""));
        assertTrue(AppUpdateGate.required(context));
        Shadows.shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        assertFalse(AppUpdateGate.required(context));
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        assertTrue(AppUpdateGate.required(context));
        checkFeed(404, "");
        assertFalse(AppUpdateGate.required(context));
        assertEquals("current", AppUpdates.prefs(context).getString("state", ""));
    }

    @Test public void timeoutOrMalformedFeedCannotInventAnUpdateButRetainsAlreadyKnownOne() throws Exception {
        checkFeed(503, "temporarily unavailable");
        assertFalse(AppUpdateGate.required(context));
        checkFeed(200, "{}");
        assertFalse(AppUpdateGate.required(context));
        checkFeed(200, AppUpdateReleaseTest.feed(compatible()), 1);
        assertTrue(AppUpdateGate.required(context));
        checkFeed(503, "temporarily unavailable");
        assertTrue(AppUpdateGate.required(context));
        assertEquals("check_failed", AppUpdates.prefs(context).getString("state", ""));
    }

    @Test public void incompatibleAndInstalledBuildsNeverBlock() throws Exception {
        checkFeed(200, AppUpdateReleaseTest.feed(compatible().put("applicationId", "other.application")));
        assertFalse(AppUpdateGate.required(context));
        long installed = AppUpdateVerifier.version(AppUpdateVerifier.installed(context));
        checkFeed(200, AppUpdateReleaseTest.feed(compatible().put("versionCode", installed)));
        assertFalse(AppUpdateGate.required(context));
    }

    @Test public void alwaysAutomaticStillRespectsRetryBackoff() throws Exception {
        String feed = AppUpdateReleaseTest.feed(compatible());
        checkFeed(200, feed, 1);
        assertTrue(AppUpdates.prefs(context).getLong("retry_after", 0) > System.currentTimeMillis());
        checkFeed(200, feed); // Still refreshes metadata, but does not hammer a failed download.
        assertEquals("retry", AppUpdates.prefs(context).getString("state", ""));
        AppUpdates.prefs(context).edit().putLong("retry_after", 0).commit();
        checkFeed(200, feed, 1);
    }

    @Test public void feedFailureAndWithdrawalDoNotLosePendingAndroidConfirmation() throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int session = installer.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        long pendingVersion = BuildConfig.VERSION_CODE + 1;
        AppUpdates.prefs(context).edit().putInt("session", session).putLong("target", pendingVersion)
                .putLong("session_at", System.currentTimeMillis()).putString("state", "confirmation").commit();
        checkFeed(200, AppUpdateReleaseTest.feed(compatible()));
        assertFalse(AppUpdates.prefs(context).contains("enabled"));
        assertEquals(pendingVersion, AppUpdates.prefs(context).getLong("target", 0));
        assertTrue(AppUpdateGate.required(context));
        checkFeed(503, "unavailable");
        assertEquals("confirmation", AppUpdates.prefs(context).getString("state", ""));
        checkFeed(404, "");
        assertFalse(AppUpdateGate.required(context));
        assertEquals(session, AppUpdates.prefs(context).getInt("session", -1));
        assertEquals(pendingVersion, AppUpdates.prefs(context).getLong("target", 0));
        assertEquals("confirmation", AppUpdates.prefs(context).getString("state", ""));
    }
}
