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
/*      Fri Jan 27 15:31:32 CET 2023                                                 */


package tk.glucodata.settings;

import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;

import tk.glucodata.Applic;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.Log;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.ClinicalUi;
import yuku.ambilwarna.AmbilWarnaDialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.settings.Settings.removeContentView;

public class SetColors {
private static final String LOG_ID="SetColors";
static void show(MainActivity act) {

   act.lightBars(!Natives.getInvertColors( ));
    int initialColor=Natives.getlastcolor();
    if(initialColor==0) initialColor=0xfff7f022;
    final View[] preview={null};
    AmbilWarnaDialog dialog = new AmbilWarnaDialog(act, initialColor,c-> {
        {if(doLog) {Log.i(LOG_ID,String.format(usedlocale,"col=%x",c));};};
            Natives.setlastcolor(c);
            tk.glucodata.Applic.app.redraw();
            if(preview[0]!=null) preview[0].setBackgroundColor(c);
        }, v-> { });
    View picker=dialog.getview();
    picker.setBackground(ClinicalUi.surface(act,true,false));
    picker.setPadding(ClinicalUi.dp(act,18),ClinicalUi.dp(act,18),
            ClinicalUi.dp(act,18),ClinicalUi.dp(act,18));
    Button internalClose=picker.findViewById(R.id.closeambi);
    Button internalHelp=picker.findViewById(R.id.helpambi);
    if(internalClose!=null) internalClose.setVisibility(GONE);
    if(internalHelp!=null) internalHelp.setVisibility(GONE);

    Button close=ClinicalUi.button(act,act.getString(R.string.closename),
            ClinicalUi.ButtonRole.SECONDARY);
    Button help=ClinicalUi.button(act,act.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    Button done=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setGravity(Gravity.CENTER_HORIZONTAL);
    content.setPaddingRelative(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,24));
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.clinical_graph_color_title),close));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.clinical_graph_color_intro));
    intro.setPaddingRelative(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
            ClinicalUi.dp(act,16));
    content.addView(intro,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    View previewBar=new View(act);
    previewBar.setBackgroundColor(initialColor);
    previewBar.setContentDescription(act.getString(R.string.clinical_color_preview));
    preview[0]=previewBar;
    LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(MATCH_PARENT,
            ClinicalUi.dp(act,18));
    previewParams.bottomMargin=ClinicalUi.dp(act,18);
    content.addView(previewBar,previewParams);
    content.addView(picker,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    LinearLayout actions=new LinearLayout(act);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setPadding(0,ClinicalUi.dp(act,20),0,0);
    actions.addView(help,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    actions.addView(new Space(act),new LinearLayout.LayoutParams(ClinicalUi.dp(act,12),1));
    actions.addView(done,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    content.addView(actions,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    close.setOnClickListener(view->MainActivity.doonback());
    done.setOnClickListener(view->MainActivity.doonback());
    help.setOnClickListener(view->tk.glucodata.help.helplight(R.string.colorhelp,act));
    act.setonback(()-> {
        removeContentView(screen);
        if(tk.glucodata.Menus.on)
            tk.glucodata.Menus.show(act);
            
    });
}

}
