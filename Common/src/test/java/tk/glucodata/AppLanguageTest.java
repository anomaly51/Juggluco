package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowLocaleManager;

import java.util.Locale;
import java.util.TimeZone;

/** English-only presentation checks with a synthetic app, never live sensor data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class AppLanguageTest {
    private Locale previousLocale;
    private Locale previousDisplayLocale;
    private Locale previousFormatLocale;
    private LocaleList previousLocales;
    private TimeZone previousTimeZone;
    private Configuration previousSystemConfiguration;

    @Before
    public void preserveProcessPresentationDefaults() {
        previousLocale = Locale.getDefault();
        previousDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        if (Build.VERSION.SDK_INT >= 24) previousLocales = LocaleList.getDefault();
        previousTimeZone = TimeZone.getDefault();
        previousSystemConfiguration = new Configuration(Resources.getSystem().getConfiguration());
    }

    @After
    public void restoreProcessPresentationDefaults() {
        Resources.getSystem().updateConfiguration(previousSystemConfiguration,
                Resources.getSystem().getDisplayMetrics());
        if (Build.VERSION.SDK_INT >= 24) LocaleList.setDefault(previousLocales);
        Locale.setDefault(previousLocale);
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplayLocale);
        Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale);
        TimeZone.setDefault(previousTimeZone);
    }

    @Test
    public void configurationPinsOneEnglishLocaleWithoutMutatingSource() {
        setSystemLocale("ru-RU");
        Configuration source = foreignConfiguration("ru-RU");
        source.setLocales(LocaleList.forLanguageTags("ru-RU,ar-EG,de-DE"));
        Configuration original = new Configuration(source);

        Configuration english = AppLanguage.configuration(source);

        assertNotSame(source, english);
        assertEquals(original, source);
        assertEnglish(english);
        assertEquals(Locale.ENGLISH, AppLanguage.LOCALE);
        assertDisplayConfigurationPreserved(source, english);
    }

    @Test
    public void arabicLayoutDirectionBecomesLtrWithoutChangingLargeTextOrFoldSize() {
        setSystemLocale("ar-EG");
        Configuration source = foreignConfiguration("ar-EG");
        source.orientation = Configuration.ORIENTATION_LANDSCAPE;
        source.screenWidthDp = 840;
        source.screenHeightDp = 700;
        source.smallestScreenWidthDp = 700;
        source.fontScale = 2f;
        assertEquals(View.LAYOUT_DIRECTION_RTL, source.getLayoutDirection());

        Configuration english = AppLanguage.configuration(source);

        assertEnglish(english);
        assertDisplayConfigurationPreserved(source, english);
        assertEquals(View.LAYOUT_DIRECTION_RTL, source.getLayoutDirection());
        assertEquals("ar", source.getLocales().get(0).getLanguage());
    }

    @Test
    public void wrapShowsEnglishAddSettingsAndAlarmLabelsOnRussianOrArabicSystem() {
        for (String language : new String[]{"ru-RU", "ar-EG"}) {
            setSystemLocale(language);
            Context foreign = foreignContext(language);
            Configuration original = new Configuration(foreign.getResources().getConfiguration());
            Context english = AppLanguage.wrap(foreign);

            assertEnglish(english.getResources().getConfiguration());
            assertEquals(original, foreign.getResources().getConfiguration());
            assertLabels(english);
            assertEquals(AppLanguage.regionalLocale(), Locale.getDefault());
            assertEquals(Locale.forLanguageTag(language).getCountry(),
                    english.getResources().getConfiguration().getLocales().get(0).getCountry());
            assertEquals(language, AppLanguage.deviceLocale().toLanguageTag());

            Context themed = new ContextThemeWrapper(english, R.style.AppTheme_ClinicalDark);
            View page = LayoutInflater.from(themed).inflate(R.layout.modern_meal_chat, null, false);
            assertEquals("Log it your way", ((TextView) page.findViewById(
                    R.id.intake_start_title)).getText().toString());
            assertEquals("Insulin", ((TextView) page.findViewById(
                    R.id.intake_start_insulin)).getText().toString());
            assertEquals("Food", ((TextView) page.findViewById(
                    R.id.intake_start_food)).getText().toString());
        }
    }

    @Test
    public void applyRepinsRuntimeResourcesAfterADeviceLanguageChange() {
        Context context = foreignContext("ru-RU");
        AppLanguage.apply(context);
        assertEnglish(context.getResources().getConfiguration());
        assertLabels(context);

        Configuration arabic = foreignConfiguration("ar-EG");
        arabic.orientation = Configuration.ORIENTATION_LANDSCAPE;
        context.getResources().updateConfiguration(arabic, context.getResources().getDisplayMetrics());
        AppLanguage.apply(context);

        assertEnglish(context.getResources().getConfiguration());
        assertDisplayConfigurationPreserved(arabic, context.getResources().getConfiguration());
        assertLabels(context);
    }

    @Test
    public void forcingPresentationLanguageDoesNotMoveDeviceTimeZone() {
        TimeZone expected = TimeZone.getTimeZone("Asia/Tokyo");
        TimeZone.setDefault(expected);
        Context english = AppLanguage.wrap(foreignContext("ru-RU"));
        AppLanguage.apply(english);
        assertEquals(expected.getID(), TimeZone.getDefault().getID());
        assertEquals(expected.getRawOffset(), TimeZone.getDefault().getRawOffset());
    }

    @Test
    public void installPinsAndroidPerAppLanguageAndApplicationResources() {
        setSystemLocale("ru-RU");
        Application app = RuntimeEnvironment.getApplication();
        LocaleManager manager = app.getSystemService(LocaleManager.class);
        assertNotNull(manager);
        manager.setApplicationLocales(LocaleList.forLanguageTags("ru-RU"));

        AppLanguage.install(app);

        assertEquals("en-RU", manager.getApplicationLocales().toLanguageTags());
        assertEnglish(app.getResources().getConfiguration());
        assertLabels(app);
    }

    @Test
    @Config(shadows = IndependentSystemLocaleManager.class)
    public void trueSystemRegionWinsOverOldAppOverrideAndFollowsDeviceChanges() {
        // An app-specific override may already have reached process resources.
        // Keep the framework's true system-locale channel independent of it.
        setSystemLocale("ru-RU");
        Application app = RuntimeEnvironment.getApplication();
        app.getResources().updateConfiguration(foreignConfiguration("ru-RU"),
                app.getResources().getDisplayMetrics());
        IndependentSystemLocaleManager.systemLocales = LocaleList.forLanguageTags("uk-UA");
        LocaleManager manager = app.getSystemService(LocaleManager.class);
        manager.setApplicationLocales(LocaleList.forLanguageTags("ru-RU"));
        assertEquals("ru-RU", Resources.getSystem().getConfiguration().getLocales().toLanguageTags());
        assertEquals("uk-UA", manager.getSystemLocales().toLanguageTags());

        AppLanguage.install(app);

        assertEquals("en-UA", manager.getApplicationLocales().toLanguageTags());
        assertEquals("uk-UA", AppLanguage.deviceLocale().toLanguageTag());
        assertEquals("UA", AppLanguage.regionalLocale().getCountry());
        assertEnglish(app.getResources().getConfiguration());
        assertLabels(app);

        // The app is still pinned to its previous regional locale when the
        // user changes the actual device region; stale app state must not win.
        setSystemLocale("en-UA");
        IndependentSystemLocaleManager.systemLocales = LocaleList.forLanguageTags("pl-PL");
        assertEquals("en-UA", manager.getApplicationLocales().toLanguageTags());
        assertEquals("pl-PL", AppLanguage.deviceLocale().toLanguageTag());
        AppLanguage.install(app);
        assertEquals("en-PL", manager.getApplicationLocales().toLanguageTags());
        assertEquals("PL", AppLanguage.regionalLocale().getCountry());
        assertEnglish(app.getResources().getConfiguration());
        assertLabels(app);
    }

    @Test
    @Config(sdk = 28, application = Application.class)
    public void preAndroid13ActivityAttachmentInitializesEnglishAppCompatLocale() {
        setSystemLocale("ar-EG");
        AppLanguage.beforeActivityAttach();
        assertEquals("en-EG", AppCompatDelegate.getApplicationLocales().toLanguageTags());
    }

    @Test
    @Config(sdk = 23, application = Application.class)
    public void legacyApiPinsEnglishAndLtrWithoutLocaleListApi() {
        setSystemLocale("ar-EG");
        Configuration source = foreignConfiguration("ar-EG");
        Configuration original = new Configuration(source);

        Configuration english = AppLanguage.configuration(source);
        Context wrapped = AppLanguage.wrap(foreignContext("ar-EG"));

        assertEquals(new Locale("en", "EG"), english.locale);
        assertEquals(View.LAYOUT_DIRECTION_LTR, english.getLayoutDirection());
        assertEquals(original, source);
        assertDisplayConfigurationPreserved(source, english);
        assertLabels(wrapped);
    }

    private static Context foreignContext(String language) {
        return RuntimeEnvironment.getApplication().createConfigurationContext(
                foreignConfiguration(language));
    }

    private static Configuration foreignConfiguration(String language) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication()
                .getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(language);
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        config.fontScale = 1.75f;
        config.screenWidthDp = 360;
        config.screenHeightDp = 900;
        config.smallestScreenWidthDp = 360;
        config.densityDpi = 420;
        config.orientation = Configuration.ORIENTATION_PORTRAIT;
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_YES;
        return config;
    }

    private static void assertEnglish(Configuration config) {
        assertEquals("en", config.getLocales().get(0).getLanguage());
        assertEquals(AppLanguage.deviceLocale().getCountry(),
                config.getLocales().get(0).getCountry());
        assertEquals(1, config.getLocales().size());
        assertEquals(View.LAYOUT_DIRECTION_LTR, config.getLayoutDirection());
    }

    private static void assertDisplayConfigurationPreserved(Configuration expected,
            Configuration actual) {
        assertEquals(expected.fontScale, actual.fontScale, .001f);
        assertEquals(expected.screenWidthDp, actual.screenWidthDp);
        assertEquals(expected.screenHeightDp, actual.screenHeightDp);
        assertEquals(expected.smallestScreenWidthDp, actual.smallestScreenWidthDp);
        assertEquals(expected.densityDpi, actual.densityDpi);
        assertEquals(expected.orientation, actual.orientation);
        assertEquals(expected.uiMode, actual.uiMode);
        assertEquals(expected.screenLayout & ~Configuration.SCREENLAYOUT_LAYOUTDIR_MASK,
                actual.screenLayout & ~Configuration.SCREENLAYOUT_LAYOUTDIR_MASK);
    }

    private static void assertLabels(Context context) {
        assertEquals("ADD ENTRY", context.getString(R.string.intake_thumb_title));
        assertEquals("Settings", context.getString(R.string.settings));
        assertEquals("Alarms", context.getString(R.string.alarms));
        assertEquals("Low glucose", context.getString(R.string.critical_alarm_banner_low));
        assertEquals("High glucose", context.getString(R.string.critical_alarm_banner_high));
    }

    private static void setSystemLocale(String language) {
        Resources system = Resources.getSystem();
        Configuration config = new Configuration(system.getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        system.updateConfiguration(config, system.getDisplayMetrics());
    }

    /** The stock shadow aliases system locales to process resources. */
    @Implements(value = LocaleManager.class, minSdk = 33)
    public static class IndependentSystemLocaleManager extends ShadowLocaleManager {
        static LocaleList systemLocales = LocaleList.getEmptyLocaleList();

        @Implementation
        @Override protected LocaleList getSystemLocales() {
            return systemLocales;
        }

        @Resetter
        public static void reset() {
            ShadowLocaleManager.reset();
            systemLocales = LocaleList.getEmptyLocaleList();
        }
    }
}
