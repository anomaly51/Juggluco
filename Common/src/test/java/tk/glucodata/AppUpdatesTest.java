package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdatesTest {
    Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        AppUpdates.prefs(context).edit().clear().commit();
    }
    @Test public void callbackIsBoundToPrivateNonceAndExpectedSession() {
        AppUpdates.prefs(context).edit().putInt("session",7).putString("nonce","test-nonce").commit();
        Intent result=new Intent(AppUpdates.RESULT).setData(Uri.parse("juggluco-update://result/test-nonce"))
                .putExtra(PackageInstaller.EXTRA_SESSION_ID,7);
        assertTrue(AppUpdates.validCallback(context,result));
        assertFalse(AppUpdates.validCallback(context,new Intent(result).putExtra(PackageInstaller.EXTRA_SESSION_ID,8)));
        assertFalse(AppUpdates.validCallback(context,new Intent(result).setData(Uri.parse("juggluco-update://result/wrong"))));
        assertFalse(AppUpdates.validCallback(context,new Intent(result).setAction("other")));
        AppUpdates.prefs(context).edit().clear().commit();
        assertFalse(AppUpdates.validCallback(context,result));
    }
    @Test public void refusesEmptyAndMismatchedSigningIdentities() {
        assertFalse(AppUpdateVerifier.sameSigners(Set.of(),Set.of()));
        assertFalse(AppUpdateVerifier.sameSigners(Set.of("one"),Set.of("two")));
        assertFalse(AppUpdateVerifier.sameSigners(Set.of("one","two"),Set.of("one")));
        assertTrue(AppUpdateVerifier.sameSigners(Set.of("one"),Set.of("one")));
    }
    @Test public void publicOrMissingSigningKeyNeverEnablesSilentUpdates() {
        assertFalse(AppUpdateVerifier.privateSigningIdentity(Set.of()));
        assertFalse(AppUpdateVerifier.privateSigningIdentity(Set.of(AppUpdateVerifier.PUBLIC_TEST_CERT)));
        assertFalse(AppUpdateVerifier.privateSigningIdentity(Set.of("private",AppUpdateVerifier.PUBLIC_TEST_CERT)));
        assertTrue(AppUpdateVerifier.privateSigningIdentity(Set.of("private")));
    }
    @Test public void rotationOnlyAcceptsPinnedSuccessorWithVerifiedHistory() {
        Set<String> old=Set.of(AppUpdateVerifier.PUBLIC_TEST_CERT);
        Set<String> next=Set.of(AppUpdateVerifier.RELEASE_CERT);
        Set<String> history=Set.of(AppUpdateVerifier.PUBLIC_TEST_CERT,AppUpdateVerifier.RELEASE_CERT);
        assertTrue(AppUpdateVerifier.acceptedSigners(old,next,history));
        assertFalse(AppUpdateVerifier.acceptedSigners(old,next,Set.of()));
        assertFalse(AppUpdateVerifier.acceptedSigners(old,next,old));
        assertFalse(AppUpdateVerifier.acceptedSigners(old,Set.of("attacker"),history));
        assertFalse(AppUpdateVerifier.acceptedSigners(next,old,history));
        assertFalse(AppUpdateVerifier.acceptedSigners(Set.of("unrelated"),next,history));
    }
    @Test public void privateKeyRequestsOsSilentInstallationMode() throws Exception {
        android.content.pm.PackageInfo pkg=AppUpdateVerifier.installed(context);
        pkg.signingInfo=null;
        pkg.signatures=new android.content.pm.Signature[]{
                new android.content.pm.Signature(new byte[]{1,2,3,4})};
        org.robolectric.Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        PackageInstaller.SessionParams params=AppUpdates.sessionParams(context,
                AppUpdateReleaseTest.select(AppUpdateReleaseTest.feed(AppUpdateReleaseTest.build())));
        java.lang.reflect.Field action=params.getClass().getDeclaredField("requireUserAction");
        action.setAccessible(true);
        assertEquals(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,action.getInt(params));
    }
    @Test public void everyStateHasAnEnglishExplanation() {
        for(String state:new String[]{"checking","downloading","ready","permission","confirmation","installing","current","retry",""})
            assertFalse(context.getString(AppUpdates.statusText(state)).isEmpty());
    }
    @Test public void versionComesFromTheActualBuild() {
        assertTrue(AppUpdates.versionLabel().contains(BuildConfig.DISPLAY_VERSION));
        assertTrue(AppUpdates.versionLabel().contains(String.valueOf(BuildConfig.VERSION_CODE)));
    }
    @Test public void versionIsBottomEndOutsideTheScrollableMenuAndPhotoSection() {
        Context themed=new ContextThemeWrapper(context,R.style.AppTheme_ClinicalDark);
        View root=LayoutInflater.from(themed).inflate(R.layout.menus,null,false);
        ViewGroup panel=root.findViewById(R.id.modern_menu_panel);
        ViewGroup footer=root.findViewById(R.id.modern_menu_footer);
        TextView version=root.findViewById(R.id.modern_menu_version);
        assertSame(footer,panel.getChildAt(panel.getChildCount()-1));
        assertSame(version,footer.getChildAt(footer.getChildCount()-1));
        assertEquals(Gravity.END,version.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
        assertFalse(version.isClickable());
        assertFalse(version.isFocusable());
        assertEquals(10f,version.getTextSize()
                /context.getResources().getDisplayMetrics().scaledDensity,0.1f);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,version.getLayoutParams().height);
        assertTrue(root.findViewById(R.id.modern_menu_updates).getMinimumHeight()
                >=48*context.getResources().getDisplayMetrics().density);
    }
}

