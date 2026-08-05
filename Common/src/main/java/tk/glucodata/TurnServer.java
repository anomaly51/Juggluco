/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*      Fri Nov 28 17:57:06 CET 2025                                                 */

package tk.glucodata;

import static android.view.View.INVISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Backup.getnumedit;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;



public class TurnServer {
static final private String LOG_ID="TurnServer";

/*
public static EditText getEditText(Context context, String key) {
    var editkey= new EditText(context);
    editkey.setImeOptions(editoptions);
    editkey.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
    editkey.setTransformationMethod(new PasswordTransformationMethod());
    editkey.setMinEms(12);
    editkey.setText(key);
    return editkey;
    } */

static public  EditText getedit(Context act, String text) {
      EditText label=new EditText(act);
       label.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
       label.setImeOptions(editoptions);
       label.setMinEms(12);
      label.setText(text);
      return label;
      }

static boolean validTurnPort(int port) {
    return port>0&&port<=65535;
    }

private static void clinicalShow(MainActivity context,View parent) {
    EnableControls(parent,false);
    boolean absent=Natives.TurnServerNR()==0;
    EditText host=getedit(context,absent?"":Natives.getTurnHost(0));
    EditText port=getnumedit(context,absent?"":String.valueOf(Natives.getTurnPort(0)));
    EditText username=getedit(context,absent?"":Natives.getTurnUser(0));
    EditText password=getedit(context,absent?"":Natives.getTurnPassword(0));
    ConnectionUi.styleInput(host);
    ConnectionUi.styleInput(port);
    ConnectionUi.styleInput(username);
    ConnectionUi.styleInput(password);
    password.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setTransformationMethod(new PasswordTransformationMethod());

    CheckDirectionBox showPassword=new CheckDirectionBox(context);
    showPassword.setText(R.string.connection_show_password);
    showPassword.setOnCheckedChangeListener((button,checked)-> {
        int selection=password.getSelectionStart();
        password.setInputType(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(checked?null:new PasswordTransformationMethod());
        password.setSelection(Math.max(0,Math.min(selection,password.length())));
        });
    Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
    Button save=ClinicalUi.button(context,context.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    Button delete=ClinicalUi.button(context,context.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    delete.setVisibility(absent?View.GONE:View.VISIBLE);
    LinearLayout turnHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),context.getString(R.string.connection_turn_help_hint));
    TextView error=ConnectionUi.status(context,"",true);

    LinearLayout content=ConnectionUi.content(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.connection_turn_title),cancel));
    content.addView(ConnectionUi.intro(context,R.string.connection_turn_intro));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_server_details_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,context.getString(R.string.hostname),host),
            ClinicalUi.fieldRow(context,context.getString(R.string.port),port)));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_credentials_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,context.getString(R.string.username),username),
            ClinicalUi.fieldRow(context,context.getString(R.string.password),password),
            ConnectionUi.directToggle(context,showPassword)));
    error.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,12),
            ClinicalUi.dp(context,16),ClinicalUi.dp(context,12));
    content.addView(error);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_support_section)));
    content.addView(ClinicalUi.card(context,turnHelp));
    LinearLayout actions=new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setPadding(0,ClinicalUi.dp(context,20),0,0);
    if(!absent) {
        actions.addView(delete,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
        View gap=new View(context);
        actions.addView(gap,new LinearLayout.LayoutParams(ClinicalUi.dp(context,12),1));
        }
    actions.addView(save,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    content.addView(actions);
    ScrollView screen=ConnectionUi.screen(context,content);
    ConnectionUi.fullScreen(context,screen);
    Runnable close=()-> {
        EnableControls(parent,true);
        removeContentView(screen);
        hidekeyboard(context);
        };
    context.setonback(close);
    cancel.setOnClickListener(view->MainActivity.doonback());
    turnHelp.setOnClickListener(view->help.help(R.string.turnservers,context));
    delete.setOnClickListener(view->{
        Natives.deleteTurnServer(0);
        MainActivity.doonback();
        });
    save.setOnClickListener(view->{
        String hostValue=host.getText().toString().trim();
        int portValue;
        try {
            portValue=Integer.parseInt(port.getText().toString().trim());
            }
        catch(Throwable parseError) {
            error.setText(R.string.connection_invalid_port);
            error.setVisibility(View.VISIBLE);
            return;
            }
        if(hostValue.isEmpty()) {
            error.setText(R.string.connection_host_required);
            error.setVisibility(View.VISIBLE);
            return;
            }
        if(!validTurnPort(portValue)) {
            error.setText(R.string.portrange);
            error.setVisibility(View.VISIBLE);
            return;
            }
        Natives.setTurnPort(0,portValue);
        Natives.setTurnHost(0,hostValue);
        Natives.setTurnUser(0,username.getText().toString());
        Natives.setTurnPassword(0,password.getText().toString());
        MainActivity.doonback();
        });
    }

public static void show(MainActivity context,View parent) {
   if(!Applic.isWearable) {
      clinicalShow(context,parent);
      return;
      }
   EnableControls(parent,false);
   var delete=getbutton(context,context.getString(R.string.delete));
   var save=getbutton(context,R.string.save);
   var cancel=getbutton(context,R.string.cancel);
   var password = getlabel(context,R.string.password);
   var laypad=(int)(GlucoseCurve.getDensity()*4.0f);
   var absent=Natives.TurnServerNR()==0;
   password.setPaddingRelative(laypad*2,0,laypad,0);
   var username = getlabel(context,R.string.username);
   var hostname = getlabel(context,R.string.hostname);
   var portname = getlabel(context,R.string.port);
   var passedit=getedit(context,absent?"":Natives.getTurnPassword(0));
   var useredit=getedit(context,absent?"":Natives.getTurnUser(0));
   var hostedit=getedit(context,absent?"":Natives.getTurnHost(0));
  var portedit=getnumedit(context,absent?"":(""+Natives.getTurnPort(0)));

    delete.setOnClickListener(v->  {
        Natives.deleteTurnServer( 0);
        MainActivity.doonback();
        }
        );
   if(absent) {
        delete.setVisibility(INVISIBLE);
        }
    save.setOnClickListener(
            v -> {
             var portstr=portedit.getText().toString();
             int portnum=0;
             try {
                    portnum=Integer.parseInt(portstr);
                    }
            catch(Throwable e) {
                    Log.stack(LOG_ID,"parseInt", e);
                    Applic.argToaster(context,portstr+context.getString(R.string.invalidport), Toast.LENGTH_LONG);
                    return;
                    };
            if(portnum> 65535) {
                    Applic.argToaster(context,R.string.portrange,Toast.LENGTH_LONG);
                    return;
                    }
            Natives.setTurnPort(0,portnum);
            Natives.setTurnHost(0,hostedit.getText().toString());
            Natives.setTurnUser(0,useredit.getText().toString());
            Natives.setTurnPassword(0,passedit.getText().toString());
            MainActivity.doonback();
            }
            );
    var Help=getbutton(context,R.string.helpname);
    Help.setOnClickListener( v-> { 
            help.help(R.string.turnservers,context);
    });

    var layout=new Layout(context,(l,w,h)-> {
        return new int[] {w,h};
        },new View[]{hostname,hostedit,portname,portedit},new View[]{username,useredit,password,passedit} ,new View[]{cancel,Help,delete,save} );

    var params =new ViewGroup.MarginLayoutParams(MATCH_PARENT, WRAP_CONTENT);
//    var laypad=(int)(GlucoseCurve.metrics.density*4.0);
    params.leftMargin= MainActivity.systembarLeft;
    params.topMargin= MainActivity.systembarTop*3/4;
    params.rightMargin=MainActivity.systembarRight;
    params.bottomMargin=MainActivity.systembarBottom;

   layout.setPadding(laypad*2,0,laypad*2,0);
    layout.setBackgroundResource(R.drawable.dialogbackground);
    context.addMyContentView(layout, params);
     MainActivity.setonback( () -> {
        EnableControls(parent,true);
        removeContentView(layout); 
        hidekeyboard(context);
        });
    cancel.setOnClickListener( v -> { MainActivity.doonback(); });
    }
};
