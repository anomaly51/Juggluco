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

import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import tk.glucodata.Applic;
import tk.glucodata.CheckDirectionBox;
import tk.glucodata.ClinicalUi;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.MainActivity.poponback;
import static tk.glucodata.MainActivity.setonback;
import static tk.glucodata.Natives.canSendNumbers;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.hideSystemUI;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getcheckbox;

public class LibreNumbers  {
private final static String LOG_ID="LibreNumbers";


static public class LibreNumberAdapter extends RecyclerView.Adapter<LibreNumberHolder> {
	ArrayList<String > 	labels=Natives.getLabels();

	ViewGroup layout;
	View sendnumbers;
	int night;

    LibreNumberAdapter(ViewGroup layout,View sendnumbers,int night) {
    	this.layout=layout;
    	this.sendnumbers=sendnumbers;
    	this.night=night;

    	}

    @NonNull
	@Override
    public LibreNumberHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Button view = ClinicalUi.button(parent.getContext(), "",
                ClinicalUi.ButtonRole.SECONDARY);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        view.setSingleLine(false);
        view.setMinHeight(ClinicalUi.dp(parent.getContext(), 58));
        view.setBackground(ClinicalUi.surface(parent.getContext(), false, true));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ClinicalUi.dp(parent.getContext(), 8);
        view.setLayoutParams(params);

        return new LibreNumberHolder(view,layout,sendnumbers,night);

    }

	@Override
	public void onBindViewHolder(final LibreNumberHolder holder, int pos) {
		TextView text=(TextView)holder.itemView;
		text.setText(labels.get(pos));
	    }
        @Override
        public int getItemCount() {
                return labels.size()-1;

        }

}





public static void    mklayout(MainActivity context, int night, CheckDirectionBox donum, int[] donothing, View parent) {
	parent.setVisibility(INVISIBLE);
	Button close = ClinicalUi.button(context, context.getString(R.string.closename),
            ClinicalUi.ButtonRole.SECONDARY);
	RecyclerView recycle = new RecyclerView(context);
	recycle.setHasFixedSize(false);
	LinearLayoutManager lin=new LinearLayoutManager(context);
	recycle.setLayoutManager(lin);
	Button help=ClinicalUi.button(context, context.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);

	CheckDirectionBox sendnumbers=getcheckbox(context,donum.getText().toString(),donum.isChecked());
	TextView remark=ClinicalUi.body(context, "");
	if(!canSendNumbers(night)) {
		sendnumbers.setEnabled(false);
		remark.setText(R.string.libresetalllabels);
		}

    LinearLayout librenumlayout=ClinicalUi.verticalContent(context);
    librenumlayout.setPaddingRelative(
            MainActivity.systembarLeft + ClinicalUi.dp(context,20),
            MainActivity.systembarTop + ClinicalUi.dp(context,8),
            MainActivity.systembarRight + ClinicalUi.dp(context,20),
            MainActivity.systembarBottom + ClinicalUi.dp(context,18));
    librenumlayout.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    librenumlayout.addView(ClinicalUi.header(context,
            context.getString(R.string.clinical_amount_mapping_title),close));
    TextView intro=ClinicalUi.body(context,
            context.getString(R.string.clinical_amount_mapping_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    librenumlayout.addView(intro);
    librenumlayout.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_sending_section)));
    librenumlayout.addView(ClinicalUi.card(context,
            ClinicalUi.toggleRow(context,sendnumbers,
                    context.getString(R.string.clinical_send_amounts_hint))));
    if(remark.getText().length()>0) {
        remark.setPaddingRelative(ClinicalUi.dp(context,4),ClinicalUi.dp(context,10),
                ClinicalUi.dp(context,4),0);
        remark.setTextColor(ClinicalUi.danger(context));
        librenumlayout.addView(remark);
        }
    librenumlayout.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_label_mapping_section)));
    recycle.setClipToPadding(false);
    recycle.setPadding(0,0,0,ClinicalUi.dp(context,8));
    librenumlayout.addView(recycle,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));
    librenumlayout.addView(help,new LinearLayout.LayoutParams(MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
    var adapt = new LibreNumberAdapter(librenumlayout,sendnumbers,night);
	recycle.setAdapter(adapt);

	Runnable closerun=()-> {
		hidekeyboard(context);
		removeContentView(librenumlayout) ;
		final var checked= sendnumbers.isChecked();
		parent.setVisibility(VISIBLE);
		donothing[0]=2;
		donum.setChecked(checked);
		donothing[0]=0;
		};
    setonback(closerun);
    close.setOnClickListener(v->{
	    poponback();
    	   closerun.run();
    });
        context.addMyContentView(librenumlayout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
	help.setOnClickListener(v->{
		EnableControls(librenumlayout,false);
		tk.glucodata.help.help(context.getString(night==1?R.string.nightnumhelp:R.string.librenumhelp),context,l-> {
			Log.i(LOG_ID,"librenumhelp callback");	
			EnableControls(librenumlayout,true); 
			if(!canSendNumbers(night)) { 
				sendnumbers.setEnabled(false);
				}
			} );

		});

	}

}
