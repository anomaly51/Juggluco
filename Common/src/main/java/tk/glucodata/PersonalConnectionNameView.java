package tk.glucodata;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Small, scrollable-in-dialog name editor with an exact URL preview. */
final class PersonalConnectionNameView extends LinearLayout {
    final EditText input;
    final TextView preview;

    PersonalConnectionNameView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        int padding=ClinicalUi.dp(context,20);
        setPadding(padding,padding,padding,padding);
        TextView label=ClinicalUi.body(context,context.getString(R.string.personal_connection_label));
        input=new EditText(context);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setMinHeight(ClinicalUi.dp(context,56));
        input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(63)});
        input.setHint("my-sugar");
        input.setTextColor(ClinicalUi.primaryText(context));
        preview=ClinicalUi.body(context,"");
        preview.setTextColor(ClinicalUi.accent(context));
        addView(label);
        addView(input,new LayoutParams(-1,-2));
        addView(preview);
        addView(ClinicalUi.body(context,context.getString(R.string.personal_connection_rotate_help)));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                input.setError(null);
                updatePreview();
            }
            public void afterTextChanged(Editable value) {}
        });
        updatePreview();
    }

    String name() { return input.getText().toString().trim().toLowerCase(Locale.ROOT); }

    private void updatePreview() {
        String value=name();
        preview.setText(value.isEmpty()?getContext().getString(R.string.personal_connection_random_preview):
                PersonalConnectionPolicy.validLabel(value)?"https://"+value+".api-api-api.com/":
                getContext().getString(R.string.personal_connection_label_invalid));
    }

    void showError(int message) {
        input.setError(getContext().getString(message));
        input.requestFocus();
    }
}
