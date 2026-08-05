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


package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Floating.rewritefloating;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.MainActivity.screenheight;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import yuku.ambilwarna.AmbilWarnaDialog;

public class FloatingConfig {
private static final String LOG_ID="FloatingConfig";
//   AmbilWarnaDialog(Context context, int color, boolean supportsAlpha, OnAmbilWarnaListener listener)
static private boolean background=true;
static public void    setcolor(int c) {
        if(doLog) {Log.i(LOG_ID,"setcolor("+(c&0xFFFFFFFF)+")");};
        if(background) { 
                Floating.setbackgroundcolor(c);
                }
        else  {
              Floating.setforegroundcolor(c);
              }
        }
static public int    getcolor() {
        return background?Natives.getfloatingbackground( ):Natives.getfloatingforeground( );
        }



private static GradientDrawable swatch(MainActivity context,int color) {
    GradientDrawable shape=new GradientDrawable();
    shape.setShape(GradientDrawable.RECTANGLE);
    shape.setColor(color);
    shape.setStroke(ClinicalUi.dp(context,1),
            ClinicalUi.blend(ClinicalUi.primaryText(context),ClinicalUi.window(context),.24f));
    shape.setCornerRadius(ClinicalUi.dp(context,12));
    return shape;
    }

private static LinearLayout colorRow(MainActivity context,CharSequence title,int color) {
    LinearLayout row=ClinicalUi.actionRow(context,title,
            String.format(usedlocale,"#%08X",color));
    View sample=new View(context);
    sample.setBackground(swatch(context,color));
    sample.setContentDescription(title);
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(
            ClinicalUi.dp(context,40),ClinicalUi.dp(context,40));
    params.setMarginStart(ClinicalUi.dp(context,8));
    params.setMarginEnd(ClinicalUi.dp(context,6));
    row.addView(sample,1,params);
    return row;
    }

private static void openColorPicker(MainActivity act,View parent,int initialColor,
        AmbilWarnaDialog.IntConsumer consumer) {
    parent.setVisibility(INVISIBLE);
    FrameLayout screen=new FrameLayout(act);
    screen.setBackgroundColor(ClinicalUi.window(act));
    screen.setPadding(MainActivity.systembarLeft+ClinicalUi.dp(act,18),
            MainActivity.systembarTop+ClinicalUi.dp(act,18),
            MainActivity.systembarRight+ClinicalUi.dp(act,18),
            MainActivity.systembarBottom+ClinicalUi.dp(act,18));
    AmbilWarnaDialog picker=new AmbilWarnaDialog(act,initialColor,color->{
        consumer.accept(color);
        Floating.invalidatefloat();
        },view->{});
    View pickerView=picker.getview();
    pickerView.setBackground(ClinicalUi.surface(act,true,false));
    pickerView.setPadding(ClinicalUi.dp(act,18),ClinicalUi.dp(act,18),
            ClinicalUi.dp(act,18),ClinicalUi.dp(act,18));
    FrameLayout.LayoutParams pickerParams=new FrameLayout.LayoutParams(WRAP_CONTENT,
            WRAP_CONTENT,android.view.Gravity.CENTER);
    screen.addView(pickerView,pickerParams);
    act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    Button done=pickerView.findViewById(R.id.closeambi);
    Button pickerHelp=pickerView.findViewById(R.id.helpambi);
    if(done!=null) {
        done.setText(R.string.save);
        done.setOnClickListener(view->MainActivity.doonback());
        }
    if(pickerHelp!=null)
        pickerHelp.setOnClickListener(view->help.helplight(R.string.colorhelp,act));
    act.setonback(()->{
        removeContentView(screen);
        parent.setVisibility(VISIBLE);
        });
    }

static public void show(MainActivity act,View parent) {
    parent.setVisibility(INVISIBLE);
    int height=GlucoseCurve.getheight();
    int width=GlucoseCurve.getwidth();
    int maxfont=Math.max(32,height*7/10);
    int currentfont=Natives.getfloatingFontsize();
    if(currentfont<5||currentfont>(int)(screenheight*.8))
        currentfont=(int)Notify.glucosesize;

    Button close=ClinicalUi.button(act,act.getString(R.string.closename),
            ClinicalUi.ButtonRole.SECONDARY);
    Button helpButton=ClinicalUi.button(act,act.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    CheckDirectionBox floatglucose=getcheckbox(act,R.string.active,Natives.getfloatglucose());
    CheckDirectionBox timeshow=getcheckbox(act,R.string.time,Floating.showtime);
    CheckDirectionBox touchable=getcheckbox(act,R.string.touchable,Natives.getfloatingTouchable());
    CheckDirectionBox transparent=getcheckbox(act,R.string.transparent,
            Color.alpha(Natives.getfloatingbackground())!=0xFF);
    boolean[] hidden={Natives.gethidefloatinJuggluco()};
    CheckDirectionBox showInside=getcheckbox(act,R.string.floatjuggluco,!hidden[0]);

    floatglucose.setOnCheckedChangeListener((button,checked)->
            Floating.setfloatglucose(act,checked));
    timeshow.setOnCheckedChangeListener((button,checked)->{
        Floating.showtime=checked;
        Natives.setfloattime(checked);
        rewritefloating(act);
        });
    touchable.setOnCheckedChangeListener((button,checked)->Floating.setTouchable(checked));
    transparent.setOnCheckedChangeListener((button,checked)->{
        Floating.setbackgroundalpha(checked?0:0xff);
        Floating.invalidatefloat();
        });
    showInside.setOnCheckedChangeListener((button,checked)->{
        hidden[0]=!checked;
        Natives.sethidefloatinJuggluco(!checked);
        });

    TextView fontValue=ClinicalUi.body(act,
            act.getString(R.string.clinical_floating_font_value,currentfont));
    fontValue.setTextColor(ClinicalUi.primaryText(act));
    SeekBar fontSize=new SeekBar(act);
    Applic.ifRTLseekbar(fontSize);
    fontSize.setMax((maxfont-5)*100);
    fontSize.setProgress((currentfont-5)*100);
    fontSize.setMinimumWidth(Math.max(ClinicalUi.dp(act,220),(int)(width*.55f)));
    fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser) {
            int size=Math.round(progress/100f)+5;
            fontValue.setText(act.getString(R.string.clinical_floating_font_value,size));
            Natives.setfloatingFontsize(size);
            rewritefloating(act);
            }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    LinearLayout fontCard=new LinearLayout(act);
    fontCard.setOrientation(LinearLayout.VERTICAL);
    fontCard.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,14),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,12));
    fontCard.addView(fontValue);
    fontCard.addView(fontSize,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    fontCard.setBackground(ClinicalUi.surface(act,false,false));

    LinearLayout foreground=colorRow(act,act.getString(R.string.foreground),
            Natives.getfloatingforeground());
    LinearLayout backgroundColor=colorRow(act,act.getString(R.string.background),
            Natives.getfloatingbackground());
    foreground.setOnClickListener(view->openColorPicker(act,view,
            Natives.getfloatingforeground(),color->{
                Floating.setforegroundcolor(color);
                rewritefloating(act);
                }));
    backgroundColor.setOnClickListener(view->openColorPicker(act,view,
            Natives.getfloatingbackground(),color->{
                Floating.setbackgroundcolor(color);
                rewritefloating(act);
                }));
    helpButton.setOnClickListener(view->help.help(R.string.floatingconfig,act));

    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setPaddingRelative(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,24));
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.clinical_floating_title),close));
    TextView intro=ClinicalUi.body(act,act.getString(R.string.clinical_floating_intro));
    intro.setPaddingRelative(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
            ClinicalUi.dp(act,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.clinical_floating_status_section)));
    content.addView(ClinicalUi.card(act,
            ClinicalUi.toggleRow(act,floatglucose,
                    act.getString(R.string.clinical_floating_active_hint)),
            ClinicalUi.toggleRow(act,showInside,
                    act.getString(R.string.clinical_floating_inside_hint))));
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.clinical_floating_appearance_section)));
    content.addView(fontCard);
    LinearLayout.LayoutParams colorGap=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    colorGap.topMargin=ClinicalUi.dp(act,10);
    foreground.setLayoutParams(colorGap);
    content.addView(foreground);
    LinearLayout.LayoutParams colorGap2=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    colorGap2.topMargin=ClinicalUi.dp(act,10);
    backgroundColor.setLayoutParams(colorGap2);
    content.addView(backgroundColor);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.clinical_floating_behavior_section)));
    content.addView(ClinicalUi.card(act,
            ClinicalUi.toggleRow(act,timeshow,
                    act.getString(R.string.clinical_floating_time_hint)),
            ClinicalUi.toggleRow(act,touchable,
                    act.getString(R.string.clinical_floating_touch_hint)),
            ClinicalUi.toggleRow(act,transparent,
                    act.getString(R.string.clinical_floating_transparency_hint))));
    LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    helpParams.topMargin=ClinicalUi.dp(act,22);
    content.addView(helpButton,helpParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    act.setonback(()->{
        parent.setVisibility(VISIBLE);
        removeContentView(screen);
        if(hidden[0]) Floating.removeFloating();
        });
    close.setOnClickListener(view->act.doonback());
}

}
