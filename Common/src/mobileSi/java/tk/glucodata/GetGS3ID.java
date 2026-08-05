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
/*      Wed May 06 21:13:17 CEST 2026                                                */
package tk.glucodata;


import static tk.glucodata.Applic.Toaster;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.scheduler;
import static tk.glucodata.Applic.useZXing;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.GS3ID.GS3IDstatus;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.MainActivity.REQUEST_BARCODE_SIB2;
import static tk.glucodata.PhotoScan.deviceAdded;
import static tk.glucodata.ZXing.scanZXingAlg;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getradiobuttonId;
import static tk.glucodata.util.sethtml;


import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


class GetGS3ID {
private static final String LOG_ID="GetGS3ID";

private static EditText clinicalField(MainActivity context,int inputType) {
   EditText field=new EditText(context);
   field.setImeOptions(editoptions);
   field.setInputType(inputType);
   field.setSingleLine(true);
   field.setTextColor(ClinicalUi.primaryText(context));
   field.setHintTextColor(ClinicalUi.secondaryText(context));
   field.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
   field.setMinHeight(ClinicalUi.dp(context,52));
   field.setMinWidth(ClinicalUi.dp(context,160));
   field.setPadding(ClinicalUi.dp(context,14),0,
         ClinicalUi.dp(context,14),0);
   field.setBackground(ClinicalUi.surface(context,false,true));
   return field;
   }

private static LinearLayout screenContent(MainActivity context,String title,Button close) {
   LinearLayout content=ClinicalUi.verticalContent(context);
   content.setPadding(ClinicalUi.dp(context,20),
         MainActivity.systembarTop+ClinicalUi.dp(context,8),
         ClinicalUi.dp(context,20),ClinicalUi.dp(context,30));
   content.addView(ClinicalUi.header(context,title,close));
   return content;
   }

private static TextView inlineStatus(MainActivity context) {
   TextView status=ClinicalUi.body(context,"");
   status.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,12),
         ClinicalUi.dp(context,16),ClinicalUi.dp(context,12));
   status.setBackground(ClinicalUi.surface(context,false,false));
   status.setVisibility(View.GONE);
   return status;
   }

private static void showStatus(TextView status,CharSequence text,boolean error) {
   status.setText(text);
   status.setTextColor(error?ClinicalUi.danger(status.getContext()):
         ClinicalUi.secondaryText(status.getContext()));
   status.setVisibility(text==null||text.length()==0?View.GONE:View.VISIBLE);
   if(error)
      status.announceForAccessibility(text);
   }

private static void getUserid(String name,String oldid,MainActivity context) {
   context.lightBars(false);
   var editid=clinicalField(context,InputType.TYPE_CLASS_NUMBER);
    if(!"0".equals(oldid))
       editid.setText(oldid);
   editid.setContentDescription(context.getString(R.string.hardware_account_id));
   var save=ClinicalUi.button(context,context.getString(R.string.save),
         ClinicalUi.ButtonRole.PRIMARY);
   var close=ClinicalUi.button(context,context.getString(R.string.closename),
         ClinicalUi.ButtonRole.SECONDARY);
   LinearLayout content=screenContent(context,
         context.getString(R.string.hardware_manual_account_title),close);
   var intro=ClinicalUi.body(context,
         context.getString(R.string.hardware_manual_account_intro));
   intro.setPadding(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
         ClinicalUi.dp(context,8));
   content.addView(intro);
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.hardware_account_section)));
   content.addView(ClinicalUi.card(context,ClinicalUi.fieldRow(context,
         context.getString(R.string.hardware_account_id),editid)));
   TextView error=inlineStatus(context);
   LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
         MATCH_PARENT,WRAP_CONTENT);
   errorParams.topMargin=ClinicalUi.dp(context,12);
   content.addView(error,errorParams);
   LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
         MATCH_PARENT,WRAP_CONTENT);
   saveParams.topMargin=ClinicalUi.dp(context,20);
   content.addView(save,saveParams);
   ScrollView screen=ClinicalUi.scrollScreen(context,content);
   String[] prevID={editid.getText().toString()};
   context.addMyContentView(screen,new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));

    Runnable closer= ()->  
      {removeContentView(screen);
      gs3Number(name, context);
      };
   Runnable[] onback={null}; //Idioot, maar gaat niet anders
  onback[0]= ()-> {
             String idstr = editid.getText().toString();
             if(idstr.equals(prevID[0])) {
                  closer.run();
                  }
             else  {
             Confirm.ask2(context,"getUserID "+context.getString(R.string.withoutsaving),"",
                    closer ,
                    ()-> {
                        MainActivity.setonback( onback[0]);
                        });
                  }
          };


   MainActivity.setonback( onback[0]);

   save.setOnClickListener(v->  {
         String idstr = editid.getText().toString();
         try {
            if(!idstr.isEmpty()) {
               long id = Long.parseLong(idstr);
               if(id<=0L) {
                  showStatus(error,context.getString(R.string.hardware_account_error_positive),true);
                  return;
                  }
               Natives.saveGS3id(id);
               Applic.argToaster(context, context.getString(R.string.saved)+ " "+ id, Toast.LENGTH_SHORT);
               prevID[0]=idstr;
            } else {
               showStatus(error,context.getString(R.string.hardware_account_error_empty),true);
               Applic.argToaster(context, context.getString(R.string.noaccountidspecified), Toast.LENGTH_SHORT);
               return;
            }
         } catch (Throwable th) {
            showStatus(error,context.getString(R.string.hardware_account_error_format),true);
            Applic.argToaster(context, context.getString(R.string.wrongformat) + idstr, Toast.LENGTH_SHORT);
            Log.stack(LOG_ID, "parse account id", th);
            return;
         }
      MainActivity.poponback();
      closer.run();
      });
   close.setOnClickListener(v->  {
         MainActivity.doonback();
         });
   }

static private boolean validate(MainActivity act,String emailstr,String passstr) {
            if(emailstr.length()<3) {
               Applic.argToaster(act, act.getString( R.string.emailaddresstooshort)+emailstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(emailstr.length()>255) {
               Applic.argToaster(act,  act.getString( R.string.emailaddresstoolong)+emailstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(passstr.length()<3) {
               Applic.argToaster(act, act.getString(R.string.password8)+passstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(passstr.length()>36) {
               Applic.argToaster(act,  act.getString(R.string.password36)+passstr, Toast.LENGTH_SHORT);
               return false;
               }
        return true;
        }


public static void  gs3fromserver(String name,MainActivity act) {
   act.lightBars(false);
   var email=clinicalField(act,InputType.TYPE_CLASS_TEXT|
         InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
   email.setHint(R.string.email);
   var editpass=clinicalField(act,InputType.TYPE_CLASS_TEXT|
         InputType.TYPE_TEXT_VARIATION_PASSWORD);
   editpass.setHint(R.string.password);
   editpass.setTransformationMethod(new PasswordTransformationMethod());
        var visible = new CheckDirectionBox(act);
        visible.setText(R.string.hardware_show_password);
        visible.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
                        var sel=editpass.getSelectionStart();
                        editpass.setInputType(InputType.TYPE_CLASS_TEXT|
                              (isChecked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                                    InputType.TYPE_TEXT_VARIATION_PASSWORD));
                        if(isChecked)
                                        editpass.setTransformationMethod(null);
                        else
                                        editpass.setTransformationMethod(new PasswordTransformationMethod());
                        editpass.setSelection(sel);
                        });
   var accountid=ClinicalUi.body(act,act.getString(R.string.hardware_not_retrieved));
   accountid.setTextColor(ClinicalUi.primaryText(act));
   var getaccountid=ClinicalUi.button(act,act.getString(R.string.getaccountid),
         ClinicalUi.ButtonRole.PRIMARY);
   var close=ClinicalUi.button(act,act.getString(R.string.closename),
         ClinicalUi.ButtonRole.SECONDARY);
   final var helpbutton=ClinicalUi.button(act,act.getString(R.string.helpname),
         ClinicalUi.ButtonRole.SECONDARY);

   helpbutton.setOnClickListener(v-> help.help(R.string.SibionicsServer,act));
   var statusview=inlineStatus(act);
   showStatus(statusview,GS3IDstatus,false);
   LinearLayout content=screenContent(act,
         act.getString(R.string.hardware_server_account_title),close);
   var intro=ClinicalUi.body(act,
         act.getString(R.string.hardware_server_account_intro));
   intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
         ClinicalUi.dp(act,8));
   content.addView(intro);
   content.addView(ClinicalUi.sectionLabel(act,
         act.getString(R.string.hardware_credentials_section)));
   content.addView(ClinicalUi.card(act,
         ClinicalUi.fieldRow(act,act.getString(R.string.email),email),
         ClinicalUi.fieldRow(act,act.getString(R.string.password),editpass),
         ClinicalUi.toggleRow(act,visible,act.getString(R.string.hardware_password_helper))));
   content.addView(ClinicalUi.sectionLabel(act,
         act.getString(R.string.hardware_account_section)));
   content.addView(ClinicalUi.card(act,ClinicalUi.fieldRow(act,
         act.getString(R.string.hardware_account_id),accountid)));
   LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(
         MATCH_PARENT,WRAP_CONTENT);
   statusParams.topMargin=ClinicalUi.dp(act,12);
   content.addView(statusview,statusParams);
   LinearLayout.LayoutParams getParams=new LinearLayout.LayoutParams(
         MATCH_PARENT,WRAP_CONTENT);
   getParams.topMargin=ClinicalUi.dp(act,18);
   content.addView(getaccountid,getParams);
   LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(
         MATCH_PARENT,WRAP_CONTENT);
   helpParams.topMargin=ClinicalUi.dp(act,10);
   content.addView(helpbutton,helpParams);
   ScrollView screen=ClinicalUi.scrollScreen(act,content);
   String[] waspass={""},wasmail={""};
   Runnable closer=() -> {
            removeContentView(screen);
            gs3Number(name, act);
     };
   Runnable[] onback={null};
   onback[0]=
           ()->  {
       var  success=wasmail[0].equals(email.getText().toString())&&waspass[0].equals(editpass.getText().toString());
        if(!success) {
            Confirm.ask2(act,act.getString(R.string.leave),act.getString(R.string.emailnotused),
                closer ,
                ()-> {
                    MainActivity.setonback( onback[0]);
                    });
            }
        else {
            closer.run();
        }};
   MainActivity.setonback( onback[0]);

   close.setOnClickListener(v->  {
         MainActivity.doonback();
         });


    getaccountid.setOnClickListener(v -> {
       String emailstr = email.getText().toString();
       String passstr  = editpass.getText().toString();
       if (!validate(act, emailstr, passstr)) {
          showStatus(statusview,act.getString(R.string.hardware_credentials_error),true);
          return;
          }

       var md5pass = Natives.md5sum(passstr);
       showStatus(statusview,act.getString(R.string.connecttoserver),false);
       scheduler.execute(() -> {                                       // background thread
          boolean ok = GS3ID.postgetauth(emailstr, md5pass);
          long id    = Natives.getGS3id();
          String st  = GS3IDstatus;

          Applic.getHandler().post(() -> {                                       // back on the UI thread
             showStatus(statusview,st,!ok);
             if(ok) {
                accountid.setText(Long.toString(id));
                waspass[0]=passstr;
                wasmail[0]=emailstr;
                }
             else {
                 Toast.makeText(act,R.string.retrievefailed, Toast.LENGTH_SHORT).show();
                 }


          });
       });
    });

   act.addMyContentView(screen,new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
   }

static void gs3Number(String name, MainActivity act) {
    act.lightBars(false);
    long id=Natives.getGS3id();
    var help=new TextView(act);
    sethtml(help, R.string.gs3number);
    help.setTextColor(ClinicalUi.secondaryText(act));
    help.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
    help.setLineSpacing(0f,1.16f);

    String idstring=Long.toString(id);
    var idtext=ClinicalUi.body(act,idstring);
    idtext.setTextColor(id==0L?ClinicalUi.secondaryText(act):ClinicalUi.accent(act));
    idtext.setTextSize(TypedValue.COMPLEX_UNIT_SP,24);
    idtext.setGravity(Gravity.CENTER);
    idtext.setPadding(ClinicalUi.dp(act,12),ClinicalUi.dp(act,18),
          ClinicalUi.dp(act,12),ClinicalUi.dp(act,18));
    var manual=ClinicalUi.button(act,act.getString(R.string.manual),
          ClinicalUi.ButtonRole.SECONDARY);
    var retrieve=ClinicalUi.button(act,act.getString(R.string.retrieve),
          ClinicalUi.ButtonRole.SECONDARY);
    var ok=ClinicalUi.button(act,act.getString(R.string.hardware_continue_setup),
          ClinicalUi.ButtonRole.PRIMARY);
    LinearLayout content=screenContent(act,
          act.getString(R.string.hardware_account_setup_title),null);
    content.addView(help);
    content.addView(ClinicalUi.sectionLabel(act,
          act.getString(R.string.hardware_current_account_section)));
    content.addView(ClinicalUi.card(act,idtext));
    content.addView(ClinicalUi.sectionLabel(act,
          act.getString(R.string.hardware_account_method_section)));
    LinearLayout methods=new LinearLayout(act);
    methods.setOrientation(LinearLayout.VERTICAL);
    methods.addView(manual,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams retrieveParams=new LinearLayout.LayoutParams(
          MATCH_PARENT,WRAP_CONTENT);
    retrieveParams.topMargin=ClinicalUi.dp(act,10);
    methods.addView(retrieve,retrieveParams);
    content.addView(methods);
    LinearLayout.LayoutParams okParams=new LinearLayout.LayoutParams(
          MATCH_PARENT,WRAP_CONTENT);
    okParams.topMargin=ClinicalUi.dp(act,20);
    content.addView(ok,okParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    act.addMyContentView(screen,new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
   Runnable closer=() -> {
      removeContentView(screen);
      act.requestRender();
      };
  Runnable[] back={null};
  back[0]=
() -> {
       var idstr=idtext.getText().toString();
       if(idstr.isEmpty() || idstr.equals("0")) {
              Confirm.ask2(act,act.getString(R.string.leave),
                        act.getString(R.string.id0message),
                          ()-> {
                              deviceAdded(act);
                              closer.run();} ,
                        ()-> {
                            MainActivity.setonback( back[0]);
                            });
              }
       else {
          deviceAdded(act);
          closer.run();
          }
      };

   MainActivity.setonback(back[0]);

   ok.setOnClickListener(v-> {
        MainActivity.doonback();
        });
   manual.setOnClickListener(v-> {
        MainActivity.poponback();
        closer.run();
        getUserid(name,idstring,act);
        });
   retrieve.setOnClickListener(v-> {
        MainActivity.poponback();
        closer.run();
        gs3fromserver(name,act);
        });

    }
    }
