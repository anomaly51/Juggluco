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


package tk.glucodata;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;

import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.garmin.android.connectiq.IQDevice;

import java.text.DateFormat;
import java.util.Date;

import tk.glucodata.nums.AllData;
import tk.glucodata.nums.numio;
import tk.glucodata.settings.Shortcuts;

import static android.text.InputType.TYPE_NULL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getkerfstokblack;
import static tk.glucodata.Natives.setkerfstokblack;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.help;
import static tk.glucodata.help.helplight;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.timestring;

//import static tk.glucodata.GlucoseCurve.width;
//import static tk.glucodata.GlucoseCurve.height;

class GarminStatus {
	Spinner spinner;
	//s/^[ 	]*\([^=]*\)=new.*/TextView \1;/g
	TextView sdkreadyview;
	TextView registeredview;
	TextView restview;
	//	TextView GarminStatusstr;
	CheckDirectionBox glucose;
	AllData alldata;
	Button next;
	Button sync;
	View layout;
	private static final String LOG_ID = "GarminStatus";

	static String displaystr(IQDevice device) {
		IQDevice.IQDeviceStatus stat = device.getStatus();
		String friendly = device.getFriendlyName();
		return ((friendly == null) ? device.getDeviceIdentifier() : friendly) + " - " + stat.name();
	}

	static boolean validGarminAppId(String value) {
		return value!=null&&value.matches("[0-9A-Fa-f]{32}");
	}

	static private void setidview(MainActivity context,AllData alldata,View parent,
			View parentlayout) {
		EnableControls(parent,false);
		CheckDirectionBox defaultApp=getcheckbox(context,R.string.defaultname,false);
		EditText appId=new EditText(context);
		appId.setImeOptions(tk.glucodata.settings.Settings.editoptions);
		String defaultId=Natives.getdefaultid();
		String savedId=Natives.getgarminid();
		appId.setText(savedId);
		appId.setFilters(new InputFilter[]{(source,start,end,dest,dstart,dend)-> {
			StringBuilder result=new StringBuilder();
			for(int index=start;index<end;index++) {
				if(Character.digit(source.charAt(index),16)==-1)
					return "";
				result.append(Character.toUpperCase(source.charAt(index)));
				}
			return result.toString();
			}});
		ConnectionUi.styleInput(appId);
		defaultApp.setChecked(defaultId.equals(savedId));
		if(defaultApp.isChecked())
			appId.setInputType(TYPE_NULL);
		else
			appId.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		defaultApp.setOnCheckedChangeListener((button,checked)-> {
			if(checked) {
				appId.setText(defaultId);
				appId.setInputType(TYPE_NULL);
				hidekeyboard(context);
				}
			else {
				appId.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
				appId.requestFocus();
				tk.glucodata.help.showkeyboard(context,appId);
				}
			});

		Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
		Button save=ClinicalUi.button(context,context.getString(R.string.save),
				ClinicalUi.ButtonRole.PRIMARY);
		TextView error=ConnectionUi.status(context,"",true);
		LinearLayout content=ConnectionUi.content(context);
		content.addView(ClinicalUi.header(context,
				context.getString(R.string.clinical_garmin_id_title),cancel));
		content.addView(ConnectionUi.intro(context,R.string.clinical_garmin_id_intro));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_app_section)));
		content.addView(ClinicalUi.card(context,
				ConnectionUi.directToggle(context,defaultApp),
				ClinicalUi.fieldRow(context,
						context.getString(R.string.clinical_garmin_app_id),appId)));
		LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
		errorParams.topMargin=ClinicalUi.dp(context,12);
		error.setLayoutParams(errorParams);
		content.addView(error);
		LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
		saveParams.topMargin=ClinicalUi.dp(context,20);
		save.setLayoutParams(saveParams);
		content.addView(save);
		ScrollView screen=ConnectionUi.screen(context,content);
		ConnectionUi.fullScreen(context,screen);
		context.setonback(()-> {
			EnableControls(parent,true);
			removeContentView(screen);
			context.hideSystemUI();
			hidekeyboard(context);
			});
		cancel.setOnClickListener(view->context.doonback());
		save.setOnClickListener(view-> {
			boolean changed=false;
			if(defaultApp.isChecked()) {
				if(!defaultId.equals(savedId))
					changed=Natives.setgarminid(null);
				}
			else {
				String value=appId.getText().toString();
				if(!validGarminAppId(value)) {
					error.setText(R.string.clinical_garmin_id_error);
					error.setVisibility(VISIBLE);
					return;
					}
				if(!savedId.equals(value)) {
					changed=Natives.setgarminid(value);
					if(!changed) {
						error.setText(R.string.clinical_garmin_id_save_error);
						error.setVisibility(VISIBLE);
						return;
						}
					}
				}
			if(changed)
				alldata.reinit(context);
			context.doonback();
			context.doonback();
			context.doonback();
			new GarminStatus(context,alldata,parentlayout);
			});
	}

	public GarminStatus(MainActivity context, AllData alldata,View parentlayout) {
   		EnableControls(parentlayout,false);
		this.alldata = alldata;
		spinner = new Spinner(context);
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				{if(doLog) {Log.i(LOG_ID, "onItemSelected");};};
				if (alldata.devices != null) {
					alldata.devused = position;
					long oldident = numio.getident();
					long ident = alldata.devices.get(position).getDeviceIdentifier();
					if (oldident != ident) {
						numio.setident(ident);
						alldata.reinit(context);
					}
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				Applic.argToaster(parent.getContext(),R.string.clinical_garmin_nothing_selected,
						Toast.LENGTH_SHORT);
			}
		});
		RangeAdapter<IQDevice> adap = new RangeAdapter<>(alldata.devices, context, GarminStatus::displaystr);
		spinner.setAdapter(adap);
		avoidSpinnerDropdownFocus(spinner);
		spinner.setMinimumHeight(ClinicalUi.dp(context,54));
		spinner.setPaddingRelative(ClinicalUi.dp(context,12),0,
				ClinicalUi.dp(context,12),0);
		spinner.setBackground(ClinicalUi.surface(context,false,true));

		sdkreadyview = new TextView(context);
		registeredview = new TextView(context);
		restview = new TextView(context);
		for(TextView status:new TextView[]{sdkreadyview,registeredview,restview}) {
			status.setTextColor(ClinicalUi.primaryText(context));
			status.setTextSize(15);
			status.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,12),
					ClinicalUi.dp(context,16),ClinicalUi.dp(context,12));
			}
		sync=ClinicalUi.button(context,context.getString(R.string.sync),
				ClinicalUi.ButtonRole.PRIMARY);
		sync.setOnClickListener(view->alldata.sync());
		Button close=ConnectionUi.headerButton(context,R.string.closename);
		LinearLayout refresh=ClinicalUi.actionRow(context,context.getString(R.string.refresh),
				context.getString(R.string.clinical_garmin_refresh_hint));
		refresh.setOnClickListener(view->show());
		LinearLayout help=ClinicalUi.actionRow(context,context.getString(R.string.helpname),
				context.getString(R.string.clinical_garmin_help_hint));
		help.setOnClickListener(view->helplight(R.string.kerfstok,context));
		glucose = new CheckDirectionBox(context);
		glucose.setText(R.string.glucose);
		glucose.setChecked(alldata.sendtowatch);
		glucose.setOnClickListener(
				v -> {
					if (glucose.isChecked())
						alldata.startglucose();

					else
						alldata.stopglucose();
				});
		next=ClinicalUi.button(context,context.getString(R.string.sendqueue),
				ClinicalUi.ButtonRole.SECONDARY);
		next.setOnClickListener(v -> alldata.nextmessage());
		LinearLayout reinit=ClinicalUi.actionRow(context,context.getString(R.string.reinit),
				context.getString(R.string.clinical_garmin_reinit_hint));
		reinit.setOnClickListener(view->alldata.reinit(context));
		LinearLayout config=ClinicalUi.actionRow(context,context.getString(R.string.config),
				context.getString(R.string.clinical_garmin_config_hint));
		config.setOnClickListener(view->kerfstokconfig(context,alldata,layout,parentlayout));

		LinearLayout content=ConnectionUi.content(context);
		content.addView(ClinicalUi.header(context,
				context.getString(R.string.clinical_garmin_status_title),close));
		content.addView(ConnectionUi.intro(context,R.string.clinical_garmin_status_intro));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_device_section)));
		content.addView(ClinicalUi.card(context,
				ClinicalUi.fieldRow(context,context.getString(R.string.clinical_garmin_device),spinner),
				refresh));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_connection_section)));
		content.addView(ClinicalUi.card(context,sdkreadyview,registeredview,restview));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_transfer_section)));
		content.addView(ClinicalUi.card(context,ConnectionUi.directToggle(context,glucose)));
		content.addView(sync,new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
		nextParams.topMargin=ClinicalUi.dp(context,10);
		next.setLayoutParams(nextParams);
		content.addView(next);
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_tools_section)));
		content.addView(ClinicalUi.card(context,reinit,config,help));
		ScrollView screen=ConnectionUi.screen(context,content);
		layout=screen;
		ConnectionUi.fullScreen(context,screen);
		close.setOnClickListener(view->context.doonback());
		context.setonback(() ->  {
                        EnableControls(parentlayout,true);
                        removeContentView(screen);
                        });
		show();
	}

	public void show() {
		try {
			if (alldata.devices != null) {
				RangeAdapter<IQDevice> adap = new RangeAdapter<IQDevice>(alldata.devices, spinner.getContext(), GarminStatus::displaystr);
				spinner.setAdapter(adap);
				spinner.setSelection(alldata.devused);
				//		spinner.getAdapter().notifyDataSetChanged();
			}
			Context resources=spinner.getContext();
			String yes=resources.getString(R.string.clinical_state_yes);
			String no=resources.getString(R.string.clinical_state_no);
			sdkreadyview.setText(resources.getString(R.string.clinical_garmin_sdk_ready,
					alldata.sdkready()?yes:no));
			registeredview.setText(resources.getString(R.string.clinical_garmin_registered,
					alldata.usewatch?yes:no));
	/*	if(alldata.appmissing==0)
			apppresent.setVisibility(INVISIBLE);
		else*/
			StringBuilder builder = new StringBuilder();
			if (alldata.sendtime != 0L) {
				builder.append(resources.getString(R.string.clinical_garmin_sent_at,
						timestring(alldata.sendtime))).append('\n');
				if (alldata.sendtime > alldata.statustime) {
					builder.append(resources.getString(R.string.clinical_garmin_no_status))
							.append('\n');
				} else {
					builder.append(resources.getString(R.string.clinical_garmin_status_value,
							alldata.sendstatus.name())).append('\n');
				}
			} else {
				builder.append(resources.getString(R.string.clinical_garmin_not_sent))
						.append('\n');
			}
			if (alldata.receivedmessage == 0) {
				builder.append(resources.getString(R.string.clinical_garmin_not_received));
			} else {
				builder.append(resources.getString(R.string.clinical_garmin_received_at,
						timestring(alldata.receivedmessage)));
			}
			String alltext=builder.toString();
			{if(doLog) {Log.i(LOG_ID,"setText "+alltext);};};
			restview.setText(alltext);
			int vis = alldata.usewatch ? VISIBLE : GONE;
			glucose.setChecked(alldata.sendtowatch);
			glucose.setVisibility(vis);
			next.setVisibility(alldata.usewatch && alldata.waiting() ? VISIBLE : GONE);
			sync.setVisibility(vis);
//		resend.setVisibility(vis);
			layout.setVisibility(VISIBLE);
//			layout.invalidate();
		} catch (Throwable e) {
			String mess = e.getMessage();
			if (mess == null)
				mess = "GarminStatus error";
			Log.e(LOG_ID, "Exception: " + mess);
		}

	}

	static private void kerfstokconfig(MainActivity context,AllData alldata,View parent,View parentlayout) {
		EnableControls(parent,false);
		Button close=ConnectionUi.headerButton(context,R.string.closename);
		String appTitle=(AllData.appmissing<0)?context.getString(R.string.watchappinstalled):
				context.getString(R.string.getkerfstok);
		LinearLayout app=ClinicalUi.actionRow(context,appTitle,
				context.getString(R.string.clinical_garmin_store_hint));
		LinearLayout appId=ClinicalUi.actionRow(context,
				context.getString(R.string.clinical_garmin_app_id),
				context.getString(R.string.clinical_garmin_id_hint));
		LinearLayout shortcuts=ClinicalUi.actionRow(context,
				context.getString(R.string.shutcuts),
				context.getString(R.string.clinical_garmin_shortcuts_hint));
		CheckDirectionBox darkMode=getcheckbox(context,R.string.darkmode,getkerfstokblack());
		LinearLayout help=ClinicalUi.actionRow(context,context.getString(R.string.helpname),
				context.getString(R.string.clinical_garmin_config_help_hint));

		LinearLayout content=ConnectionUi.content(context);
		content.addView(ClinicalUi.header(context,
				context.getString(R.string.clinical_garmin_config_title),close));
		content.addView(ConnectionUi.intro(context,R.string.clinical_garmin_config_intro));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_app_section)));
		content.addView(ClinicalUi.card(context,app,appId,shortcuts));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.clinical_garmin_appearance_section)));
		content.addView(ClinicalUi.card(context,ConnectionUi.directToggle(context,darkMode)));
		content.addView(ClinicalUi.sectionLabel(context,
				context.getString(R.string.connection_support_section)));
		content.addView(ClinicalUi.card(context,help));
		ScrollView screen=ConnectionUi.screen(context,content);
		ConnectionUi.fullScreen(context,screen);

		app.setOnClickListener(v -> {
			final String url = "https://apps.garmin.com/en-US/apps/b6348ccc-86d8-4780-8013-d9e19fed5260";
			Uri uri = Uri.parse(url);
			Intent intent = new Intent(Intent.ACTION_VIEW, uri);
			try { 
				if (intent.resolveActivity(context.getPackageManager()) != null) {
					context.startActivity(intent);
					}
				}
			catch(Throwable th)  {
				Log.stack(LOG_ID,"garmin",th);
				}

		});
		shortcuts.setOnClickListener(v -> {
            context.themeLightBars();
			hidekeyboard(context);
			new Shortcuts().mkshortlistview(context);
		});
		darkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
			Applic.app.numdata.setcolor(isChecked);
			setkerfstokblack(isChecked);
		});
		appId.setOnClickListener(v->setidview(context,alldata,screen,parentlayout));
		help.setOnClickListener(v->helplight(R.string.garminconfig,context));
		context.setonback(() -> {
			EnableControls(parent,true);
			removeContentView(screen);
			context.hideSystemUI();
			hidekeyboard(context);
		});
		close.setOnClickListener(view->context.doonback());
	}
}
