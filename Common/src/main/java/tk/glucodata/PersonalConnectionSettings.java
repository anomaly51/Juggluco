package tk.glucodata;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** A single, thumb-friendly place for provisioning, sharing and revoking a link. */
public final class PersonalConnectionSettings {
    public static void show(MainActivity activity) {
        show(activity,null);
    }

    public static void show(MainActivity activity,Runnable dismissed) {
        PersonalConnection connection=PersonalConnection.get(activity);
        PersonalConnectionView root=new PersonalConnectionView(activity);
        Runnable render=()-> {
            root.render(connection.progress(),connection.status(),connection.viewerUrl(),connection.busy(),connection.needsClaim());
            root.renderTiming(connection.startedAt(),connection.nextRetry(),connection.attempts(),
                    connection.verifiedAt(),connection.readingAt(),connection.progress());
        };
        Runnable clock=new Runnable() {
            public void run() {
                root.renderTiming(connection.startedAt(),connection.nextRetry(),connection.attempts(),
                        connection.verifiedAt(),connection.readingAt(),connection.progress());
                root.postDelayed(this,1000);
            }
        };
        Runnable close=()-> {
            connection.removeObserver(render);
            root.removeCallbacks(clock);
            ViewCompat.setOnApplyWindowInsetsListener(root,null);
            if(root.getParent() instanceof ViewGroup) ((ViewGroup)root.getParent()).removeView(root);
            activity.themeLightBars();
            if(dismissed!=null) dismissed.run();
        };
        root.back.setOnClickListener(view-> { MainActivity.poponback(); close.run(); });
        root.primary.setOnClickListener(view-> {
            if(!connection.viewerUrl().isEmpty()&&connection.progress()==100) {
                ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Juggluco",connection.viewerUrl()));
                Toast.makeText(activity,R.string.personal_connection_copied,Toast.LENGTH_SHORT).show();
            } else if(connection.needsClaim()) {
                new AlertDialog.Builder(activity).setTitle(R.string.personal_connection_claim)
                        .setMessage(R.string.personal_connection_claim_help)
                        .setNegativeButton(android.R.string.cancel,null)
                        .setPositiveButton(R.string.personal_connection_claim,(dialog,which)->connection.claimExisting()).show();
            } else connection.ensure(true);
        });
        root.open.setOnClickListener(view->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(connection.viewerUrl()))));
        root.change.setOnClickListener(view->showChange(activity,connection));
        root.recheck.setOnClickListener(view->connection.ensure(true));
        ViewCompat.setAccessibilityPaneTitle(root,activity.getString(R.string.personal_connection_title));
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)-> {
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|
                    WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());
            int gutter=ClinicalUi.readableHorizontalGutter(activity,view.getWidth()-bars.left-bars.right,16);
            view.setPadding(bars.left+gutter,bars.top,bars.right+gutter,bars.bottom+ClinicalUi.dp(activity,12));
            return insets;
        });
        ClinicalUi.reapplyInsetsOnWidthChanges(root);
        activity.addMyContentView(root,new ViewGroup.LayoutParams(-1,-1),false);
        MainActivity.setonback(close);
        connection.observe(render);
        connection.ensure(true);
        root.post(clock);
        ViewCompat.requestApplyInsets(root);
        activity.themeLightBars();
    }

    private static void showChange(MainActivity activity,PersonalConnection connection) {
        PersonalConnectionNameView fields=new PersonalConnectionNameView(activity);
        String original=connection.viewerUrl();
        boolean[] submitted={false};
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.personal_connection_change)
                .setView(ClinicalUi.scrollScreen(activity,fields)).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.personal_connection_change,null).create();
        Runnable observer=()-> {
            if(!submitted[0]) return;
            if(!original.equals(connection.viewerUrl())||(!connection.busy()&&connection.progress()==100)) {
                dialog.dismiss();
                return;
            }
            if(!connection.busy()) {
                if(connection.status()!=R.string.personal_connection_name_taken
                        &&connection.status()!=R.string.personal_connection_label_invalid) {
                    // Uncertain requests are retried durably by the connection
                    // screen. Do not accept a new name while the old one is pending.
                    dialog.dismiss();
                    return;
                }
                submitted[0]=false;
                fields.input.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.personal_connection_change);
                fields.showError(connection.status());
            }
        };
        dialog.setOnDismissListener(ignored->connection.removeObserver(observer));
        dialog.setOnShowListener(ignored-> {
            connection.observe(observer);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view-> {
                String value=fields.name();
                if(!PersonalConnectionPolicy.validLabel(value)) {
                    fields.showError(R.string.personal_connection_label_invalid);
                    return;
                }
                submitted[0]=true;
                fields.input.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.personal_connection_renaming);
                connection.rotate(value);
            });
        });
        dialog.show();
    }
}
