package tk.glucodata;

import android.app.Application;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdateReleaseTest {
    static JSONObject build() throws Exception {
        return new JSONObject().put("applicationId", "tk.glucodata.debug")
                .put("flavor", "mobileLibre3SiDexNogoogle").put("buildType", "debug")
                .put("abi", "arm64-v8a").put("versionCode", 901).put("versionName", "10.9.9")
                .put("size", 1000).put("minSdk", 21).put("targetSdk", 36)
                .put("sha256", "a".repeat(64))
                .put("url", "https://github.com/anomaly51/Juggluco/releases/download/android-v901/app.apk");
    }
    static String feed(JSONObject... builds) throws Exception {
        return new JSONObject().put("schema", 1).put("builds", new JSONArray(builds)).toString();
    }
    static AppUpdateRelease select(String data) throws Exception {
        return AppUpdateRelease.select(data, "tk.glucodata.debug", "mobileLibre3SiDexNogoogle",
                "debug", new String[]{"arm64-v8a"}, 35, 900);
    }
    @Test public void selectsNewCompatibleBuild() throws Exception {
        assertEquals(901, select(feed(build())).versionCode);
    }
    @Test public void choosesHighestCompatibleVersion() throws Exception {
        assertEquals(904, select(feed(build(), build().put("versionCode", 904))).versionCode);
    }
    @Test public void neverDowngradesOrReinstallsSameVersion() throws Exception {
        assertNull(select(feed(build().put("versionCode", 900))));
        assertNull(select(feed(build().put("versionCode", 899))));
    }
    @Test public void respectsAllCompatibilityDimensions() throws Exception {
        for (String field : new String[]{"applicationId", "flavor", "buildType", "abi"})
            assertNull(select(feed(build().put(field, "other"))));
        assertNull(select(feed(build().put("minSdk", 36))));
    }
    @Test public void noBuildsIsNotAnError() throws Exception { assertNull(select(feed())); }
    @Test public void rejectsUnknownSchemaAndMalformedInput() throws Exception {
        for (String data : new String[]{"{}", "[]", "garbage", "{\"schema\":2,\"builds\":[]}"})
            assertThrows(Exception.class, () -> select(data));
    }
    @Test public void rejectsInvalidMatchingMetadata() throws Exception {
        for (JSONObject b : new JSONObject[]{build().put("size", 0), build().put("size", 300000000),
                build().put("sha256", "wrong"), build().put("versionCode", 3000000000L),
                build().put("url", "http://github.com/anomaly51/Juggluco/releases/download/v1/app.apk"),
                build().put("versionName", ""), build().put("targetSdk", 18),
                build().put("versionCode", 901.5), build().put("size", "1000"),
                build().put("minSdk", 4294967317L)}) {
            assertThrows(Exception.class, () -> select(feed(b)));
        }
    }
    @Test public void onlyAllowsThisRepositoryAndHttpsAssetHosts() {
        assertTrue(AppUpdateRelease.isAllowedDownload(AppUpdateRelease.FEED));
        assertTrue(AppUpdateRelease.isReleaseUrl("https://github.com/anomaly51/Juggluco/releases/download/android-v901/app.apk"));
        assertTrue(AppUpdateRelease.isAllowedDownload("https://release-assets.githubusercontent.com/a?sig=123"));
        for (String url : new String[]{"https://evil.example/app.apk", "https://github.com/other/repo/releases/download/v1/app.apk",
                "https://github.com.evil.example/anomaly51/Juggluco/releases/download/v1/app.apk",
                "https://user@github.com/anomaly51/Juggluco/releases/download/v1/app.apk",
                "http://release-assets.githubusercontent.com/a", "file:///app.apk",
                "https://github.com:443/anomaly51/Juggluco/releases/download/v1/app.apk",
                "https://github.com/anomaly51/Juggluco/releases/download/v1/%2E%2E/app.apk",
                "https://github.com/anomaly51/Juggluco/releases/download/v1/app.apk#x"}) {
            assertFalse(url, AppUpdateRelease.isAllowedDownload(url));
        }
    }
}

