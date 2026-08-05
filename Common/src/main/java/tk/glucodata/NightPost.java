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
/*      Thu Mar 23 21:04:47 CET 2023                                                 */


package tk.glucodata;
import static android.view.View.GONE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;

import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.stackline;
import static tk.glucodata.Natives.setNightUploader;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.bluediag.datestr;
import static tk.glucodata.help.help;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
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

import androidx.annotation.Keep;

import com.google.android.gms.security.ProviderInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.SSLContext;

import tk.glucodata.settings.LibreNumbers;

public class NightPost  {
    private static final String LOG_ID="NightPost";

private static void patch() {
      try {
          ProviderInstaller.installIfNeeded(Applic.app);
      }
    catch(Throwable th) {
        uploadstatus= "ProviderInstaller.installIfNeeded: \n"+stackline(th);
         Log.e(LOG_ID,uploadstatus);
          }
      }

static String getstring(HttpURLConnection con)  throws IOException{
    try(BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
        StringBuffer response = new StringBuffer();
        String inputLine;
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
            }
        return response.toString();
        }
    finally {
        con.disconnect();
        }
    }

private static  String getstart(HttpURLConnection con,int max)  throws IOException{
    try(var in = con.getInputStream()) {
        int len=max;
        byte[] buf=new byte[len];
        int res=in.read(buf,0,len);
        return new String(buf,0,res);
        }
    finally {
        con.disconnect();
        }
    }

/** Identity sentinels: localization is resolved lazily when a status is rendered. */
final static String nothing=new String("nightpost:nothing");
final static String success=new String("nightpost:success");
static private String uploadstatus=nothing;

static String visibleStatus(Context context,String status) {
    if(status==nothing)
        return context.getString(R.string.triednothing);
    if(status==success)
        return context.getString(R.string.success);
    return status==null?"":status;
    }
@Keep
static public boolean deleteUrl(String urlstring,String secret) {
    patch();
    uploadtime=System.currentTimeMillis();
    {if(doLog) {Log.i(LOG_ID,"deleteUrl "+urlstring+" "+ secret);};};
    try {
        URL url = new URL(urlstring);
        if(url==null)  {
            uploadstatus="URL("+urlstring+")==null";
            return false;
            }
    uploadstatus=" start deleteURL "+urlstring;
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(10000);
        urlConnection.setReadTimeout(60000);
        if(secret!=null)
                urlConnection.setRequestProperty("api-secret", secret);
        else
            urlConnection.setRequestProperty("Authorization", gettoken(uploadtime));
        urlConnection.setRequestProperty("Content-Type", "application/json");
        urlConnection.setRequestMethod("DELETE");

        final int code=urlConnection.getResponseCode();
        String res=getstring(urlConnection);
        if(code==HTTP_OK) {
            {if(doLog) {Log.i(LOG_ID,"deleteUrl success "+res);};};
            uploadstatus=success;
            return true;
            }
        else {
            String delerror="deleteUrl "+urlstring+" failure code="+code+'\n'+res;
            Log.e(LOG_ID,delerror);
            uploadstatus=delerror;
            return false;
            }

        }
    catch(Throwable th) {
        String error ="deleteUrl error:\n"+stackline(th);
        uploadstatus=error;
        Log.e(LOG_ID,error);
        return false;
        }
    }

/*
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3NUb2tlbiI6ImFhcHMtOTQ0Y2YzZGVkYTMxMTkxNCIsImlhdCI6MTcwODg1NDE1NiwiZXhwIjoxNzA4ODgyOTU2fQ.YrNGSUPiz-3zxv6ZxfOO_Sm98bKrK0eDjZYIR6LPQUY",
  "sub": "aaps",
  "permissionGroups": [
    [
      "*"
    ],
    []
  ],
  "iat": 1708854156,
  "exp": 1708882956
} */
static private long  expire=0L;
static private String token="";

static JSONObject  readJSONObject(HttpURLConnection urlConnection)  throws IOException, JSONException {
    String ant=getstring(urlConnection);
    if(doLog) {
        Log.format("%s: readJSONObject len=%d %s\n",LOG_ID,ant.length(),ant);
        }
     return new JSONObject(ant);
    }

private static String gettoken(long now) {
    if(now<expire)
        return token;
    var Nighturl=Natives.getnightuploadurl();
    var secret=Natives.getnightuploadsecret();
    var authstr=Nighturl+ "/api/v2/authorization/request/"+secret;
    try {

        URL url = new URL(authstr);
        HttpURLConnection  urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(10000);
        urlConnection.setReadTimeout(60000);
        urlConnection.setRequestMethod("GET");
        final int code=urlConnection.getResponseCode();
        if(code==HTTP_OK) {
            JSONObject object =  readJSONObject(urlConnection) ;
            final String tokenin=object.getString( "token");
            final var expirein=object.getLong( "exp");
            expire=expirein*1000L;
            token="Bearer "+tokenin;
            return token;
            }
        else {
            uploadstatus="gettoken failed code="+code;
            Log.e(LOG_ID,uploadstatus);
            return "";
            }

        }
    catch(Throwable th) {
        uploadstatus="gettoken:\n"+(th==null?"Network error ":th.getMessage());
        Log.e(LOG_ID,uploadstatus);
        return "";
        }
    }

private static long uploadtime=System.currentTimeMillis();
@Keep
static public int upload(String httpurl,byte[] postdata,String secret,boolean put) {
    patch();
    uploadtime=System.currentTimeMillis();
    {if(doLog) {Log.i(LOG_ID,"upload("+httpurl+",#"+postdata.length+","+ secret+","+(put?"PUT":"POST")+")");};};
    try {

      uploadstatus="start upload "+httpurl;
        URL url = new URL(httpurl);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(10000);
        urlConnection.setReadTimeout(60000);
        urlConnection.setRequestMethod(put?"PUT":"POST");
        urlConnection.setDoOutput(true);
        if(secret!=null)
            urlConnection.setRequestProperty("api-secret", secret);
        else
            urlConnection.setRequestProperty("Authorization", gettoken(uploadtime));
        urlConnection.setRequestProperty("Content-Type", "application/json");
           urlConnection.setRequestProperty( "Content-Length", Integer.toString( postdata.length ));

        OutputStream outputPost = new BufferedOutputStream(urlConnection.getOutputStream());
        outputPost.write(postdata);
        outputPost.flush();
        outputPost.close();
        final int code=urlConnection.getResponseCode();
        String res=getstring(urlConnection);
        final String resstr="upload ResponseCode="+code+"\n"+res;
        if(code!=200&&code!=201) {
            uploadstatus=resstr;
            Log.e(LOG_ID,resstr);
            }
        else {
            uploadstatus=success;
            {if(doLog) {Log.i(LOG_ID,resstr);};};
            }
        return code;
         }
    catch(Throwable th) {
        final String posterror="upload failure:\n"+stackline(th);
        uploadstatus=posterror;
        Log.e(LOG_ID,posterror);
        return -1;
        }
     }
private static void askclearupload(MainActivity context,View parent) {
    ConnectionUi.confirmSheet(context,parent,
            context.getString(R.string.connection_nightscout_resend_title),
            context.getString(R.string.connection_nightscout_resend_message),
            context.getString(R.string.resenddata),ClinicalUi.ButtonRole.DANGER,
            Natives::resetuploader);
    }

static boolean validNightscoutUrl(String value) {
    try {
        URL parsed=new URL(value);
        String scheme=parsed.getProtocol();
        return ("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))
                &&parsed.getHost()!=null&&!parsed.getHost().isEmpty();
        }
    catch(Throwable ignored) {
        return false;
        }
    }

private static void clinicalConfig(MainActivity context,View settingsView) {
    EnableControls(settingsView,false);
    EditText url=getedit(context,Natives.getnightuploadurl());
    EditText secret=new EditText(context);
    secret.setImeOptions(editoptions);
    secret.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
    secret.setTransformationMethod(new PasswordTransformationMethod());
    String previousSecret=Natives.getnightuploadsecret();
    if(previousSecret!=null)
        secret.setText(previousSecret);
    ConnectionUi.styleInput(url);
    ConnectionUi.styleInput(secret);

    CheckDirectionBox showSecret=getcheckbox(context,R.string.connection_show_secret,false);
    showSecret.setOnCheckedChangeListener((button,checked)-> {
        int selection=secret.getSelectionStart();
        secret.setInputType(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setTransformationMethod(checked?null:new PasswordTransformationMethod());
        secret.setSelection(Math.max(0,Math.min(selection,secret.length())));
        });
    CheckDirectionBox active=getcheckbox(context,R.string.active,Natives.getuseuploader());
    CheckDirectionBox apiV3=getcheckbox(context,R.string.connection_nightscout_v3,
            Natives.getnightscoutV3());
    CheckDirectionBox treatments=getcheckbox(context,R.string.sendamounts,
            Natives.getpostTreatments());

    Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
    Button save=ClinicalUi.button(context,context.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    Button resend=ClinicalUi.button(context,context.getString(R.string.resenddata),
            ClinicalUi.ButtonRole.DANGER);
    LinearLayout sendNow=ClinicalUi.actionRow(context,context.getString(R.string.sendnow),
            context.getString(R.string.connection_send_now_hint));
    LinearLayout uploaderHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),context.getString(R.string.connection_uploader_help_hint));
    boolean statusError=uploadstatus!=success&&uploadstatus!=nothing;
    TextView status=ConnectionUi.status(context,
            datestr(uploadtime)+": "+visibleStatus(context,uploadstatus),statusError);
    TextView formError=ConnectionUi.status(context,"",true);

    LinearLayout content=ConnectionUi.content(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.connection_uploader_title),cancel));
    content.addView(ConnectionUi.intro(context,R.string.connection_uploader_intro));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_account_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,"Nightscout URL",url),
            ClinicalUi.fieldRow(context,context.getString(R.string.secret),secret),
            ConnectionUi.directToggle(context,showSecret)));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_upload_section)));
    content.addView(ClinicalUi.card(context,
            ConnectionUi.directToggle(context,active),
            ConnectionUi.directToggle(context,apiV3),
            ConnectionUi.directToggle(context,treatments),sendNow));
    LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    statusParams.topMargin=ClinicalUi.dp(context,12);
    status.setLayoutParams(statusParams);
    content.addView(status);
    formError.setLayoutParams(statusParams);
    content.addView(formError);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_maintenance_section)));
    content.addView(resend,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.connection_support_section)));
    content.addView(ClinicalUi.card(context,uploaderHelp));
    LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    saveParams.topMargin=ClinicalUi.dp(context,20);
    save.setLayoutParams(saveParams);
    content.addView(save);
    ScrollView screen=ConnectionUi.screen(context,content);
    ConnectionUi.fullScreen(context,screen);

    int[] noTreatmentChange={0};
    treatments.setOnCheckedChangeListener((button,checked)-> {
        switch(noTreatmentChange[0]) {
            case 0:
                noTreatmentChange[0]++;
                treatments.setChecked(!checked);
                LibreNumbers.mklayout(context,1,treatments,noTreatmentChange,screen);
                break;
            case 2:
                Natives.setpostTreatments(checked);
                break;
            default:
                break;
            }
        });
    resend.setOnClickListener(view->askclearupload(context,screen));
    sendNow.setOnClickListener(view->Natives.wakeuploader());
    uploaderHelp.setOnClickListener(view->help(R.string.NightPost,context));
    Runnable close=()-> {
        removeContentView(screen);
        EnableControls(settingsView,true);
        tk.glucodata.help.hidekeyboard(context);
        };
    context.setonback(close);
    cancel.setOnClickListener(view-> {
        context.poponback();
        close.run();
        });
    save.setOnClickListener(view-> {
        String endpoint=url.getText().toString().trim();
        if(active.isChecked()&&!validNightscoutUrl(endpoint)) {
            formError.setText(R.string.connection_invalid_url);
            formError.setVisibility(View.VISIBLE);
            return;
            }
        context.poponback();
        close.run();
        setNightUploader(endpoint,secret.getText().toString(),active.isChecked(),apiV3.isChecked());
        });
    }

public static void  config(MainActivity act, View settingsview) {
    if(!isWearable) {
        clinicalConfig(act,settingsview);
        return;
        }
    EnableControls(settingsview,false);
    var urllabel=getlabel(act,"Nightscout URL");
    var url=getedit(act, Natives.getnightuploadurl());
//    final int minems=isWearable?12:16;
    final int minems=12;
        url.setMinEms(minems);
    var secretlabel=getlabel(act,R.string.secret);
    secretlabel.setPadding(0,0,0,0);
    var editsecret= new EditText(act);
        editsecret.setImeOptions(editoptions);
        editsecret.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editsecret.setTransformationMethod(new PasswordTransformationMethod());
     editsecret.setMinEms(minems);
    String secretwas=Natives.getnightuploadsecret();
    if(secretwas!=null) {
        editsecret.setText(secretwas);
        }
    var save=getbutton(act,R.string.save);
    var cancel=getbutton(act,R.string.cancel);
    var clear=getbutton(act,R.string.resenddata);
    clear.setOnClickListener(v->askclearupload(act,null));
    var wake=getbutton(act,act.getString(R.string.sendnow));
    wake.setOnClickListener(v-> Natives.wakeuploader());
    Button help;
    CheckDirectionBox treatments=getcheckbox(act,R.string.sendamounts,Natives.getpostTreatments());
    if(!isWearable) {
        help=getbutton(act,R.string.helpname);
        help.setOnClickListener(v-> help(R.string.NightPost,act));
        }
    final CheckDirectionBox v3box=!isWearable?getcheckbox(act,"test V3",Natives.getnightscoutV3()):null;
    boolean useuploader=Natives.getuseuploader();
    var activebox=getcheckbox(act,R.string.active,useuploader);
       var visible = new CheckDirectionBox(act);
       visible.setButtonDrawable(R.drawable.password_visible);
      visible.setMinimumWidth(0);
      visible.setMinWidth(0);
        getMargins(wake).topMargin= (int)(GlucoseCurve.metrics.density*7.0);
       //visible.setText(R.string.visible);
    int pad= (int)tk.glucodata.GlucoseCurve.metrics.density*7;
    visible.setPadding(0,0,pad,0);
        visible.setOnCheckedChangeListener( (buttonView,  isChecked)-> {

                        var sel=editsecret.getSelectionStart();
                        editsecret.setInputType(isChecked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        if(isChecked)
                                        editsecret.setTransformationMethod(null);
                        else
                                        editsecret.setTransformationMethod(new PasswordTransformationMethod());
                        editsecret.setSelection(sel);
                        });

      var statusview=getlabel(act,datestr(uploadtime)+": "+visibleStatus(act,uploadstatus));
      int statuspad=  (int)tk.glucodata.GlucoseCurve.metrics.density*7;
    statusview.setPadding(statuspad,statuspad,statuspad,statuspad);
    if(!useclose)
        cancel.setVisibility(GONE);
   Layout layout;
   if(isWearable) {
      var space1=new Space(act);
      var space2=new Space(act);
       layout=new Layout(act, (lay, w, h) -> { return new int[] {w,h};}, new View[]{secretlabel},new View[]{visible},new View[]{editsecret},new View[]{urllabel},new View[]{url},new View[]{statusview},new View[]{treatments},new View[]{clear},new View[]{wake},new View[]{space1,activebox,cancel,space2},new View[]{save});
   }
      else {
    layout=new Layout(act, (lay, w, h) -> {
/*
        var height=GlucoseCurve.getheight();
        var width=GlucoseCurve.getwidth();
                        if(w>=width||h>=height) {
                                lay.setX(0);
                                }
                        else {
                                lay.setX((width-w)/2); 
                                };

            lay.setY(MainActivity.systembarTop);
*/
                        return new int[] {w,h};}, new View[]{urllabel,url},new View[]{secretlabel,editsecret,visible},new View[]{statusview},new View[]{activebox,v3box,clear,wake},new View[]{treatments,help,cancel,save});

      }
        final View allview=isWearable?new ScrollView(act):layout;
        ViewGroup.LayoutParams params;
        if(isWearable) {
            ((ScrollView)allview).addView(layout);
            int laypar=ViewGroup.LayoutParams.MATCH_PARENT;
            params=new ViewGroup.LayoutParams(laypar,laypar);
            layout.setBackgroundColor(tk.glucodata.Applic.backgroundcolor);
           int allpad=  (int)tk.glucodata.GlucoseCurve.metrics.density*8;
            layout.setPadding(allpad,allpad,(int)(GlucoseCurve.metrics.density*12.0),allpad);

        treatments.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
              if(isChecked) {
                if(!Natives.canSendNumbers(1)) {
                    Toast.makeText(act, R.string.libresetalllabels, Toast.LENGTH_LONG).show();
                    treatments.setChecked(false);
                    return;
                    }
                  }
                Natives.setpostTreatments(isChecked);
                });
        } else {
            int[] nochangeamounts={0};
            treatments.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
                switch(nochangeamounts[0])  {
                    case 0: {
                        ++nochangeamounts[0];
                        treatments.setChecked(!isChecked);
                        LibreNumbers.mklayout(act,1,treatments,nochangeamounts,layout);
                        };break;
                    case  2: Natives.setpostTreatments(isChecked);break;

                    };
                });
           // laypar=ViewGroup.LayoutParams.WRAP_CONTENT;
              allview.setBackgroundResource(R.drawable.dialogbackground);
               allview.setPadding(pad,pad,pad,pad);

                params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
               }

        act.addMyContentView(allview, params);
        getMargins(allview).topMargin= MainActivity.systembarTop;
    Runnable closerun=()-> {
        allview.setVisibility(GONE);
        removeContentView(allview);
        EnableControls(settingsview,true);
        };
    act.setonback(closerun);
    cancel.setOnClickListener(v->  {
            act.poponback();
            closerun.run();
            });
    save.setOnClickListener(v-> {
            act.poponback();
            closerun.run();
            setNightUploader(url.getText().toString(),editsecret.getText().toString(),activebox.isChecked(),isWearable?false:v3box.isChecked());
            });
    
    }
 }
