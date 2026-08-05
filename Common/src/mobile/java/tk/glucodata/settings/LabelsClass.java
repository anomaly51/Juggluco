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
/*      Sun Apr 16 20:58:46 CEST 2023                                                 */


package tk.glucodata.settings;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.getscreenwidth;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.help;
import static tk.glucodata.settings.Settings.edit2float;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.hideSystemUI;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getlabel;

import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.ClinicalUi;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.LabelAdapter;
import tk.glucodata.Layout;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

class LabelsClass {
    private static final String LOG_ID="LabelsClass";
final MainActivity activity;
LabelsClass(MainActivity context ) {
    activity=context;
    }

boolean garminwatch=Natives.gethasgarmin();;
ArrayList<String > labels;
    EditText label;
EditText labelprec;
EditText labelweight;
Button delete=null;
   int labelpos=-1;
LabelListAdapter    adapt;
void mkchangelabel(MainActivity context,Runnable onsave,View parent) {
        EnableControls(parent,false);
        label = new EditText(context);
        label.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        label.setImeOptions(editoptions);
        label.setMinEms(10);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));

        if(garminwatch) {
            labelprec = new EditText(context);
            labelprec.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            labelprec.setMinEms(3);
            labelprec.setImeOptions(editoptions);
            labelprec.setLayoutParams(new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            }
        labelweight = new EditText(context);
        labelweight.setInputType( InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        labelweight.setMinEms(3);
        labelweight.setImeOptions(editoptions);
        labelweight.setLayoutParams(new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));

        Button cancel=ClinicalUi.button(context,context.getString(R.string.cancel),
                ClinicalUi.ButtonRole.SECONDARY);
        Button save=ClinicalUi.button(context,context.getString(R.string.save),
                ClinicalUi.ButtonRole.PRIMARY);
        Button help=ClinicalUi.button(context,context.getString(R.string.helpname),
                ClinicalUi.ButtonRole.SECONDARY);
        help.setOnClickListener(v->{help(R.string.newlabelhelp,(MainActivity)(v.getContext()));});

        LinearLayout content=ClinicalUi.verticalContent(context);
        content.setPadding(
                MainActivity.systembarLeft+ClinicalUi.dp(context,20),
                MainActivity.systembarTop+ClinicalUi.dp(context,8),
                MainActivity.systembarRight+ClinicalUi.dp(context,20),
                MainActivity.systembarBottom+ClinicalUi.dp(context,28));
        content.addView(ClinicalUi.header(context,
                context.getString(R.string.numlabel),cancel));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.numlabel)));
        java.util.ArrayList<View> fields=new java.util.ArrayList<>();
        fields.add(ClinicalUi.fieldRow(context,
                context.getString(R.string.numlabel),label));
        if(garminwatch)
            fields.add(ClinicalUi.fieldRow(context,
                    context.getString(R.string.roundto),labelprec));
        fields.add(ClinicalUi.fieldRow(context,
                context.getString(R.string.weight),labelweight));
        content.addView(ClinicalUi.card(context,fields.toArray(new View[0])));

        LinearLayout actions=new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f);
        half.setMarginEnd(ClinicalUi.dp(context,6));
        actions.addView(help,half);
        LinearLayout.LayoutParams primary=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.35f);
        primary.setMarginStart(ClinicalUi.dp(context,6));
        actions.addView(save,primary);
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.helpname)));
        content.addView(actions);

        ScrollView editlabel=ClinicalUi.scrollScreen(context,content);
        cancel.setOnClickListener(v->context.doonback());
    if(Natives.staticnum()) {
        save.setVisibility(GONE);
        }
    else {
        save.setOnClickListener(v-> {
            float pr=garminwatch?edit2float(labelprec):0;
            float wei=edit2float(labelweight);
            String name=label.getText().toString();
            int pos=(labelpos>=0) ? labelpos:labels.size()-1;
            if(wei>0.0)  {
                  Toast.makeText(context,String.format(Applic.usedlocale,context.getString(R.string.usedweight),wei), Toast.LENGTH_LONG).show();
                }
            if(!Natives.setlabel(pos,name,pr,wei)) {
                Applic.argToaster(context, name+context.getString(R.string.toolarg), Toast.LENGTH_SHORT);
                return;
                }
            if(labelpos>=0) {
                labels.set(pos,name);
                }
            else {
                labels.add(pos,name);
              if(delete!=null) {
                    delete.setVisibility(VISIBLE);
                    }

                }
            tk.glucodata.help.hidekeyboard(activity) ;

            removeContentView(editlabel);
            adapt.notifyDataSetChanged();
            EnableControls(parent,true);
            onsave.run();
            context.poponback();
            } );
        }
        context.addMyContentView(editlabel,new ViewGroup.LayoutParams(
                MATCH_PARENT,MATCH_PARENT));



    context.setonback(() -> {
        tk.glucodata.help.hidekeyboard(activity) ;
        EnableControls(parent,true);
        removeContentView(editlabel) ;
        });
}

private void    dodeletelast(Spinner spinner,    LabelAdapter<String> numspinadapt,Button addnew, int nr) {
    Natives.setnrlabel(nr);
    {if(doLog) {Log.i(LOG_ID,"voor remove labels.size()="+labels.size());};};
    labels.remove(nr); //USE
    {if(doLog) {Log.i(LOG_ID,"na remove labels.size()="+labels.size());};};
    adapt.notifyDataSetChanged();
    numspinadapt.setarray(Natives.getLabels());
    spinner.setAdapter(numspinadapt);
    spinner.setSelection(Natives.getmealvar());

    if((labels.size()-1)<40) {
        {if(doLog) {Log.i(LOG_ID,"addnew.setVisibility(VISIBLE)");};};
        addnew.setVisibility(VISIBLE);
        }
    }

private void    askdeletelast(Spinner spinner,    LabelAdapter<String> numspinadapt, Button addnew,int nr) {

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.deletelabel).
     setMessage(labels.get(nr)).
        setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                 dodeletelast(spinner,numspinadapt,addnew,nr);
                    }
                }) .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        }).show().setCanceledOnTouchOutside(false);
    }

void    mklabellayout(View parent ) {
    parent.setVisibility(INVISIBLE);
    labels=Applic.app.getlabels();
    MainActivity context = activity;
    Button ok=ClinicalUi.button(context,context.getString(R.string.closename),
            ClinicalUi.ButtonRole.SECONDARY);
    RecyclerView recycle = new RecyclerView(context);
    recycle.setHasFixedSize(true);
    LinearLayoutManager lin = new LinearLayoutManager(context);
    recycle.setLayoutManager(lin);
    recycle.setClipToPadding(false);
    recycle.setPadding(0,ClinicalUi.dp(context,8),0,ClinicalUi.dp(context,12));
    Button addnew=ClinicalUi.button(context,context.getString(R.string.newname),
            ClinicalUi.ButtonRole.PRIMARY);

    Spinner spinner=new Spinner(context);
    final int minheight= GlucoseCurve.dpToPx(48);
    spinner.setMinimumHeight(minheight);
    avoidSpinnerDropdownFocus(spinner);
    LabelAdapter<String> numspinadapt=new LabelAdapter<String>(context,Natives.getLabels(),0);
    spinner.setAdapter(numspinadapt);
    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            Natives.setmealvar((byte)position);
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });
    final Runnable onsave= ()->  {
                numspinadapt.setarray(Natives.getLabels());
                spinner.setAdapter(numspinadapt);
                spinner.setSelection(Natives.getmealvar());
                if((labels.size()-1)>=40)
                    addnew.setVisibility(INVISIBLE);
                };

    delete=ClinicalUi.button(context,context.getString(R.string.deletelast),
            ClinicalUi.ButtonRole.DANGER);
    TextView menulabel=getlabel(context,context.getString(R.string.meal));
//    spinner.clearAnimation();
    spinner.setSelection(Natives.getmealvar());
    Layout.getMargins(spinner).setMarginEnd((int)(tk.glucodata.GlucoseCurve.metrics.density*8.0));
    Button help=ClinicalUi.button(context,context.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    help.setOnClickListener(v->{help(R.string.labelhelp,context); });

    LinearLayout labellayout=new LinearLayout(context);
    labellayout.setOrientation(LinearLayout.VERTICAL);
    labellayout.setBackgroundColor(ClinicalUi.window(context));
    labellayout.setPadding(
            MainActivity.systembarLeft+ClinicalUi.dp(context,20),
            MainActivity.systembarTop+ClinicalUi.dp(context,8),
            MainActivity.systembarRight+ClinicalUi.dp(context,20),
            MainActivity.systembarBottom+ClinicalUi.dp(context,16));
    labellayout.addView(ClinicalUi.header(context,
            context.getString(R.string.numberlabels),ok));
    labellayout.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.meal)));
    labellayout.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,context.getString(R.string.meal),spinner)));
    labellayout.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.numberlabels)));
    labellayout.addView(recycle,new LinearLayout.LayoutParams(
            MATCH_PARENT,0,1.0f));

    LinearLayout secondaryActions=new LinearLayout(context);
    secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams actionLeft=new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,1.0f);
    actionLeft.setMarginEnd(ClinicalUi.dp(context,6));
    secondaryActions.addView(help,actionLeft);
    LinearLayout.LayoutParams actionRight=new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,1.0f);
    actionRight.setMarginStart(ClinicalUi.dp(context,6));
    secondaryActions.addView(delete,actionRight);
    labellayout.addView(secondaryActions);
    LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    addParams.topMargin=ClinicalUi.dp(context,12);
    labellayout.addView(addnew,addParams);

    adapt = new LabelListAdapter(labels, this,onsave,labellayout); //USE
    recycle.setAdapter(adapt);

    if (Natives.staticnum()) {
        addnew.setVisibility(INVISIBLE);
        delete.setVisibility(INVISIBLE);
    } else {
        addnew.setOnClickListener(v -> {
            mkchangelabel(activity,onsave,labellayout); //USE
            label.setText("");
            labelpos = -1;
        });
        if((labels.size()-1)>=40)
            addnew.setVisibility(INVISIBLE);


        delete.setOnClickListener(v -> {
            int nr = labels.size() - 2; //USE
            {if(doLog) {Log.d(LOG_ID, "delete " + nr);};};
            if (nr >= 0) {
                askdeletelast(spinner,numspinadapt,addnew, nr);
                }
            if (nr <= 0)
                delete.setVisibility(INVISIBLE);
        });
    if (labels.size() < 2)  //USE
        delete.setVisibility(INVISIBLE);

    }

    Runnable closerun=()-> {
        parent.setVisibility(VISIBLE);
         tk.glucodata.help.hidekeyboard(activity) ;
        Applic app=(tk.glucodata.Applic )context.getApplication();
        if(Natives.shouldsendlabels())  {
            Applic.wakemirrors();
            app.sendlabels();
        }
        /*
        if(app.curve!=null&&app.curve.search!=null) {
            app.curve.searchspinadap.setarray(Natives.getLabels());
            app.curve.searchspinner.setAdapter(app.curve.searchspinadap);
            } */
        removeContentView(labellayout) ;
        };
    context.setonback(closerun);
    ok.setOnClickListener(v->{
        context.poponback();
        closerun.run();
    });

        context.addMyContentView(labellayout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
   /* }
    else {
        labellayout.setVisibility(VISIBLE); 
          labellayout.bringToFront();
    }*/
}
static public class LabelListAdapter extends RecyclerView.Adapter<LabelListHolder> {
    ArrayList<String> labels=null;
    LabelsClass settings;
    View parlayout;
    Runnable onsave;
    LabelListAdapter(ArrayList<String> labels,LabelsClass set,Runnable onsave,View parlayout) {
        this.labels=labels;
        this.parlayout=parlayout;
        this.onsave=onsave;
        settings=set;
         }

    @NonNull
    @Override
    public LabelListHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Button view = ClinicalUi.button(parent.getContext(), "", ClinicalUi.ButtonRole.SECONDARY);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        view.setSingleLine(false);
        view.setMinHeight(ClinicalUi.dp(parent.getContext(), 58));
        view.setPaddingRelative(
                ClinicalUi.dp(parent.getContext(), 16),
                ClinicalUi.dp(parent.getContext(), 10),
                ClinicalUi.dp(parent.getContext(), 16),
                ClinicalUi.dp(parent.getContext(), 10));
        view.setBackground(ClinicalUi.surface(parent.getContext(), false, true));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ClinicalUi.dp(parent.getContext(), 8);
        view.setLayoutParams(params);

        return new LabelListHolder(view,settings,onsave,this.parlayout);

    }

    @Override
    public void onBindViewHolder(final LabelListHolder holder, int pos) {
        TextView text=(TextView)holder.itemView;
        text.setText(labels.get(pos));
        }
        @Override
        public int getItemCount() {
                return labels.size()-1;

        }

}
}
