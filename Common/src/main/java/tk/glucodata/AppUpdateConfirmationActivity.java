package tk.glucodata;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** A notification can carry the OS-owned confirmation across a process restart. */
public final class AppUpdateConfirmationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        String nonce = AppUpdates.prefs(this).getString("nonce", "");
        boolean valid = !nonce.isEmpty() && nonce.equals(request.getStringExtra("nonce"))
                && request.getIntExtra("session", -2) == AppUpdates.prefs(this).getInt("session", -1);
        Intent confirmation = request.getParcelableExtra("confirmation");
        if (valid && confirmation != null && !AppUpdates.alarmActive(this)) {
            try { startActivity(confirmation); }
            catch (RuntimeException ignored) { AppUpdates.state(this, "retry"); }
        } else if (valid) Toast.makeText(this, R.string.app_update_ready, Toast.LENGTH_LONG).show();
        finish();
    }
}

