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

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.Backup.getnumedit;
import static tk.glucodata.MainActivity.CHAIN_REQUEST;
import static tk.glucodata.MainActivity.PRIVATE_REQUEST;
import static tk.glucodata.MainActivity.doonback;
import static tk.glucodata.MainActivity.poponback;
import static tk.glucodata.MainActivity.setonback;
import static tk.glucodata.Natives.getreceiveport;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import tk.glucodata.settings.LibreNumbers;

public class Nightscout {
static final private String LOG_ID="Nightscout";
static private void openfile(Activity act,int requestid) {
	try {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		String name= MainActivity.keys[requestid&~PRIVATE_REQUEST];
		intent.setType("*/*");
        	intent.putExtra(Intent.EXTRA_TITLE, name);

		act.startActivityForResult(intent, requestid);
	}
	catch(Throwable th) {
		Log.stack(LOG_ID,"openfile",th);
		}
    }

static final private int MAXKEY=80;

static boolean validHttpServerPort(int port) {
	return port>=1024&&port<=65535&&port!=17580;
	}

private static void setFormError(TextView error,CharSequence message) {
	error.setText(message);
	error.setVisibility(message==null||message.length()==0?View.GONE:View.VISIBLE);
	}

public static void show(MainActivity context,View parent) {
	EnableControls(parent,false);
	String initialSecret=Natives.getApiSecret();
	String[] savedSecret={initialSecret};
	EditText secret=new EditText(context);
	secret.setImeOptions(editoptions);
	secret.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
	secret.setTransformationMethod(new PasswordTransformationMethod());
	secret.setText(initialSecret);
	ConnectionUi.styleInput(secret);
	EditText sslPort=getnumedit(context,String.valueOf(Natives.getsslport()));
	EditText interval=getnumedit(context,String.valueOf(Natives.getinterval()));
	ConnectionUi.styleInput(sslPort);
	ConnectionUi.styleInput(interval);

	CheckDirectionBox showSecret=getcheckbox(context,R.string.connection_show_secret,false);
	showSecret.setOnCheckedChangeListener((button,checked)-> {
		int selection=secret.getSelectionStart();
		secret.setInputType(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
				InputType.TYPE_TEXT_VARIATION_PASSWORD);
		secret.setTransformationMethod(checked?null:new PasswordTransformationMethod());
		secret.setSelection(Math.max(0,Math.min(selection,secret.length())));
		});
	CheckDirectionBox active=getcheckbox(context,R.string.active,Natives.getusexdripwebserver());
	active.setOnCheckedChangeListener((button,checked)->Natives.setusexdripwebserver(checked));
	CheckDirectionBox ssl=getcheckbox(context,R.string.usessl,Natives.getuseSSL());
	boolean[] sslChange={true};
	ssl.setOnCheckedChangeListener((button,checked)-> {
		if(!sslChange[0])
			return;
		String result=Natives.setuseSSL(checked);
		if(result!=null) {
			sslChange[0]=false;
			ssl.setChecked(!checked);
			sslChange[0]=true;
			Applic.argToaster(context,result,Toast.LENGTH_LONG);
			}
		else if(checked)
			active.setChecked(true);
		});
	CheckDirectionBox local=getcheckbox(context,R.string.localonly,Natives.getXdripServerLocal());
	local.setOnCheckedChangeListener((button,checked)->Natives.setXdripServerLocal(checked));
	CheckDirectionBox treatments=getcheckbox(context,R.string.treatments,Natives.getsaytreatments());

	Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
	LinearLayout certificate=ClinicalUi.actionRow(context,
			context.getString(R.string.fullchain),context.getString(R.string.connection_certificate_hint));
	LinearLayout privateKey=ClinicalUi.actionRow(context,
			context.getString(R.string.privatekey),context.getString(R.string.connection_private_key_hint));
	LinearLayout serverHelp=ClinicalUi.actionRow(context,
			context.getString(R.string.helpname),context.getString(R.string.connection_web_help_hint));
	Button save=ClinicalUi.button(context,context.getString(R.string.save),
			ClinicalUi.ButtonRole.PRIMARY);

	LinearLayout content=ConnectionUi.content(context);
	content.addView(ClinicalUi.header(context,
			context.getString(R.string.connection_web_server_title),cancel));
	content.addView(ConnectionUi.intro(context,R.string.connection_web_server_intro));
	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.connection_server_section)));
	content.addView(ClinicalUi.card(context,
			ConnectionUi.directToggle(context,active),
			ConnectionUi.directToggle(context,local),
			ConnectionUi.directToggle(context,treatments)));
	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.connection_network_section)));
	content.addView(ClinicalUi.card(context,
			ClinicalUi.fieldRow(context,context.getString(R.string.connection_ssl_port),sslPort),
			ClinicalUi.fieldRow(context,context.getString(R.string.connection_update_interval),interval)));
	TextView httpNote=ConnectionUi.status(context,
			context.getString(R.string.connection_http_port_note),false);
	LinearLayout.LayoutParams noteParams=new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
	noteParams.topMargin=ClinicalUi.dp(context,10);
	httpNote.setLayoutParams(noteParams);
	content.addView(httpNote);
	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.connection_security_section)));
	content.addView(ClinicalUi.card(context,
			ClinicalUi.fieldRow(context,context.getString(R.string.secret),secret),
			ConnectionUi.directToggle(context,showSecret),
			ConnectionUi.directToggle(context,ssl),certificate,privateKey));
	String nativeError=Natives.nightError();
	TextView formError=ConnectionUi.status(context,nativeError,true);
	LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
	errorParams.topMargin=ClinicalUi.dp(context,14);
	formError.setLayoutParams(errorParams);
	content.addView(formError);
	content.addView(ClinicalUi.sectionLabel(context,
			context.getString(R.string.connection_support_section)));
	content.addView(ClinicalUi.card(context,serverHelp));
	LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
	saveParams.topMargin=ClinicalUi.dp(context,20);
	save.setLayoutParams(saveParams);
	content.addView(save);
	ScrollView screen=ConnectionUi.screen(context,content);
	ConnectionUi.fullScreen(context,screen);

	certificate.setOnClickListener(view->openfile(context,CHAIN_REQUEST));
	privateKey.setOnClickListener(view->openfile(context,PRIVATE_REQUEST));
	serverHelp.setOnClickListener(view->help.help(R.string.Nightscouthelp,context));
	int[] noTreatmentChange={0};
	treatments.setOnCheckedChangeListener((button,checked)-> {
		switch(noTreatmentChange[0]) {
			case 0:
				noTreatmentChange[0]++;
				treatments.setChecked(!checked);
				LibreNumbers.mklayout(context,1,treatments,noTreatmentChange,screen);
				break;
			case 2:
				Natives.setsaytreatments(checked);
				break;
			default:
				break;
			}
		});

	Runnable closeNow=()-> {
		poponback();
		EnableControls(parent,true);
		hidekeyboard(context);
		removeContentView(screen);
		};
	Runnable[] closeRequest={null};
	closeRequest[0]=()-> {
		setonback(closeRequest[0]);
		boolean unchanged=secret.getText().toString().equals(savedSecret[0])
				&&sslPort.getText().toString().equals(String.valueOf(Natives.getsslport()))
				&&interval.getText().toString().equals(String.valueOf(Natives.getinterval()));
		if(unchanged)
			closeNow.run();
		else
			ConnectionUi.confirmSheet(context,screen,
					context.getString(R.string.connection_discard_title),
					context.getString(R.string.connection_discard_message),
					context.getString(R.string.connection_discard_action),
					ClinicalUi.ButtonRole.DANGER,closeNow);
		};
	setonback(closeRequest[0]);
	cancel.setOnClickListener(view->doonback());
	save.setOnClickListener(view-> {
		String newSecret=secret.getText().toString();
		if(newSecret.length()>=MAXKEY) {
			setFormError(formError,context.getString(R.string.connection_secret_too_long,MAXKEY-1));
			return;
			}
		int port;
		int seconds;
		try {
			port=Integer.parseInt(sslPort.getText().toString().trim());
			}
		catch(Throwable error) {
			setFormError(formError,context.getString(R.string.connection_invalid_port));
			return;
			}
		if(sslPort.getText().toString().trim().equals(getreceiveport())) {
			setFormError(formError,context.getString(R.string.nomirrorport));
			return;
			}
		if(!validHttpServerPort(port)) {
			setFormError(formError,port==17580?context.getString(R.string.nohttpport):
					context.getString(R.string.portrange));
			return;
			}
		try {
			seconds=Integer.parseInt(interval.getText().toString().trim());
			}
		catch(Throwable error) {
			setFormError(formError,context.getString(R.string.connection_invalid_interval));
			return;
			}
		if(!newSecret.equals(savedSecret[0])) {
			savedSecret[0]=newSecret;
			Natives.setApiSecret(newSecret);
			}
		if(port!=Natives.getsslport()) {
			Natives.setsslport(port);
			if(Natives.getuseSSL())
				Natives.setuseSSL(true);
			}
		Natives.setinterval(seconds);
		setFormError(formError,Natives.nightError());
		hidekeyboard(context);
		Applic.argToaster(context,R.string.saved,Toast.LENGTH_SHORT);
		});
	}

@SuppressWarnings("unused")
private static void legacyShow(MainActivity context,View parent) {
   	EnableControls(parent,false);

	var save=getbutton(context,R.string.save);
	var secret=getlabel(context,R.string.secret);

	String key=Natives.getApiSecret();
	String[] oldkey={key};
    var editkey= new EditText(context);
        editkey.setImeOptions(editoptions);
        editkey.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editkey.setTransformationMethod(new PasswordTransformationMethod());
	 editkey.setMinEms(12);
	editkey.setText(key);

       var visible = new CheckDirectionBox(context);
       //visible.setText(R.string.visible);
       visible.setButtonDrawable(R.drawable.password_visible);
/*      visible.setMinimumWidth(0); visible.setMinWidth(0);*/
        visible.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
                        var sel=editkey.getSelectionStart();
                        editkey.setInputType(isChecked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        if(isChecked)
                                        editkey.setTransformationMethod(null);
                        else
                                        editkey.setTransformationMethod(new PasswordTransformationMethod());
                        editkey.setSelection(sel);
                        });
	 var labport=getlabel(context,"SSL "+context.getString(R.string.port));
	var oldport=Natives.getsslport();
  	var portview=getnumedit(context, ""+oldport);
                        
	 var labinterval=getlabel(context,R.string.interval);
	int interval=Natives.getinterval();
  	var intervalview=getnumedit(context, ""+interval);
   
	save.setOnClickListener(
		v -> {
		 var newkey=editkey.getText().toString();
		 if(newkey.length()>=MAXKEY) {
			Applic.argToaster(context,newkey + context.getString(R.string.toolongsecret)+MAXKEY, Toast.LENGTH_LONG);
			return;
		 	}
		 var portstr=portview.getText().toString();
		 int portnum=0;
		 try {
                        portnum=Integer.parseInt(portstr);
                        }
                catch(Throwable e) {
                        Log.stack(LOG_ID,"parseInt", e);
			Applic.argToaster(context,portstr+context.getString(R.string.invalidport), Toast.LENGTH_LONG);
			return;
                        };
		if(portstr.equals(getreceiveport())) {
			Applic.argToaster(context,R.string.nomirrorport,Toast.LENGTH_LONG);
			return;
			}
		if(portnum==17580) {
			Applic.argToaster(context,R.string.nohttpport,Toast.LENGTH_LONG);
			return;
			}	
		if(portnum<1024||portnum> 65535) {
			Applic.argToaster(context,R.string.portrange,Toast.LENGTH_LONG);
			return;
			}
		 if(!newkey.equals(oldkey[0])) {
		 	oldkey[0]=newkey;
			Applic.argToaster(context,context.getString(R.string.newsecret)+newkey, Toast.LENGTH_LONG);
		 	Natives.setApiSecret(newkey);
			}
		if(portnum!= Natives.getsslport()) {
			Natives.setsslport(portnum);
			Applic.argToaster(context,context.getString(R.string.newport)+portstr, Toast.LENGTH_LONG);
			if(Natives.getuseSSL())
				Natives.setuseSSL(true);
			}

		 var intervalstr=intervalview.getText().toString();
		 int intervalnum=0;
		 try {
                        intervalnum=Integer.parseInt(intervalstr);
                        }
                catch(Throwable e) {
                        Log.stack(LOG_ID,"parseInt", e);
			Applic.argToaster(context,intervalstr+" invalid", Toast.LENGTH_LONG);
			return;
                        };
		Natives.setinterval(intervalnum);
		tk.glucodata.help.hidekeyboard(context);
		Applic.argToaster(context, R.string.saved,Toast.LENGTH_SHORT);
		});
	var chain=getbutton(context,R.string.fullchain);
	chain.setOnClickListener(
		v -> {
			openfile(context,CHAIN_REQUEST);
		});

	var local=getcheckbox(context,R.string.localonly,Natives.getXdripServerLocal( ));
	float density=GlucoseCurve.metrics.density;
	int laypad=(int)(density*4.0);
	var httpport=getlabel(context,"http "+context.getString(R.string.port)+"=17580");
	httpport.setPadding(laypad*3,0,0,0);
	local.setOnCheckedChangeListener(
			 (buttonView,  isChecked) -> {
				Natives.setXdripServerLocal(isChecked);
			 });

	var privkey=getbutton(context,R.string.privatekey);
	privkey.setOnClickListener(
		v -> {
			openfile(context,PRIVATE_REQUEST);
		});
	var Help=getbutton(context,R.string.helpname);
	Help.setOnClickListener(
		v-> {
           if(parent.getX()>0)
                help.helplight(R.string.Nightscouthelp, (MainActivity) context);
            else
                help.help(R.string.Nightscouthelp,context);
		});
		

	var Close=getbutton(context,R.string.closename);

	var usexdripserver=Natives.getusexdripwebserver();
	var server=getcheckbox(context,R.string.active,usexdripserver);
	server.setOnCheckedChangeListener((buttonView, isChecked)-> {
		  Natives.setusexdripwebserver(isChecked);
		  });
	boolean usessl=Natives.getuseSSL();
	var sslbox=getcheckbox(context,R.string.usessl,usessl);
	boolean[] enabled={true};
	sslbox.setOnCheckedChangeListener((buttonView, isChecked)-> {
		if(enabled[0]) {
			String res=Natives.setuseSSL(isChecked);
			if(res!=null) {
				enabled[0]=false;
				sslbox.setChecked(!isChecked);
				enabled[0]=true;
				Applic.argToaster(context, res, Toast.LENGTH_LONG);
				}
			else {
				if(isChecked)
					server.setChecked(true);
				}
			}
		});
	boolean saytreatments=Natives.getsaytreatments();
	var treatments=getcheckbox(context,R.string.treatments,saytreatments);
	int[] nochangeamounts={0};

	var errstr=Natives.nightError();
	var errorrow=errstr.length()>0?new View[]{getlabel(context,errstr)}:null;
	var layout=new Layout(context,(l,w,h)-> {
        /*
		var width= GlucoseCurve.getwidth();
		if(width>w)
			l.setX((width-w)/2);
		l.setY(MainActivity.systembarTop); */
		return new int[] {w,h};
		},new View[]{secret,editkey,visible},new View[]{labport,portview,labinterval,intervalview} , new View[]{sslbox,privkey,chain,save},new View[]{local,httpport,treatments},errorrow,new View[]{Help,server,Close} );
	treatments.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
		switch(nochangeamounts[0])  {
			case 0: {
				++nochangeamounts[0];
				treatments.setChecked(!isChecked);
				LibreNumbers.mklayout(context,1,treatments,nochangeamounts,layout);
				};break;
			case  2: Natives.setsaytreatments(isChecked);break;

			};
		});
	layout.setPadding(laypad*2,laypad,laypad*2,laypad);

	layout.setBackgroundResource(R.drawable.dialogbackground);

    var  params =    
            new FrameLayout.LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL);

    context.addMyContentView(layout, params);
//	context.addMyContentView(layout, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
	Runnable[] closeproc={null};

        closeproc[0]=()-> {
                         var newkey=editkey.getText().toString();
                        setonback(closeproc[0]);
                         Runnable okproc= () -> {
                                poponback();
                                EnableControls(parent,true);
                                hidekeyboard(context);
                                removeContentView(layout); 
                                };
                         if(newkey.equals(oldkey[0])) {
                                 var portstr=portview.getText().toString();
                                 int portnum=0;
                                 try {
                                        portnum=Integer.parseInt(portstr);
                                        }
                                catch(Throwable e) {
                                        Log.stack(LOG_ID,"parseInt", e);
                                        };
                                if(portnum== Natives.getsslport()) {
                                         var intervalstr=intervalview.getText().toString();
                                         int intervalnum=0;
                                         try {
                                                intervalnum=Integer.parseInt(intervalstr);
                                                }
                                        catch(Throwable e) {
                                                Log.stack(LOG_ID,"parseInt", e);
                                                };
                                        if(intervalnum==Natives.getinterval())  {
                                                okproc.run();
                                                return;
                                                }
                                        }
                                }
                        Confirm.ask(context,context.getString(R.string.withoutsaving),"",okproc);
                        };

	setonback( closeproc[0]);
	Close.setOnClickListener(
		v -> {
              doonback();

			});




		};
};
