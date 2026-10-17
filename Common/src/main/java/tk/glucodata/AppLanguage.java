package tk.glucodata;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** One UI language, independent of the device language and intake input. */
public final class AppLanguage {
    public static final Locale LOCALE = Locale.ENGLISH;
    private static LocaleManager systemLocaleManager;

    private AppLanguage() {}

    /** Region still controls sensor/account defaults; changing UI must not change it. */
    public static Locale deviceLocale() {
        if (Build.VERSION.SDK_INT >= 33 && systemLocaleManager != null) {
            // Resources.getSystem() may carry an app-specific override in a
            // running process. The framework API explicitly ignores it.
            LocaleList systemLocales = systemLocaleManager.getSystemLocales();
            if (!systemLocales.isEmpty()) return systemLocales.get(0);
        }
        Configuration system = Resources.getSystem().getConfiguration();
        if (Build.VERSION.SDK_INT >= 24 && !system.getLocales().isEmpty()) {
            return system.getLocales().get(0);
        }
        return system.locale == null ? Locale.getDefault() : system.locale;
    }

    public static Locale regionalLocale() {
        return new Locale("en", deviceLocale().getCountry());
    }

    static Configuration configuration(Configuration source) {
        Configuration result = new Configuration(source);
        if (Build.VERSION.SDK_INT >= 24) {
            result.setLocales(new LocaleList(regionalLocale()));
        } else {
            result.setLocale(regionalLocale());
        }
        result.setLayoutDirection(LOCALE);
        return result;
    }

    static Context wrap(Context base) {
        rememberSystemLocaleSource(base);
        Locale.setDefault(regionalLocale());
        return base.createConfigurationContext(configuration(
                base.getResources().getConfiguration()));
    }

    @SuppressWarnings("deprecation")
    static void apply(Context context) {
        Locale.setDefault(regionalLocale());
        Resources resources = context.getResources();
        resources.updateConfiguration(configuration(resources.getConfiguration()),
                resources.getDisplayMetrics());
    }

    static void install(Application application) {
        rememberSystemLocaleSource(application);
        // The framework applies this to services, notifications and plain
        // activities too. The country is retained for account/region defaults.
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = application.getSystemService(LocaleManager.class);
            LocaleList english = new LocaleList(regionalLocale());
            if (manager != null && !english.equals(manager.getApplicationLocales())) {
                manager.setApplicationLocales(english);
            }
        }
        apply(application);
    }

    private static void rememberSystemLocaleSource(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Retain only the application-scoped service, never an activity.
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            systemLocaleManager = app.getSystemService(LocaleManager.class);
        }
    }

    static void beforeActivityAttach() {
        // Before API 33 we own the fixed preference rather than restoring a
        // previous device-default/Russian AppCompat preference on cold start.
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(regionalLocale()));
        }
    }
}
