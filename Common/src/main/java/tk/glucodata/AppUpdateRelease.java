package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/** Public release metadata, never an executable command or a configurable update host. */
final class AppUpdateRelease {
    static final String REPOSITORY = "anomaly51/Juggluco";
    static final String FEED = "https://github.com/" + REPOSITORY
            + "/releases/latest/download/android-updates.json";
    static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    final long versionCode, size;
    final int minSdk, targetSdk;
    final String versionName, url, sha256;

    private AppUpdateRelease(JSONObject json) throws JSONException {
        versionCode = integer(json, "versionCode");
        versionName = json.getString("versionName");
        size = integer(json, "size");
        long minimum = integer(json, "minSdk");
        long target = integer(json, "targetSdk");
        if (minimum < 21 || target < minimum || minimum > 1000 || target > 1000)
            throw new JSONException("Invalid SDK range");
        minSdk = (int) minimum;
        targetSdk = (int) target;
        url = json.getString("url");
        sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
        if (versionCode <= 0 || versionCode > 2100000000L || size <= 0
                || size > MAX_APK_BYTES || minSdk < 21 || targetSdk < minSdk
                || versionName.length() > 100 || versionName.trim().isEmpty()
                || !sha256.matches("[0-9a-f]{64}") || !isReleaseUrl(url)
                || !url.endsWith(".apk")) throw new JSONException("Invalid Android release");
    }

    static AppUpdateRelease select(String data, String packageName, String flavor,
            String buildType, String[] abis, int sdk, long installed) throws JSONException {
        JSONObject root = new JSONObject(data);
        if (integer(root, "schema") != 1) throw new JSONException("Unknown release schema");
        JSONArray builds = root.getJSONArray("builds");
        if (builds.length() > 32) throw new JSONException("Too many builds");
        AppUpdateRelease selected = null;
        for (int i = 0; i < builds.length(); i++) {
            JSONObject build = builds.getJSONObject(i);
            if (!packageName.equals(build.getString("applicationId"))
                    || !flavor.equals(build.getString("flavor"))
                    || !buildType.equals(build.getString("buildType"))
                    || !Arrays.asList(abis).contains(build.getString("abi"))) continue;
            AppUpdateRelease candidate = new AppUpdateRelease(build);
            if (candidate.minSdk <= sdk && candidate.versionCode > installed
                    && (selected == null || candidate.versionCode > selected.versionCode)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new JSONException("Expected integer " + key);
        return ((Number) value).longValue();
    }

    static boolean isReleaseUrl(String value) {
        try {
            URI uri = new URI(value);
            return safeHttps(uri) && "github.com".equals(uri.getHost())
                    && uri.getRawQuery() == null
                    && uri.getRawPath().matches("/anomaly51/Juggluco/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
                    && !uri.getRawPath().contains("/../");
        } catch (Exception ignored) { return false; }
    }

    static boolean isAllowedDownload(String value) {
        if (FEED.equals(value) || isReleaseUrl(value)) return true;
        try {
            URI uri = new URI(value);
            return safeHttps(uri) && ("release-assets.githubusercontent.com".equals(uri.getHost())
                    || "objects.githubusercontent.com".equals(uri.getHost()));
        } catch (Exception ignored) { return false; }
    }

    private static boolean safeHttps(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && uri.getPort() == -1 && uri.getFragment() == null;
    }
}

