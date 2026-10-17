package tk.glucodata;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import java.util.function.Consumer;

/** Native checked choices: readable in either language, including after a mistaken selection. */
public final class AppLanguagePicker {
    private AppLanguagePicker() {}

    public static String selectedLabel() {
        return "ru".equals(AppLanguage.language()) ? "Русский" : "English";
    }

    public static void show(MainActivity activity) {
        create(activity, activity::changeAppLanguage).show();
    }

    static AlertDialog create(Context context, Consumer<String> onSelected) {
        return new AlertDialog.Builder(context)
                .setTitle(R.string.app_language_title)
                .setSingleChoiceItems(new String[]{"English", "Русский"},
                        "ru".equals(AppLanguage.language()) ? 1 : 0,
                        (dialog, which) -> {
                            dialog.dismiss();
                            onSelected.accept(which == 1 ? "ru" : "en");
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
