package tk.glucodata;

import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

/** Phone-only connection screen for the user-owned meal backend. */
public final class IntakeBackendSettings {
    private final MainActivity activity;
    private final IntakeRepository repository;
    private final Runnable onChanged;
    private View root;
    private EditText url;
    private EditText token;
    private TextView status;
    private Button test;
    private Button save;
    private boolean closed;

    private IntakeBackendSettings(MainActivity activity,Runnable onChanged) {
        this.activity=activity;
        this.onChanged=onChanged;
        repository=IntakeRepository.get(activity);
    }

    public static void show(MainActivity activity) {
        show(activity,null);
    }

    static void show(MainActivity activity,Runnable onChanged) {
        new IntakeBackendSettings(activity,onChanged).show();
    }

    private void show() {
        root=LayoutInflater.from(activity).inflate(
                R.layout.modern_intake_backend_settings,null,false);
        url=root.findViewById(R.id.intake_backend_url);
        token=root.findViewById(R.id.intake_backend_token);
        status=root.findViewById(R.id.intake_backend_test_status);
        test=root.findViewById(R.id.intake_backend_test);
        save=root.findViewById(R.id.intake_backend_save);
        url.setText(repository.backendUrl());
        token.setText(repository.backendToken());

        View page=root.findViewById(R.id.intake_backend_page);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)-> {
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    |WindowInsetsCompat.Type.displayCutout()
                    |WindowInsetsCompat.Type.ime());
            page.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            return insets;
        });

        root.findViewById(R.id.intake_backend_close).setOnClickListener(view->close(true));
        test.setOnClickListener(view->testConnection());
        save.setOnClickListener(view->saveAndClose());
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT),false);
        MainActivity.setonback(()->close(false));
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
    }

    private boolean storeFields() {
        try {
            if(!repository.configure(url.getText().toString(),
                    token.getText().toString())) {
                status.setVisibility(VISIBLE);
                status.setSelected(false);
                status.setText(R.string.intake_backend_change_pending);
                status.setTextColor(ContextCompat.getColor(activity,
                        R.color.modern_secondary_warning));
                return false;
            }
            url.setText(repository.backendUrl());
            if(onChanged!=null) onChanged.run();
            return true;
        } catch(IllegalArgumentException error) {
            url.setError(activity.getString(R.string.intake_backend_invalid_url));
            url.requestFocus();
            return false;
        }
    }

    private void testConnection() {
        if(!storeFields()) return;
        status.setVisibility(VISIBLE);
        status.setSelected(false);
        status.setText(R.string.intake_backend_checking);
        status.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_secondary));
        test.setEnabled(false);
        repository.health(new IntakeRepository.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if(closed) return;
                boolean ready="ok".equalsIgnoreCase(
                        value.optString("database",""))
                        &&value.optBoolean("auth_configured",false);
                boolean aiReady=value.optBoolean("ai_configured",false);
                status.setSelected(ready);
                status.setText(!ready?R.string.intake_backend_offline:
                        aiReady?R.string.intake_backend_test_ok:
                        R.string.intake_backend_ai_missing);
                status.setTextColor(ContextCompat.getColor(activity,
                        ready&&aiReady?
                        R.color.modern_secondary_accent:
                        R.color.modern_secondary_warning));
                test.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                if(closed) return;
                status.setSelected(false);
                status.setText(activity.getString(R.string.intake_backend_error,message));
                status.setTextColor(ContextCompat.getColor(activity,
                        R.color.modern_secondary_warning));
                test.setEnabled(true);
            }
        });
    }

    private void saveAndClose() {
        if(!storeFields()) return;
        close(true);
    }

    private void close(boolean popBack) {
        if(closed) return;
        closed=true;
        if(popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root,null);
        ViewParent parent=root.getParent();
        if(parent instanceof ViewGroup) ((ViewGroup)parent).removeView(root);
        activity.lightBars(false);
    }
}
