package tk.glucodata;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

final class AppUpdateVerifier {
    // Upstream's public development keystore is not a private release identity.
    static final String PUBLIC_TEST_CERT =
            "364faa248fa32da0dafe11ab889415e9388b629b6e946d1a42d24e56c4e6669d";
    // Public fingerprint only. Never accept an arbitrary successor to the shared test key.
    static final String RELEASE_CERT =
            "d9436b37f1887acbd8cc212781fe5739738431b3b34d4b110dbc52096defdff8";

    static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), flags());
    }

    private static int flags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures)
            result.add(AppUpdateDownload.hex(MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())));
        return result;
    }

    static boolean sameSigners(Set<String> installed, Set<String> candidate) {
        return !installed.isEmpty() && installed.equals(candidate);
    }

    static boolean acceptedSigners(Set<String> installed, Set<String> candidate,
            Set<String> verifiedHistory) {
        if (sameSigners(installed, candidate)) return true;
        return installed.size() == 1 && installed.contains(PUBLIC_TEST_CERT)
                && candidate.size() == 1 && candidate.contains(RELEASE_CERT)
                && verifiedHistory.contains(PUBLIC_TEST_CERT)
                && verifiedHistory.contains(RELEASE_CERT);
    }

    static boolean compatibleSigners(PackageInfo installed, PackageInfo candidate) throws Exception {
        Set<String> history = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28 && candidate.signingInfo != null
                && !candidate.signingInfo.hasMultipleSigners()) {
            Signature[] past = candidate.signingInfo.getSigningCertificateHistory();
            if (past != null) for (Signature signature : past)
                history.add(AppUpdateDownload.hex(MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())));
        }
        return acceptedSigners(signers(installed), signers(candidate), history);
    }

    static boolean privateSigningIdentity(PackageInfo installed) throws Exception {
        return privateSigningIdentity(signers(installed));
    }

    static boolean privateSigningIdentity(Set<String> certificates) {
        return !certificates.isEmpty() && !certificates.contains(PUBLIC_TEST_CERT);
    }

    static void verify(Context context, File file, AppUpdateRelease release) throws Exception {
        if (file.length() != release.size || !release.sha256.equals(AppUpdateDownload.sha256(file)))
            throw new SecurityException("APK integrity check failed");
        PackageInfo current = installed(context);
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getPath(), flags());
        if (archive == null || !context.getPackageName().equals(archive.packageName)
                || version(archive) != release.versionCode || version(archive) <= version(current)
                || archive.applicationInfo == null
                || archive.applicationInfo.targetSdkVersion != release.targetSdk
                || (Build.VERSION.SDK_INT >= 24
                    && archive.applicationInfo.minSdkVersion != release.minSdk)
                || !compatibleSigners(current, archive))
            throw new SecurityException("APK does not match this installation");
        // PackageInstaller performs the authoritative cryptographic APK verification at commit.
    }
}

