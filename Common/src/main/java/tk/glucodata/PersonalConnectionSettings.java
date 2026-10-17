package tk.glucodata;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** A single, thumb-friendly place for provisioning, sharing and revoking a link. */
public final class PersonalConnectionSettings {
    public static void show(MainActivity activity) {
        PersonalConnection connection=PersonalConnection.get(activity);
        PersonalConnectionView root=new PersonalConnectionView(activity);
        Runnable render=()->root.render(connection.progress(),connection.status(),connection.viewerUrl(),
                connection.busy(),connection.needsClaim());
        Runnable close=()-> {
            connection.removeObserver(render);
            ViewCompat.setOnApplyWindowInsetsListener(root,null);
            if(root.getParent() instanceof ViewGroup) ((ViewGroup)root.getParent()).removeView(root);
            activity.themeLightBars();
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
        ViewCompat.requestApplyInsets(root);
        activity.themeLightBars();
    }

    private static void showChange(MainActivity activity,PersonalConnection connection) {
        LinearLayout fields=ClinicalUi.verticalContent(activity);
        TextView label=ClinicalUi.body(activity,activity.getString(R.string.personal_connection_label));
        EditText input=new EditText(activity);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setMinHeight(ClinicalUi.dp(activity,56));
        input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setHint("my-sugar");
        input.setTextColor(ClinicalUi.primaryText(activity));
        fields.addView(label);
        fields.addView(input);
        fields.addView(ClinicalUi.body(activity,activity.getString(R.string.personal_connection_rotate_help)));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.personal_connection_change)
                .setView(fields).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.personal_connection_change,null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view-> {
            String value=input.getText().toString().trim();
            if(!PersonalConnectionPolicy.validLabel(value)) {
                input.setError(activity.getString(R.string.personal_connection_label_invalid));
                input.requestFocus();
                return;
            }
            connection.rotate(value);
            dialog.dismiss();
        }));
        dialog.show();
    }
}
