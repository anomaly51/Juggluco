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

/** Persisted presentation language; sensor region and recorded data stay unchanged. */
public final class AppLanguage {
    static final String PREFERENCES = "app_language_v1";
    private static final String KEY = "language";
    private static volatile String language = "en";
    private static LocaleManager systemLocaleManager;

    private AppLanguage() {}

    public static String language() { return language; }

    private static String supported(String candidate) {
        return "ru".equals(candidate) ? "ru" : "en";
    }

    private static void initialize(Context context) {
        rememberSystemLocaleSource(context);
        String saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, "en");
        if (Build.VERSION.SDK_INT >= 33 && systemLocaleManager != null) {
            LocaleList locales = systemLocaleManager.getApplicationLocales();
            if (!locales.isEmpty()) saved = locales.get(0).getLanguage();
        }
        language = supported(saved);
    }

    /** Android's per-app language settings are also an authoritative user choice. */
    static void refreshFromPlatform(Context context) {
        rememberSystemLocaleSource(context);
        if (Build.VERSION.SDK_INT >= 33 && systemLocaleManager != null) {
            LocaleList locales = systemLocaleManager.getApplicationLocales();
            language = supported(locales.isEmpty() ? "en" : locales.get(0).getLanguage());
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putString(KEY, language).apply();
        }
    }

    static void select(Context context, String selected) {
        if (!"en".equals(selected) && !"ru".equals(selected))
            throw new IllegalArgumentException("Unsupported app language");
        language = selected;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY, selected).apply();
        Context app = context.getApplicationContext();
        if (app != null) apply(app);
        apply(context);
        rememberSystemLocaleSource(context);
        if (Build.VERSION.SDK_INT >= 33 && systemLocaleManager != null) {
            systemLocaleManager.setApplicationLocales(new LocaleList(regionalLocale()));
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(regionalLocale()));
        }
    }

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
        return new Locale(language, deviceLocale().getCountry());
    }

    static Configuration configuration(Configuration source) {
        Configuration result = new Configuration(source);
        if (Build.VERSION.SDK_INT >= 24) {
            result.setLocales(new LocaleList(regionalLocale()));
        } else {
            result.setLocale(regionalLocale());
        }
        result.setLayoutDirection(regionalLocale());
        return result;
    }

    static Context wrap(Context base) {
        initialize(base);
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
        initialize(application);
        // The framework applies this to services, notifications and plain
        // activities too. The country is retained for account/region defaults.
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = application.getSystemService(LocaleManager.class);
            LocaleList selected = new LocaleList(regionalLocale());
            if (manager != null && !selected.equals(manager.getApplicationLocales())) {
                manager.setApplicationLocales(selected);
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
        // Before API 33 use our saved preference, initialized by wrap(base).
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(regionalLocale()));
        }
    }
}
