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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import tk.glucodata.settings.Settings;

import static android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS;
import static android.view.View.GONE;
import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO;
import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.talkbackrunning;
import static tk.glucodata.Natives.getalarmdisturb;
import static tk.glucodata.help.help;
import static tk.glucodata.help.helplight;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class RingTones {
 int kind;
 String uri=null;
 int duration,susp;
// Ringtone ringtone;
 TextView name;
CheckDirectionBox flashview;
//Button permission;
private static final String LOG_ID="RingTones";

static public RingTones one=null;
public RingTones(int ki)  {
		one=this;
		kind=ki;
		uri= Natives.readring(kind);
		duration=Natives.readalarmduration(kind);
		susp=Natives.readalarmsuspension(kind);
		 //ringtone = Notify.getring(kind);
		 }
static 	public void setring(int ki,String uristr) {
	if(one!=null)  {
		one.setringer(ki,uristr);
		}

}
private static String gettitle(Context context,String uri,int kind) {
	try {
		Ringtone ringtone = Notify.mkrings(uri, kind);
		return ringtone.getTitle(context);
	} catch (Throwable th) {
		Log.stack(LOG_ID, "ringtone title", th);
		return "Unknown";
	}
}
void setringer(int ki,String uristr) {
	if(ki==kind) {
		uri=uristr;
		if(name!=null) {
			name.setText(gettitle(name.getContext(),uri,kind));
			}
		}
	}
static public void EnableControls(View view,boolean enable){
	if(talkbackrunning)
		view.setVisibility(enable?VISIBLE:GONE);
	else
		subEnableControls(view,enable);
	}

static private void subEnableControls(View view,boolean enable){
	view.setEnabled(enable);
	//view.setAccessibilityDelegate(enable?null:accessDeli);
	if (view instanceof ViewGroup) {
		ViewGroup vg = (ViewGroup) view;
		for (int i = 0; i < vg.getChildCount(); i++) {
			subEnableControls(vg.getChildAt(i), enable);
		}
	}
	}

static final int SOUND_INPUT_VALID=0;
static final int SOUND_INPUT_EMPTY=1;
static final int SOUND_INPUT_NOT_NUMBER=2;
static final int SOUND_INPUT_NEGATIVE=3;
static final int SOUND_INPUT_TOO_LARGE=4;

static int validateDurationInput(String raw) {
	return validateWholeNumber(raw,65535);
	}

static int validateSuspensionInput(String raw) {
	return validateWholeNumber(raw,Short.MAX_VALUE);
	}

private static int validateWholeNumber(String raw,int maximum) {
	if(raw==null||raw.trim().isEmpty())
		return SOUND_INPUT_EMPTY;
	final long parsed;
	try {
		parsed=Long.parseLong(raw.trim());
		}
	catch(NumberFormatException ex) {
		return SOUND_INPUT_NOT_NUMBER;
		}
	if(parsed<0)
		return SOUND_INPUT_NEGATIVE;
	if(parsed>maximum)
		return SOUND_INPUT_TOO_LARGE;
	return SOUND_INPUT_VALID;
	}

static boolean usesSuspension(int kind) {
	return kind<2||kind>4;
	}

static boolean isDefaultTone(String uri) {
	return uri==null||uri.length()==0;
	}

private void mkPhoneViews(MainActivity context,String label,View parview) {
	if(parview!=null)
		EnableControls(parview,false);

	LinearLayout content=ClinicalUi.verticalContent(context);
	content.setPadding(ClinicalUi.dp(context,20),
			MainActivity.systembarTop+ClinicalUi.dp(context,8),
			ClinicalUi.dp(context,20),ClinicalUi.dp(context,30));
	Button headerClose=ClinicalUi.button(context,context.getString(R.string.cancel),
			ClinicalUi.ButtonRole.SECONDARY);
	content.addView(ClinicalUi.header(context,
			context.getString(R.string.sound_modern_title),headerClose));
	TextView subtitle=ClinicalUi.body(context,label==null||label.trim().isEmpty()
			?context.getString(R.string.sound_modern_reminder_context)
			:context.getString(R.string.sound_modern_for_context,label));
	subtitle.setPadding(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
			ClinicalUi.dp(context,4));
	content.addView(subtitle);

	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.sound_modern_tone_section)));
	Button select=ClinicalUi.button(context,
			context.getString(R.string.sound_modern_choose),ClinicalUi.ButtonRole.SECONDARY);
	LinearLayout currentTone=new LinearLayout(context);
	currentTone.setOrientation(LinearLayout.HORIZONTAL);
	currentTone.setGravity(Gravity.CENTER_VERTICAL);
	currentTone.setMinimumHeight(ClinicalUi.dp(context,78));
	currentTone.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,10),
			ClinicalUi.dp(context,10),ClinicalUi.dp(context,10));
	LinearLayout toneCopy=new LinearLayout(context);
	toneCopy.setOrientation(LinearLayout.VERTICAL);
	toneCopy.setGravity(Gravity.CENTER_VERTICAL);
	TextView toneCaption=ClinicalUi.body(context,
			context.getString(R.string.sound_modern_current));
	toneCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
	toneCopy.addView(toneCaption);
	name=new TextView(context);
	name.setText(gettitle(context,uri,kind));
	name.setTextColor(ClinicalUi.primaryText(context));
	name.setTextSize(TypedValue.COMPLEX_UNIT_SP,18);
	name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
	name.setPadding(0,ClinicalUi.dp(context,3),ClinicalUi.dp(context,10),0);
	toneCopy.addView(name);
	currentTone.addView(toneCopy,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
	currentTone.addView(select,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));

	CheckDirectionBox defaultTone=new CheckDirectionBox(context);
	defaultTone.setText(R.string.defaultname);
	defaultTone.setChecked(isDefaultTone(uri));
	LinearLayout defaultRow=ClinicalUi.toggleRow(context,defaultTone,
			context.getString(R.string.sound_modern_default_subtitle));
	LinearLayout toneCard=ClinicalUi.card(context,currentTone,defaultRow);
	content.addView(toneCard);

	defaultTone.setOnCheckedChangeListener((button,checked)->{
		if(checked) {
			uri=null;
			name.setText(gettitle(context,uri,kind));
			select.setVisibility(GONE);
			}
		else
			select.setVisibility(VISIBLE);
		});
	select.setVisibility(defaultTone.isChecked()?GONE:VISIBLE);
	select.setOnClickListener(v->{
		hidekeyboard(context);
		try {
			Intent intent=new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
			intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALL);
			intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);
			intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,false);
			int request=MainActivity.REQUEST_RINGTONE|kind;
			context.startActivityForResult(Intent.createChooser(intent,null),request);
			}
		catch(Throwable th) {
			Applic.argToaster(context,R.string.no_ringtone_picker_found,Toast.LENGTH_LONG);
			}
		});

	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.sound_modern_timing_section)));
	EditText durationInput=makePhoneWholeNumberInput(context,duration);
	LinearLayout timingCard;
	final boolean glucoseAlarm=usesSuspension(kind);
	EditText suspensionInput=makePhoneWholeNumberInput(context,susp);
	View durationRow=ClinicalUi.fieldRow(context,
			context.getString(R.string.sound_modern_duration),durationInput);
	if(glucoseAlarm) {
		View suspensionRow=ClinicalUi.fieldRow(context,
				context.getString(R.string.sound_modern_suspension),suspensionInput);
		timingCard=ClinicalUi.card(context,durationRow,suspensionRow);
		}
	else
		timingCard=ClinicalUi.card(context,durationRow);
	content.addView(timingCard);

	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.sound_modern_delivery_section)));
	CheckDirectionBox sound=new CheckDirectionBox(context);
	sound.setText(R.string.soundname);
	sound.setChecked(Natives.alarmhassound(kind));
	CheckDirectionBox vibration=new CheckDirectionBox(context);
	vibration.setText(R.string.vibrationname);
	vibration.setChecked(Natives.alarmhasvibration(kind));
	flashview=new CheckDirectionBox(context);
	flashview.setText(R.string.flash);
	final boolean hasFlash=Flash.hasFlash(context);
	if(hasFlash)
		flashview.setChecked(Natives.alarmhasflash(kind));
	CheckDirectionBox disturb=new CheckDirectionBox(context);
	if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
		disturb.setText(R.string.disturb);
		disturb.setChecked(getalarmdisturb(kind));
		disturb.setOnCheckedChangeListener((button,checked)->{
			if(checked)
				context.asknotificationAccess();
			});
		}
	LinearLayout soundRow=ClinicalUi.toggleRow(context,sound,
			context.getString(R.string.sound_modern_sound_subtitle));
	LinearLayout vibrationRow=ClinicalUi.toggleRow(context,vibration,
			context.getString(R.string.sound_modern_vibration_subtitle));
	LinearLayout flashRow=hasFlash?ClinicalUi.toggleRow(context,flashview,
			context.getString(R.string.sound_modern_flash_subtitle)):null;
	LinearLayout disturbRow=Build.VERSION.SDK_INT>=Build.VERSION_CODES.M
			?ClinicalUi.toggleRow(context,disturb,
			context.getString(R.string.sound_modern_disturb_subtitle)):null;
	LinearLayout deliveryCard=ClinicalUi.card(context,soundRow,vibrationRow,flashRow,disturbRow);
	content.addView(deliveryCard);
	sound.setOnCheckedChangeListener((button,checked)->{
		subEnableControls(toneCard,checked);
		toneCard.setAlpha(checked?1f:.55f);
		});
	subEnableControls(toneCard,sound.isChecked());
	toneCard.setAlpha(sound.isChecked()?1f:.55f);

	TextView error=ClinicalUi.body(context,"");
	error.setTextColor(ClinicalUi.danger(context));
	error.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
	error.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
	error.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,13),
			ClinicalUi.dp(context,16),ClinicalUi.dp(context,13));
	error.setBackground(ClinicalUi.surface(context,false,false));
	error.setVisibility(GONE);
	LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
	errorParams.topMargin=ClinicalUi.dp(context,12);
	content.addView(error,errorParams);

	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.sound_modern_preview_section)));
	Button preview=ClinicalUi.button(context,context.getString(R.string.sound_modern_preview),
			ClinicalUi.ButtonRole.PRIMARY);
	Button stop=ClinicalUi.button(context,context.getString(R.string.sound_modern_stop),
			ClinicalUi.ButtonRole.SECONDARY);
	LinearLayout previewRow=new LinearLayout(context);
	previewRow.setOrientation(LinearLayout.HORIZONTAL);
	previewRow.addView(preview,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
	LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f);
	stopParams.setMarginStart(ClinicalUi.dp(context,10));
	previewRow.addView(stop,stopParams);
	content.addView(previewRow);

	Button save=ClinicalUi.button(context,context.getString(R.string.sound_modern_save),
			ClinicalUi.ButtonRole.PRIMARY);
	Button cancel=ClinicalUi.button(context,context.getString(R.string.cancel),
			ClinicalUi.ButtonRole.SECONDARY);
	LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
	saveParams.topMargin=ClinicalUi.dp(context,18);
	content.addView(save,saveParams);
	LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
	cancelParams.topMargin=ClinicalUi.dp(context,10);
	content.addView(cancel,cancelParams);
	Button helpButton=ClinicalUi.button(context,context.getString(R.string.helpname),
			ClinicalUi.ButtonRole.SECONDARY);
	LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
	helpParams.topMargin=ClinicalUi.dp(context,10);
	content.addView(helpButton,helpParams);

	ScrollView screen=ClinicalUi.scrollScreen(context,content);
	context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
	Runnable closeScreen=()->{
		Notify.stopalarm();
		if(parview!=null)
			EnableControls(parview,true);
		one=null;
		hidekeyboard(context);
		Settings.removeContentView(screen);
		};
	headerClose.setOnClickListener(v->context.doonback());
	cancel.setOnClickListener(v->context.doonback());
	stop.setOnClickListener(v->Notify.stopalarm());
	helpButton.setOnClickListener(v->{
		if(parview!=null&&parview.getX()>0)
			helplight(R.string.ringtone,context);
		else
			help(R.string.ringtone,context);
		});
	preview.setOnClickListener(v->{
		int validation=validateDurationInput(durationInput.getText().toString());
		if(validation!=SOUND_INPUT_VALID) {
			showPhoneSoundError(context,error,validation,false);
			return;
			}
		showPhoneSoundError(context,error,SOUND_INPUT_VALID,false);
		try {
			hidekeyboard(context);
			int previewDuration=Integer.parseInt(durationInput.getText().toString().trim());
			Ringtone ringtone=Notify.mkrings(uri,kind);
			Notify.playring(ringtone,previewDuration,sound.isChecked(),
				hasFlash&&flashview.isChecked(),vibration.isChecked(),
				Build.VERSION.SDK_INT<Build.VERSION_CODES.M||disturb.isChecked(),kind);
			}
		catch(Throwable ex) {
			Log.stack(LOG_ID,"preview",ex);
			error.setText(R.string.sound_modern_error_preview);
			error.setVisibility(VISIBLE);
			}
		});
	save.setOnClickListener(v->{
		int durationValidation=validateDurationInput(durationInput.getText().toString());
		if(durationValidation!=SOUND_INPUT_VALID) {
			showPhoneSoundError(context,error,durationValidation,false);
			return;
			}
		if(glucoseAlarm) {
			int suspensionValidation=validateSuspensionInput(suspensionInput.getText().toString());
			if(suspensionValidation!=SOUND_INPUT_VALID) {
				showPhoneSoundError(context,error,suspensionValidation,true);
				return;
				}
			}
		Notify.stopalarm();
		try {
			int savedDuration=Integer.parseInt(durationInput.getText().toString().trim());
			Natives.writealarmduration(kind,savedDuration);
			if(glucoseAlarm) {
				short savedSuspension=Short.parseShort(suspensionInput.getText().toString().trim());
				SuperGattCallback.writealarmsuspension(kind,savedSuspension);
				}
			if(defaultTone.isChecked())
				uri="";
			if(!Natives.writering(kind,uri,sound.isChecked(),
					hasFlash&&flashview.isChecked(),vibration.isChecked())) {
				error.setText(R.string.sound_modern_error_save);
				error.setVisibility(VISIBLE);
				return;
				}
			if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M)
				Natives.setalarmdisturb(kind,disturb.isChecked());
			}
		catch(Throwable ex) {
			Log.stack(LOG_ID,"save",ex);
			error.setText(R.string.sound_modern_error_save);
			error.setVisibility(VISIBLE);
			return;
			}
		context.poponback();
		closeScreen.run();
		});
	context.setonback(closeScreen);
	}

private static EditText makePhoneWholeNumberInput(MainActivity context,int value) {
	EditText input=new EditText(context);
	input.setSingleLine(true);
	input.setImeOptions(editoptions);
	input.setInputType(InputType.TYPE_CLASS_NUMBER);
	input.setText(String.valueOf(value));
	input.setTextColor(ClinicalUi.primaryText(context));
	input.setHintTextColor(ClinicalUi.secondaryText(context));
	input.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
	input.setGravity(Gravity.CENTER);
	input.setMinWidth(ClinicalUi.dp(context,112));
	input.setMinimumHeight(ClinicalUi.dp(context,50));
	input.setPadding(ClinicalUi.dp(context,12),0,ClinicalUi.dp(context,12),0);
	input.setBackground(ClinicalUi.surface(context,false,true));
	return input;
	}

private static void showPhoneSoundError(
		MainActivity context,TextView error,int validation,boolean suspension) {
	if(validation==SOUND_INPUT_VALID) {
		error.setText("");
		error.setVisibility(GONE);
		return;
		}
	int message;
	switch(validation) {
		case SOUND_INPUT_EMPTY:
			message=suspension?R.string.sound_modern_error_suspension_empty:
					R.string.sound_modern_error_duration_empty;
			break;
		case SOUND_INPUT_NEGATIVE:
			message=R.string.sound_modern_error_negative;
			break;
		case SOUND_INPUT_TOO_LARGE:
			message=suspension?R.string.sound_modern_error_suspension_large:
					R.string.sound_modern_error_duration_large;
			break;
		default:
			message=R.string.sound_modern_error_number;
			break;
		}
	error.setText(message);
	error.setVisibility(VISIBLE);
	error.announceForAccessibility(error.getText());
	}

 public void mkviews(MainActivity context,String label,View parview) {
		if(!isWearable) {
			mkPhoneViews(context,label,parview);
			return;
			}
 		if(parview!=null) {
//			parview.setVisibility(GONE);
			EnableControls(parview,false);
//				parview.setEnabled(false);
			}
		Button Select=getbutton(context,R.string.select);
		 name=getlabel(context,gettitle(context,uri,kind));
		final int rand=Math.round(5*GlucoseCurve.metrics.density);
		name.setPadding(rand,0,rand,0);
		CheckDirectionBox def=new CheckDirectionBox(context);
		def.setText(R.string.defaultname);
		final int minheight=GlucoseCurve.dpToPx(48);
		def.setMinimumHeight(minheight);
		def.setOnCheckedChangeListener(
			 (buttonView,  isChecked) -> {
				 if (isChecked) {
				 	uri=null;
					 name.setText(gettitle(context,uri,kind));
					 Select.setVisibility(GONE);
				 } else {
					 Select.setVisibility(VISIBLE);
				 }
			 }
			 );
			 	

		if(uri==null||uri.length()==0)
			def.setChecked(true);

		Select.setOnClickListener(v-> {
		       hidekeyboard(context);
		      try {
			    Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
			   // intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, kind==2?RingtoneManager.TYPE_NOTIFICATION:RingtoneManager.TYPE_ALARM);
			    intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL);
			    intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
			    intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
			    final int request= MainActivity.REQUEST_RINGTONE|kind;
			   Intent openInChooser = Intent.createChooser(intent, null);
			    context.startActivityForResult(openInChooser, request);
			    }
		    catch(Throwable th) {
			Applic.argToaster(context, R.string.no_ringtone_picker_found, Toast.LENGTH_LONG);
		    	}
		});
		Button help=getbutton(context,R.string.helpname);
		help.setOnClickListener(v-> {
            if(parview.getX()>0)
                helplight(R.string.ringtone,context);
            else
                help(R.string.ringtone,context);
			});
		EditText duredit=new EditText(context);
		duredit.setImeOptions(editoptions);
		duredit.setMinEms(3);
      duredit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		duredit.setText(duration+"");
		duredit.setMinimumHeight(minheight);
	duredit.setPadding(duredit.getPaddingLeft(),duredit.getPaddingTop(),duredit.getPaddingRight()+(int)(GlucoseCurve.metrics.density*20),duredit.getPaddingBottom());
	 TextView waitlabel=getlabel(context,R.string.minuteddeactivated);
		waitlabel.setPadding(rand*2,0,0,0);
	 flashview=new CheckDirectionBox(context);
	 //permission=new Button(context);

	final boolean hasflash= !isWearable && Flash.hasFlash(context);
	CheckDirectionBox sound=new CheckDirectionBox(context);
	sound.setText(R.string.soundname);
	final boolean hassound= Natives.alarmhassound(kind);
	sound.setChecked(hassound);

	CheckDirectionBox vibration=new CheckDirectionBox(context);
	vibration.setPadding(0,0,rand*2,0);

	vibration.setText(R.string.vibrationname);
	final boolean hasvibration= Natives.alarmhasvibration(kind);
	vibration.setChecked(hasvibration);

	CheckDirectionBox disturb=new CheckDirectionBox(context);
	if(!isWearable) {

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			disturb.setText(R.string.disturb);
			final boolean dodisturb=getalarmdisturb(kind);
			disturb.setChecked(dodisturb);
			disturb.setOnCheckedChangeListener((buttonView,  isChecked) -> {
				 if (isChecked) {
					context.asknotificationAccess(); }
					}
					);
			}
		}
	 EditText waitedit=new EditText(context);
		waitedit.setMinimumHeight(minheight);
		Button play=getbutton(context,R.string.play);
		play.setOnClickListener(v-> {
			String str=duredit.getText().toString();
			try {
				hidekeyboard(context);
				int dur=(str!=null)?Integer.parseInt(str):duration;
		 		Ringtone ringtone = Notify.mkrings(uri, kind);
				Notify.playring(ringtone,dur,sound.isChecked(),hasflash&&flashview.isChecked(),vibration.isChecked(),isWearable||Build.VERSION.SDK_INT < Build.VERSION_CODES.M||disturb.isChecked(),kind);
				} catch(Throwable e) {
				Log.stack(LOG_ID,"play",e);
					Applic.argToaster(context, context.getString(R.string.can_t_play)+str+ context.getString(R.string.seconds), Toast.LENGTH_SHORT);
				
				}
			});

		Button Cancel=getbutton(context,R.string.cancel);
		Button Save=getbutton(context,R.string.save);


	var soundselect=new View[] {def,Select};
	if(!hassound) {
		for(var el:soundselect)
			el.setEnabled(false);
		}
	sound.setOnCheckedChangeListener(
		 (buttonView,  isChecked) -> {
			 for (var el : soundselect)
				 el.setEnabled(isChecked);
		 }
		 );
	final boolean glucosealarm=(kind<2||kind>4);
//	int hasname=(label==null&&kind>1)?0:1;
	int hasname=(label==null&&!glucosealarm)?0:1;
	int start=0;
	 View [][] views=new View[(glucosealarm?5:((label==null)?3:4))+3][];
View[] durviews;
	if(isWearable) {
		TextView durlabel=getlabel(context,R.string.duractionsec);
		durlabel.setPadding( (int)(13.0*GlucoseCurve.metrics.density),0,0,0);
		durviews=new View[]{durlabel,duredit};
		}
	else {
		TextView durlabel=getlabel(context,R.string.duraction);
		durlabel.setPadding(rand,0,0,0);
		TextView durseconds=getlabel(context,R.string.sec);
		 durviews=new View[]{help,durlabel,duredit,durseconds};
		}
//	View[] durviews=new View[]{help,durlabel,duredit,durseconds};
	
	views[1]=durviews;

	 if(glucosealarm) {
	 	start=2;
		 waitedit.setImeOptions(editoptions);
		 waitedit.setMinEms(2);
		 waitedit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		 waitedit.setText(susp+"");
		views[2]=new View[]{waitlabel,waitedit};
//		  views= new View[][]{new View[]{descript},new View[] {def,name,Select},durviews,new View[]{waitlabel,waitedit},new View[]{play,Cancel,Save}};
		
	 }
	 else start=1;

	views[0]=new View[]{name};
	views[start+1]=soundselect;
   if(isWearable) {
      if(hasname==1) {
            View labv=getlabel(context,label);
            views[views.length-2]= new View[]{labv};
            }
      views[views.length-1]= new View[]{Save};
      }
   else {
      if(hasname==1) {
         View labv=getlabel(context,label);
         views[views.length-1]=new View[]{labv};
        // labv.setPadding(0,0,0,0);
         }
      views[views.length-1-hasname]= isWearable?new View[]{Save}:new View[]{play,Cancel,Save};
      }

	if(hasflash) {
		boolean flashalarm= Natives.alarmhasflash(kind);
		flashview.setChecked(flashalarm);
		flashview.setText(R.string.flash);
	}
	else {
		flashview.setVisibility(INVISIBLE);
		//permission.setVisibility(INVISIBLE);
		}

   if(isWearable) {
      var space1=new Space(context);
      var space2=new Space(context);
      views[start+2]=new View[]{sound,vibration};
      views[start+3]=new View[]{space1,play,Cancel,space2};
      }
   else  {
      views[start+2]=(Build.VERSION.SDK_INT <Build.VERSION_CODES.M? new View[]{sound}:new View[]{sound,disturb});
      views[start+3]=new View[]{flashview,vibration};
   }
	
	View lay;
	ScrollView scroll=new ScrollView(context);
	lay=scroll;
		Layout layout = new Layout(context, (l, w, h) -> {
        /*
		if(!isWearable) {
			final var width=GlucoseCurve.getwidth();
			if(width>w) {
				lay.setX((width-w)/2);
				}
			}
            */
			return new int[]{w,h};}, views);
		scroll.addView(layout);
    ViewGroup.LayoutParams params;
	if(isWearable)  {
		lay.setBackgroundColor(tk.glucodata.Applic.backgroundcolor);
//		 int laypad=(int)(GlucoseCurve.metrics.density*(hasname==1?20.0f:35.0f));
		 int laypad=(int)(GlucoseCurve.metrics.density*14);
		final int sidepad=(int)(GlucoseCurve.metrics.density*10.0f);
		 layout.setPadding((int)(GlucoseCurve.metrics.density*13.0f),(int)(GlucoseCurve.metrics.density*7.0f),(int)(GlucoseCurve.metrics.density*13.0f),laypad);
        params=new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT);
		 }
	else {
		lay.setBackgroundResource(R.drawable.dialogbackground);
		 int laypad=(int)(GlucoseCurve.metrics.density*4.0f);
		 lay.setPadding(laypad,0,laypad,laypad);
          params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
		 }
	 context.addMyContentView(lay, params);
		Save.setOnClickListener(v->{
			Notify.stopalarm();
			try {
			String str=duredit.getText().toString();
			if(str!=null) {
				int durs=Integer.parseInt(str);
				if(durs<0) {
					Applic.argToaster(context, context.getString(R.string.duration_can_t_be_negative)+durs, Toast.LENGTH_SHORT);
					return;
					}
				if(durs>65535) {
					Applic.argToaster(context, durs+context.getString(R.string.too_large_maximum_65535), Toast.LENGTH_SHORT);
					return;
					}
				Natives.writealarmduration(kind,durs);
				}
			if(glucosealarm) {
				str = waitedit.getText().toString();
				if (str != null) {
					short wa = Short.parseShort(str);
					tk.glucodata.SuperGattCallback.writealarmsuspension(kind, wa);
				    }
			   }

			if(def.isChecked())
				uri="";
			  if(!Natives.writering(kind,uri,sound.isChecked(),hasflash&&flashview.isChecked(),vibration.isChecked())) {
				Applic.argToaster(context, uri+context.getString(R.string.too_large), Toast.LENGTH_SHORT);
				return;
			  	}
			//   Notify.setring(kind);
			} catch(Throwable e) {
				Log.stack(LOG_ID,"save",e);
				Applic.argToaster(context, context.getString(R.string.can_t_use_specification), Toast.LENGTH_SHORT);
				return;
				};

			if(!isWearable)  {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					Natives.setalarmdisturb(kind,disturb.isChecked());
					}
				}

			if(parview!=null)
				EnableControls(parview,true);
			//	parview.setEnabled(true);

			//	parview.setVisibility(VISIBLE);

		        one=null;
		        hidekeyboard(context);
			lay.setVisibility(GONE);
			Settings.removeContentView(lay) ;
			context.poponback();
			});
		Cancel.setOnClickListener(v->{
			context.doonback();
			});
		context.setonback(() -> {
			Notify.stopalarm();
			if(parview!=null)
				EnableControls(parview,true);
				//parview.setVisibility(VISIBLE);
			one=null;
		        hidekeyboard(context);
			lay.setVisibility(GONE);
			Settings.removeContentView(lay);
			Notify.stopalarm();
			});
		}
}
