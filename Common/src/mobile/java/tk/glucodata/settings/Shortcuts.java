/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:32:11 CET 2023                                                 */

package tk.glucodata.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.ClinicalUi;
import tk.glucodata.ConnectionUi;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

public class Shortcuts {
    private ViewGroup shortlistview;
    private ViewGroup shortedit;
    private LinearLayout shortlist;
    private TextView mainError;
    private ArrayList<ArrayList<Object>> shortcuts;
    private ArrayList<Object> current;
    private EditText labelEdit,valueEdit;

    public void hideSystemUI(Context context) { }

    public static boolean validShortcutDraft(String label,String value) {
        return label!=null&&!label.trim().isEmpty()
                &&value!=null&&!value.trim().isEmpty();
    }

    private void addRow(MainActivity context,int index) {
        ArrayList<Object> shortcut=shortcuts.get(index);
        String label=(String)shortcut.get(0);
        String value=(String)shortcut.get(1);
        LinearLayout row=ClinicalUi.actionRow(context,label,value);
        row.setOnClickListener(view-> {
            current=shortcut;
            showEditor(context,label,value);
        });
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        if(index>0)
            params.topMargin=ClinicalUi.dp(context,8);
        shortlist.addView(row,params);
    }

    private void rebuildList(MainActivity context) {
        shortlist.removeAllViews();
        for(int index=0;index<shortcuts.size();index++)
            addRow(context,index);
        if(shortcuts.isEmpty()) {
            TextView empty=ConnectionUi.status(context,
                    context.getString(R.string.clinical_shortcuts_empty),false);
            empty.setVisibility(VISIBLE);
            shortlist.addView(empty);
        }
    }

    private void saveAll(View source) {
        MainActivity context=(MainActivity)source.getContext();
        for(int index=0;index<shortcuts.size();index++) {
            ArrayList<Object> shortcut=shortcuts.get(index);
            int result=Natives.setShortcut(index,(String)shortcut.get(0),(String)shortcut.get(1));
            if(result!=-1) {
                String message=result==-5
                        ?context.getString(R.string.clinical_shortcuts_index_error,index)
                        :context.getString(R.string.clinical_shortcuts_value_error);
                mainError.setText(message);
                mainError.setVisibility(VISIBLE);
                return;
            }
        }
        Natives.setnrshortcuts(shortcuts.size());
        ((Applic)((Activity)context).getApplication()).numdata.sendshortcuts(shortcuts);
        Applic.wakemirrors();
        context.doonback();
    }

    public void mkshortlistview(MainActivity context) {
        shortcuts=Natives.getShortcuts();
        shortlist=new LinearLayout(context);
        shortlist.setOrientation(LinearLayout.VERTICAL);
        mainError=ConnectionUi.status(context,"",true);
        Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
        Button save=ClinicalUi.button(context,context.getString(R.string.save),
                ClinicalUi.ButtonRole.PRIMARY);
        LinearLayout add=ClinicalUi.actionRow(context,context.getString(R.string.newname),
                context.getString(R.string.clinical_shortcuts_add_hint));
        LinearLayout help=ClinicalUi.actionRow(context,context.getString(R.string.helpname),
                context.getString(R.string.clinical_shortcuts_help_hint));

        LinearLayout content=ConnectionUi.content(context);
        content.addView(ClinicalUi.header(context,
                context.getString(R.string.clinical_shortcuts_title),cancel));
        content.addView(ConnectionUi.intro(context,R.string.clinical_shortcuts_intro));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.clinical_shortcuts_entries_section)));
        content.addView(shortlist);
        LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin=ClinicalUi.dp(context,10);
        add.setLayoutParams(addParams);
        content.addView(add);
        content.addView(mainError);
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_support_section)));
        content.addView(ClinicalUi.card(context,help));
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin=ClinicalUi.dp(context,20);
        save.setLayoutParams(saveParams);
        content.addView(save);
        ScrollView screen=ConnectionUi.screen(context,content);
        shortlistview=screen;
        rebuildList(context);
        ConnectionUi.fullScreen(context,screen);

        add.setOnClickListener(view-> {
            current=null;
            showEditor(context,"","");
        });
        help.setOnClickListener(view->tk.glucodata.help.help(R.string.shortcuthelp,context));
        save.setOnClickListener(this::saveAll);
        cancel.setOnClickListener(view->context.doonback());
        context.setonback(()-> {
            removeContentView(screen);
            shortlistview=null;
            context.lightBars(!Natives.getInvertColors());
        });
    }

    private void showEditor(MainActivity context,String label,String value) {
        EnableControls(shortlistview,false);
        labelEdit=new EditText(context);
        labelEdit.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        labelEdit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                |EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_DONE);
        labelEdit.setText(label);
        ConnectionUi.styleInput(labelEdit);
        valueEdit=new EditText(context);
        valueEdit.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        valueEdit.setImeOptions(editoptions);
        valueEdit.setText(value);
        ConnectionUi.styleInput(valueEdit);

        Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
        Button save=ClinicalUi.button(context,context.getString(R.string.save),
                ClinicalUi.ButtonRole.PRIMARY);
        Button delete=ClinicalUi.button(context,context.getString(R.string.delete),
                ClinicalUi.ButtonRole.DANGER);
        delete.setVisibility(current==null?GONE:VISIBLE);
        TextView error=ConnectionUi.status(context,"",true);
        LinearLayout content=ConnectionUi.content(context);
        content.addView(ClinicalUi.header(context,
                context.getString(current==null?R.string.clinical_shortcut_new_title:
                        R.string.clinical_shortcut_edit_title),cancel));
        content.addView(ConnectionUi.intro(context,R.string.clinical_shortcut_editor_intro));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.clinical_shortcut_details_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.fieldRow(context,context.getString(R.string.shortcut),labelEdit),
                ClinicalUi.fieldRow(context,context.getString(R.string.value),valueEdit)));
        content.addView(error);
        if(current!=null) {
            content.addView(ClinicalUi.sectionLabel(context,
                    context.getString(R.string.connection_maintenance_section)));
            content.addView(delete,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin=ClinicalUi.dp(context,20);
        save.setLayoutParams(saveParams);
        content.addView(save);
        ScrollView screen=ConnectionUi.screen(context,content);
        shortedit=screen;
        ConnectionUi.fullScreen(context,screen);

        Runnable close=()-> {
            removeContentView(screen);
            shortedit=null;
            EnableControls(shortlistview,true);
            hidekeyboard(context);
        };
        context.setonback(close);
        cancel.setOnClickListener(view->context.doonback());
        save.setOnClickListener(view-> {
            String newLabel=labelEdit.getText().toString();
            String newValue=valueEdit.getText().toString();
            if(!validShortcutDraft(newLabel,newValue)) {
                error.setText(R.string.clinical_shortcut_required_error);
                error.setVisibility(VISIBLE);
                return;
            }
            if(current==null) {
                current=new ArrayList<>();
                current.add(newLabel);
                current.add(newValue);
                shortcuts.add(current);
            }
            else {
                current.set(0,newLabel);
                current.set(1,newValue);
            }
            rebuildList(context);
            context.doonback();
        });
        delete.setOnClickListener(view->ConnectionUi.confirmSheet(context,screen,
                context.getString(R.string.clinical_shortcut_delete_title),
                context.getString(R.string.clinical_shortcut_delete_message),
                context.getString(R.string.delete),ClinicalUi.ButtonRole.DANGER,()-> {
                    shortcuts.remove(current);
                    rebuildList(context);
                    context.doonback();
                }));
    }
}
