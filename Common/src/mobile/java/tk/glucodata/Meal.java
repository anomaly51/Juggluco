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

import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;

import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Random;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import tk.glucodata.nums.numio;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.doonback;
import static tk.glucodata.MainActivity.setonback;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.NumberView.geteditview;

import static tk.glucodata.NumberView.geteditwearos;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.edit2float;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.settings.Settings.str2float;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

class Meal {
private static final String LOG_ID="Meal";
private static Button clinicalListButton(ViewGroup parent) {
    Button view=ClinicalUi.button(parent.getContext(),"",ClinicalUi.ButtonRole.SECONDARY);
    view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP,16f);
    view.setSingleLine(false);
    view.setMinHeight(ClinicalUi.dp(parent.getContext(),58));
    view.setBackground(ClinicalUi.surface(parent.getContext(),false,true));
    RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.bottomMargin=ClinicalUi.dp(parent.getContext(),8);
    view.setLayoutParams(params);
    return view;
    }

private static LinearLayout phoneContent(MainActivity act) {
    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setPadding(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,28));
    return content;
    }

private static Layout phoneRoot(MainActivity act,View screen) {
    screen.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    screen.setMinimumHeight(Math.max(1,GlucoseCurve.getheight()));
    Layout root=new Layout(act,false,(layout,width,height)->new int[]{width,height},
            new View[]{screen});
    root.useMatch=true;
    root.setDistributeExtraSpace(false);
    root.setBackgroundColor(ClinicalUi.window(act));
    return root;
    }

private static void showPhoneRoot(MainActivity act,Layout root) {
    act.addMyContentView(root,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT),false);
    }

private static Button phoneHeaderButton(MainActivity act,int text) {
    return ClinicalUi.button(act,act.getString(text),ClinicalUi.ButtonRole.SECONDARY);
    }

private static void stylePhoneInput(MainActivity act,EditText field) {
    field.setSingleLine(true);
    field.setTextColor(ClinicalUi.primaryText(act));
    field.setHintTextColor(ClinicalUi.secondaryText(act));
    field.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    field.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    field.setMinHeight(ClinicalUi.dp(act,52));
    field.setPaddingRelative(ClinicalUi.dp(act,14),0,ClinicalUi.dp(act,14),0);
    field.setBackground(ClinicalUi.surface(act,false,true));
    }

private static TextView phoneError(MainActivity act) {
    TextView error=ClinicalUi.body(act,"");
    error.setTextColor(ClinicalUi.danger(act));
    error.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,12),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,12));
    error.setBackground(ClinicalUi.surface(act,false,false));
    error.setVisibility(GONE);
    return error;
    }

private static void setPhoneError(TextView error,CharSequence message) {
    boolean empty=message==null||message.length()==0;
    error.setText(empty?"":message);
    error.setVisibility(empty?GONE:VISIBLE);
    if(!empty) {
        error.announceForAccessibility(message);
        }
    }

private static LinearLayout phoneFieldCard(MainActivity act,CharSequence label,View field) {
    return ClinicalUi.card(act,ClinicalUi.fieldRow(act,label,field));
    }

private static TextView phoneValue(MainActivity act,CharSequence text) {
    TextView value=new TextView(act);
    value.setText(text);
    value.setTextColor(ClinicalUi.primaryText(act));
    value.setTextSize(TypedValue.COMPLEX_UNIT_SP,22.0f);
    value.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    value.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,14),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,14));
    value.setBackground(ClinicalUi.surface(act,false,false));
    return value;
    }

private static boolean hasText(EditText field) {
    return field.getText()!=null&&!field.getText().toString().trim().isEmpty();
    }

private static void phoneAskRound(MainActivity act,Runnable runner,View parent) {
    EnableControls(parent,false);
    EditText edit=new EditText(act);
    edit.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    edit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_DONE);
    edit.setText(Float.toString(Natives.getroundto()));
    edit.setHint(R.string.meal_modern_rounding_hint);
    stylePhoneInput(act,edit);
    Button cancel=phoneHeaderButton(act,R.string.cancel);
    Button save=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    TextView error=phoneError(act);
    LinearLayout content=phoneContent(act);
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_rounding_title),cancel));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_rounding_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_rounding_section)));
    content.addView(phoneFieldCard(act,act.getString(R.string.roundto),edit));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,12);
    content.addView(error,errorParams);
    LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    saveParams.topMargin=ClinicalUi.dp(act,18);
    content.addView(save,saveParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    Layout root=phoneRoot(act,screen);
    showPhoneRoot(act,root);
    Runnable close=()->{
        help.hidekeyboard(act);
        removeContentView(root);
        EnableControls(parent,true);
        act.hideSystemUI();
        };
    setonback(close);
    cancel.setOnClickListener(view->doonback());
    save.setOnClickListener(view->{
        if(!hasText(edit)) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_rounding));
            return;
            }
        float value=edit2float(edit);
        if(Float.isNaN(value)||Float.isInfinite(value)||value<0.0f) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_rounding));
            return;
            }
        setPhoneError(error,null);
        doonback();
        Natives.setroundto(value);
        runner.run();
        });
    edit.requestFocus();
    }

private static Layout phoneMealConstructor(final NumberView numb,MainActivity act,
        int mealptr,ObjIntConsumer<Float> give,Runnable endrun) {
    int[] mealptrar={mealptr};
    float[] carbs={Natives.carbinmeal(mealptr)};
    give.accept(carbs[0],mealptr);

    Button close=phoneHeaderButton(act,R.string.closename);
    Button add=ClinicalUi.button(act,act.getString(R.string.meal_modern_add_item),
            ClinicalUi.ButtonRole.PRIMARY);
    Button rounding=ClinicalUi.button(act,"",ClinicalUi.ButtonRole.SECONDARY);
    Button repeat=ClinicalUi.button(act,act.getString(R.string.meal_modern_repeat),
            ClinicalUi.ButtonRole.SECONDARY);
    Button helpButton=ClinicalUi.button(act,act.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    TextView total=phoneValue(act,"");
    TextView empty=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_empty));
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,24),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,24));
    empty.setBackground(ClinicalUi.surface(act,false,false));

    RecyclerView list=new RecyclerView(act);
    list.setLayoutManager(new LinearLayoutManager(act));
    list.setClipToPadding(false);
    list.setPadding(0,ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,8));

    LinearLayout screen=new LinearLayout(act);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(ClinicalUi.window(act));
    screen.setPadding(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,16));
    screen.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_title),close));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    screen.addView(intro);
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_total_section)));
    screen.addView(total,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_items_section)));
    screen.addView(empty,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    screen.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
    screen.addView(add,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams secondaryParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    secondaryParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(rounding,secondaryParams);
    LinearLayout.LayoutParams repeatParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    repeatParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(repeat,repeatParams);
    LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    helpParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(helpButton,helpParams);

    Layout root=phoneRoot(act,screen);
    Consptr selected=new Consptr();
    MealItemViewAdapter adapter=new MealItemViewAdapter(mealptrar,selected);
    list.setAdapter(adapter);
    Runnable update=()->{
        carbs[0]=Natives.carbinmeal(mealptrar[0]);
        total.setText(act.getString(R.string.meal_modern_total_value,carbs[0]));
        rounding.setText(act.getString(R.string.meal_modern_rounding_value,
                Natives.getroundto()));
        int count=Natives.getmealitemnr(mealptrar[0]);
        empty.setVisibility(count==0?VISIBLE:GONE);
        list.setVisibility(count==0?GONE:VISIBLE);
        repeat.setVisibility(carbs[0]==0.0f?GONE:VISIBLE);
        };
    update.run();

    IntConsumer saved=newMealPtr->{
        if(newMealPtr>=0) {
            mealptrar[0]=newMealPtr;
            carbs[0]=Natives.carbinmeal(newMealPtr);
            give.accept(carbs[0],newMealPtr);
            adapter.notifyDataSetChanged();
            }
        root.setVisibility(VISIBLE);
        update.run();
        };
    IntConsumer openItem=index->{
        root.setVisibility(INVISIBLE);
        help.hidekeyboard(act);
        phoneEditMealItem(act,numb,mealptrar[0],index,saved,carbs[0]);
        };
    selected.cons=openItem;
    add.setOnClickListener(view->openItem.accept(-1));
    rounding.setOnClickListener(view->phoneAskRound(act,update,root));
    helpButton.setOnClickListener(view->help.helplight(R.string.mealhelp,act));
    repeat.setOnClickListener(view->{
        removeContentView(root);
        act.hideSystemUI();
        if(mealptrar[0]!=0) {
            if(numb.currentnum!=0&&numb.currentnum!=numio.newhit)
                Natives.hitsetmealptr(numb.currentnum,mealptrar[0]);
            int copy=Natives.cpmeal(mealptrar[0]);
            act.poponback();
            numb.addnumberwithmenu(act,copy);
            }
        });
    close.setOnClickListener(view->doonback());
    setonback(()->{
        root.setVisibility(GONE);
        removeContentView(root);
        help.hidekeyboard(act);
        act.hideSystemUI();
        endrun.run();
        });
    showPhoneRoot(act,root);
    return root;
    }

private static void phoneEditMealItem(MainActivity act,NumberView numb,int mealptr,
        int pos,IntConsumer give,float carbMealIn) {
    EditText amount=new EditText(act);
    EditText itemTotal=new EditText(act);
    EditText mealTotal=new EditText(act);
    for(EditText field:new EditText[]{amount,itemTotal,mealTotal}) {
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
                EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_DONE);
        stylePhoneInput(act,field);
        }
    Button ingredient=ClinicalUi.button(act,
            act.getString(R.string.meal_modern_choose_ingredient),
            ClinicalUi.ButtonRole.SECONDARY);
    TextView carbInfo=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_no_ingredient));
    carbInfo.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,12),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,12));
    carbInfo.setBackground(ClinicalUi.surface(act,false,false));
    Button cancel=phoneHeaderButton(act,R.string.cancel);
    Button save=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    Button delete=ClinicalUi.button(act,act.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    TextView error=phoneError(act);
    float[] carbPerUnit={0.0f};
    int[] ingredientIndex={-1};
    float baseMealCarbs=carbMealIn;
    if(pos>=0) {
        float oldAmount=Natives.getitemamount(mealptr,pos);
        amount.setText(ondecimal(oldAmount,10));
        int oldIngredient=Natives.getitemingredient(mealptr,pos);
        ingredientIndex[0]=oldIngredient;
        String ingredientName=Natives.ingredientName(oldIngredient);
        ingredient.setText(ingredientName);
        String unit=Natives.ingredientUnitName(oldIngredient);
        float carb=Natives.ingredientCarb(oldIngredient);
        carbPerUnit[0]=carb;
        carbInfo.setText(act.getString(R.string.meal_modern_carb_value,carb,unit));
        float total=oldAmount*carb;
        itemTotal.setText(ondecimal(total,10));
        mealTotal.setText(ondecimal(carbMealIn,10));
        baseMealCarbs-=total;
        }
    else {
        delete.setVisibility(GONE);
        itemTotal.setText("0");
        mealTotal.setText(ondecimal(carbMealIn,10));
        }
    final float mealBase=baseMealCarbs;
    boolean[] changing={false};
    amount.addTextChangedListener(new TextWatcher() {
        public void afterTextChanged(Editable value) {
            if(changing[0]) return;
            changing[0]=true;
            float total=str2float(value.toString())*carbPerUnit[0];
            itemTotal.setText(ondecimal(total,10));
            mealTotal.setText(ondecimal(mealBase+total,10));
            changing[0]=false;
            }
        public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
        public void onTextChanged(CharSequence s,int start,int before,int count) {}
        });
    itemTotal.addTextChangedListener(new TextWatcher() {
        public void afterTextChanged(Editable value) {
            if(changing[0]||carbPerUnit[0]<=0.0f) return;
            changing[0]=true;
            float total=str2float(value.toString());
            amount.setText(ondecimal(total/carbPerUnit[0],10));
            mealTotal.setText(ondecimal(mealBase+total,10));
            changing[0]=false;
            }
        public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
        public void onTextChanged(CharSequence s,int start,int before,int count) {}
        });
    mealTotal.addTextChangedListener(new TextWatcher() {
        public void afterTextChanged(Editable value) {
            if(changing[0]||carbPerUnit[0]<=0.0f) return;
            changing[0]=true;
            float total=str2float(value.toString())-mealBase;
            amount.setText(ondecimal(total/carbPerUnit[0],10));
            itemTotal.setText(ondecimal(total,10));
            changing[0]=false;
            }
        public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
        public void onTextChanged(CharSequence s,int start,int before,int count) {}
        });

    LinearLayout content=phoneContent(act);
    content.addView(ClinicalUi.header(act,act.getString(pos>=0
            ?R.string.meal_modern_edit_item_title:R.string.meal_modern_new_item_title),cancel));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_item_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_ingredient_section)));
    content.addView(ingredient,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams carbParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    carbParams.topMargin=ClinicalUi.dp(act,8);
    content.addView(carbInfo,carbParams);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_quantity_section)));
    content.addView(phoneFieldCard(act,act.getString(R.string.quantity),amount));
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_computed_section)));
    content.addView(ClinicalUi.card(act,
            ClinicalUi.fieldRow(act,act.getString(R.string.meal_modern_item_total),itemTotal),
            ClinicalUi.fieldRow(act,act.getString(R.string.meal_modern_meal_total),mealTotal)));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,12);
    content.addView(error,errorParams);
    LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    saveParams.topMargin=ClinicalUi.dp(act,18);
    content.addView(save,saveParams);
    if(pos>=0) {
        LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        deleteParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(delete,deleteParams);
        }
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    Layout root=phoneRoot(act,screen);
    showPhoneRoot(act,root);
    Runnable close=()->{
        help.hidekeyboard(act);
        removeContentView(root);
        give.accept(-1);
        act.hideSystemUI();
        };
    setonback(close);
    cancel.setOnClickListener(view->doonback());
    ingredient.setOnClickListener(view->{
        help.hidekeyboard(act);
        phoneSelectIngredient(act,numb,index->{
            if(index<0) {
                if(ingredientIndex[0]>=0&&index==-ingredientIndex[0]-1) {
                    ingredientIndex[0]=-1;
                    ingredient.setText(R.string.meal_modern_choose_ingredient);
                    carbPerUnit[0]=0.0f;
                    carbInfo.setText(R.string.meal_modern_no_ingredient);
                    }
                return;
                }
            ingredientIndex[0]=index;
            String name=Natives.ingredientName(index);
            ingredient.setText(name);
            String unit=Natives.ingredientUnitName(index);
            float carb=Natives.ingredientCarb(index);
            carbPerUnit[0]=carb;
            carbInfo.setText(act.getString(R.string.meal_modern_carb_value,carb,unit));
            float total=str2float(amount.getText().toString())*carb;
            itemTotal.setText(ondecimal(total,10));
            setPhoneError(error,null);
            });
        });
    save.setOnClickListener(view->{
        if(ingredientIndex[0]<0) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_ingredient));
            return;
            }
        if(!hasText(amount)) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_quantity));
            return;
            }
        float value=str2float(amount.getText().toString());
        if(Float.isNaN(value)||Float.isInfinite(value)||value<=0.0f) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_quantity));
            return;
            }
        int newMealPtr=Natives.changemealitem(mealptr,pos,ingredientIndex[0],value);
        help.hidekeyboard(act);
        removeContentView(root);
        give.accept(newMealPtr);
        act.poponback();
        act.hideSystemUI();
        });
    delete.setOnClickListener(view->{
        int newMealPtr=Natives.deletefrommeal(mealptr,pos);
        help.hidekeyboard(act);
        removeContentView(root);
        give.accept(newMealPtr);
        act.poponback();
        act.hideSystemUI();
        });
    amount.requestFocus();
    }

private static void phoneSearchIngredients(MainActivity act,View source,
        IngredientViewAdapter adapter) {
    EditText query=new EditText(act);
    query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    query.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_SEARCH);
    query.setHint(R.string.meal_modern_search_hint);
    stylePhoneInput(act,query);
    Button cancel=phoneHeaderButton(act,R.string.cancel);
    Button apply=ClinicalUi.button(act,act.getString(R.string.search),
            ClinicalUi.ButtonRole.PRIMARY);
    TextView error=phoneError(act);
    LinearLayout content=phoneContent(act);
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_search_title),cancel));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_search_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_search_section)));
    content.addView(query,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,12);
    content.addView(error,errorParams);
    LinearLayout.LayoutParams applyParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    applyParams.topMargin=ClinicalUi.dp(act,18);
    content.addView(apply,applyParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    Layout root=phoneRoot(act,screen);
    showPhoneRoot(act,root);
    Runnable close=()->{
        help.hidekeyboard(act);
        removeContentView(root);
        source.setVisibility(VISIBLE);
        };
    setonback(close);
    cancel.setOnClickListener(view->doonback());
    Runnable search=()->{
        String text=query.getText().toString();
        if(text.trim().isEmpty()) {
            adapter.setResults(null);
            doonback();
            return;
            }
        int[] result=Natives.searchIngredient(text);
        if(result==null) {
            setPhoneError(error,act.getString(R.string.meal_modern_search_error));
            return;
            }
        adapter.setResults(result);
        doonback();
        };
    apply.setOnClickListener(view->search.run());
    query.setOnEditorActionListener((view,action,event)->{
        if(action==EditorInfo.IME_ACTION_SEARCH||
                (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
            search.run();
            return true;
            }
        return false;
        });
    query.requestFocus();
    }

private static void phoneSelectIngredient(MainActivity act,NumberView numb,
        IntConsumer setIndex) {
    help.hidekeyboard(act);
    Button close=phoneHeaderButton(act,R.string.closename);
    Button add=ClinicalUi.button(act,act.getString(R.string.meal_modern_define_ingredient),
            ClinicalUi.ButtonRole.PRIMARY);
    Button editMode=ClinicalUi.button(act,act.getString(R.string.meal_modern_edit_mode),
            ClinicalUi.ButtonRole.SECONDARY);
    EditText query=new EditText(act);
    query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    query.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_SEARCH);
    query.setHint(R.string.meal_modern_search_hint);
    stylePhoneInput(act,query);
    Button search=ClinicalUi.button(act,act.getString(R.string.search),
            ClinicalUi.ButtonRole.SECONDARY);
    TextView error=phoneError(act);
    RecyclerView list=new RecyclerView(act);
    list.setLayoutManager(new LinearLayoutManager(act));
    list.setClipToPadding(false);
    list.setPadding(0,ClinicalUi.dp(act,8),0,ClinicalUi.dp(act,8));

    LinearLayout searchRow=new LinearLayout(act);
    searchRow.setOrientation(LinearLayout.HORIZONTAL);
    searchRow.addView(query,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT);
    searchParams.setMarginStart(ClinicalUi.dp(act,8));
    searchRow.addView(search,searchParams);

    LinearLayout screen=new LinearLayout(act);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(ClinicalUi.window(act));
    screen.setPadding(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,16));
    screen.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_ingredients_title),close));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_ingredients_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    screen.addView(intro);
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_search_section)));
    screen.addView(searchRow,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(error,errorParams);
    LinearLayout.LayoutParams editParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    editParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(editMode,editParams);
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_available_section)));
    screen.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
    screen.addView(add,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    Layout root=phoneRoot(act,screen);
    int backDepth=MainActivity.onbacknr();
    Consptr selected=new Consptr();
    IngredientViewAdapter adapter=new IngredientViewAdapter(selected);
    list.setAdapter(adapter);
    boolean[] editing={false};
    IntConsumer choose=index->{
        while(MainActivity.onbacknr()>backDepth) doonback();
        setIndex.accept(index);
        };
    selected.cons=index->{
        if(editing[0]) {
            editing[0]=false;
            editMode.setText(R.string.meal_modern_edit_mode);
            phoneDefineIngredient(act,adapter,list,index,setIndex,root);
            }
        else {
            choose.accept(index);
            }
        };
    Runnable runSearch=()->{
        help.hidekeyboard(act);
        String text=query.getText().toString();
        if(text.trim().isEmpty()) {
            adapter.setResults(null);
            setPhoneError(error,null);
            return;
            }
        int[] result=Natives.searchIngredient(text);
        if(result==null) {
            setPhoneError(error,act.getString(R.string.meal_modern_search_error));
            return;
            }
        adapter.setResults(result);
        setPhoneError(error,null);
        };
    search.setOnClickListener(view->runSearch.run());
    query.setOnEditorActionListener((view,action,event)->{
        if(action==EditorInfo.IME_ACTION_SEARCH||
                (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
            runSearch.run();
            return true;
            }
        return false;
        });
    editMode.setOnClickListener(view->{
        editing[0]=!editing[0];
        editMode.setText(editing[0]?R.string.meal_modern_done_editing:
                R.string.meal_modern_edit_mode);
        });
    add.setOnClickListener(view->phoneDefineIngredient(
            act,adapter,list,-1,setIndex,root));
    close.setOnClickListener(view->doonback());
    setonback(()->{
        help.hidekeyboard(act);
        removeContentView(root);
        act.hideSystemUI();
        });
    showPhoneRoot(act,root);
    }

private static void phoneDefineIngredient(MainActivity act,
        IngredientViewAdapter adapter,RecyclerView list,int pos,
        IntConsumer setIndex,View parent) {
    EnableControls(parent,false);
    list.suppressLayout(true);
    act.showSystemUI();
    act.showui=true;
    EditText name=new EditText(act);
    name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    name.setImeOptions(editoptions);
    name.setHint(R.string.meal_modern_name_hint);
    stylePhoneInput(act,name);
    EditText unit=new EditText(act);
    unit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    unit.setImeOptions(editoptions);
    unit.setHint(R.string.meal_modern_unit_hint);
    stylePhoneInput(act,unit);
    EditText carb=new EditText(act);
    carb.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    carb.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_DONE);
    carb.setHint(R.string.meal_modern_carb_hint);
    stylePhoneInput(act,carb);
    Spinner units=new Spinner(act);
    units.setMinimumHeight(ClinicalUi.dp(act,52));
    units.setPaddingRelative(ClinicalUi.dp(act,12),0,ClinicalUi.dp(act,12),0);
    units.setBackground(ClinicalUi.surface(act,false,true));
    avoidSpinnerDropdownFocus(units);
    Button cancel=phoneHeaderButton(act,R.string.cancel);
    Button save=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    Button delete=ClinicalUi.button(act,act.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    LinearLayout database=ClinicalUi.actionRow(act,
            act.getString(R.string.meal_modern_food_database_title),
            act.getString(R.string.meal_modern_database_hint));
    TextView error=phoneError(act);

    int selectedUnit=0;
    if(pos>=0) {
        name.setText(Natives.ingredientName(pos));
        carb.setText(Float.toString(Natives.ingredientCarb(pos)));
        unit.setText(Natives.ingredientUnitName(pos));
        selectedUnit=Natives.ingredientUnit(pos)+1;
        if(!Natives.ingredientdeleteable(pos)) delete.setVisibility(GONE);
        }
    else {
        delete.setVisibility(GONE);
        }
    ArrayList<String> unitNames=Natives.getunits();
    if(unitNames!=null&&!unitNames.isEmpty()) {
        LabelAdapter<String> unitAdapter=new LabelAdapter<>(act,unitNames,0);
        units.setAdapter(unitAdapter);
        units.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,
                    int position,long id) {
                unit.setText(unitNames.get(position));
                }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        units.setSelection(Math.min(selectedUnit,unitNames.size()-1));
        }
    else {
        units.setVisibility(GONE);
        }

    LinearLayout content=phoneContent(act);
    content.addView(ClinicalUi.header(act,act.getString(pos>=0
            ?R.string.meal_modern_edit_ingredient_title:
            R.string.meal_modern_new_ingredient_title),cancel));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_ingredient_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_details_section)));
    content.addView(ClinicalUi.card(act,
            ClinicalUi.fieldRow(act,act.getString(R.string.name),name),
            ClinicalUi.fieldRow(act,act.getString(R.string.unit),unit),
            units,
            ClinicalUi.fieldRow(act,act.getString(R.string.carbperunit),carb)));
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_reference_section)));
    content.addView(ClinicalUi.card(act,database));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,12);
    content.addView(error,errorParams);
    LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    saveParams.topMargin=ClinicalUi.dp(act,18);
    content.addView(save,saveParams);
    if(delete.getVisibility()!=GONE) {
        LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        deleteParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(delete,deleteParams);
        }
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    Layout root=phoneRoot(act,screen);
    showPhoneRoot(act,root);
    Runnable finish=()->{
        help.hidekeyboard(act);
        removeContentView(root);
        list.suppressLayout(false);
        EnableControls(parent,true);
        act.showui=false;
        act.hideSystemUI();
        };
    setonback(finish);
    cancel.setOnClickListener(view->doonback());
    database.setOnClickListener(view->{
        help.hidekeyboard(act);
        final Layout[] databaseScreen={null};
        databaseScreen[0]=phoneFoodDatabase(act,(foodName,value,foodUnit)->{
            removeContentView(databaseScreen[0]);
            name.setText(foodName);
            unit.setText(foodUnit);
            carb.setText(Float.toString(value));
            });
        });
    save.setOnClickListener(view->{
        String ingredientName=name.getText().toString().trim();
        String unitName=unit.getText().toString().trim();
        if(ingredientName.isEmpty()) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_name));
            return;
            }
        if(unitName.isEmpty()) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_unit));
            return;
            }
        if(!hasText(carb)) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_carb));
            return;
            }
        float value=edit2float(carb);
        if(Float.isNaN(value)||Float.isInfinite(value)||value<0.0f) {
            setPhoneError(error,act.getString(R.string.meal_modern_error_carb));
            return;
            }
        Natives.saveingredient(pos,ingredientName,unitName,value);
        adapter.notifyDataSetChanged();
        finish.run();
        act.poponback();
        if(pos<0&&adapter.getItemCount()>0)
            list.scrollToPosition(adapter.getItemCount()-1);
        });
    delete.setOnClickListener(view->{
        if(pos>=0) {
            Natives.deleteingredient(pos);
            setIndex.accept(-pos-1);
            }
        adapter.notifyDataSetChanged();
        finish.run();
        act.poponback();
        });
    name.requestFocus();
    }

private static Layout phoneFoodDatabase(MainActivity act,
        TriConsumer<String,Float,String> give) {
    act.lightBars(false);
    long[] hitPtr={0L};
    TriConsumer<String,Float,String> result=(name,value,unit)->{
        if(hitPtr[0]!=0L) {
            Natives.freefoodptr(hitPtr[0]);
            hitPtr[0]=0L;
            }
        give.accept(name,value,unit);
        };
    MealDatabaseViewAdapter adapter=new MealDatabaseViewAdapter(hitPtr,result);
    RecyclerView list=new RecyclerView(act);
    list.setLayoutManager(new LinearLayoutManager(act));
    list.setAdapter(adapter);
    list.setClipToPadding(false);
    list.setPadding(0,ClinicalUi.dp(act,8),0,ClinicalUi.dp(act,8));
    EditText query=new EditText(act);
    query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    query.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_SEARCH);
    query.setHint(R.string.meal_modern_food_search_hint);
    stylePhoneInput(act,query);
    Button search=ClinicalUi.button(act,act.getString(R.string.search),
            ClinicalUi.ButtonRole.SECONDARY);
    Button close=phoneHeaderButton(act,R.string.closename);
    Button helpButton=ClinicalUi.button(act,act.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    TextView error=phoneError(act);
    LinearLayout searchRow=new LinearLayout(act);
    searchRow.setOrientation(LinearLayout.HORIZONTAL);
    searchRow.addView(query,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    LinearLayout.LayoutParams searchButtonParams=new LinearLayout.LayoutParams(
            WRAP_CONTENT,WRAP_CONTENT);
    searchButtonParams.setMarginStart(ClinicalUi.dp(act,8));
    searchRow.addView(search,searchButtonParams);

    LinearLayout screen=new LinearLayout(act);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(ClinicalUi.window(act));
    screen.setPadding(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,16));
    screen.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_food_database_title),close));
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_food_database_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    screen.addView(intro);
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_search_section)));
    screen.addView(searchRow,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    errorParams.topMargin=ClinicalUi.dp(act,8);
    screen.addView(error,errorParams);
    screen.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_food_results_section)));
    screen.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
    screen.addView(helpButton,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    Layout root=phoneRoot(act,screen);
    Runnable runSearch=()->{
        help.hidekeyboard(act);
        if(hitPtr[0]!=0L) {
            Natives.freefoodptr(hitPtr[0]);
            hitPtr[0]=0L;
            }
        hitPtr[0]=Natives.foodsearch(query.getText().toString());
        adapter.notifyDataSetChanged();
        setPhoneError(error,null);
        };
    search.setOnClickListener(view->runSearch.run());
    query.setOnEditorActionListener((view,action,event)->{
        if(action==EditorInfo.IME_ACTION_SEARCH||
                (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
            runSearch.run();
            return true;
            }
        return false;
        });
    helpButton.setOnClickListener(view->help.help(R.string.nutrients,act));
    close.setOnClickListener(view->doonback());
    setonback(()->{
        help.hidekeyboard(act);
        removeContentView(root);
        if(hitPtr[0]!=0L) {
            Natives.freefoodptr(hitPtr[0]);
            hitPtr[0]=0L;
            }
        act.lightBars(!Natives.getInvertColors());
        });
    showPhoneRoot(act,root);
    int count=Natives.foodnr();
    if(count>0) list.scrollToPosition(new Random().nextInt(count));
    return root;
    }

private static LinearLayout phoneMetricRow(MainActivity act,CharSequence name,
        CharSequence value) {
    TextView metricName=new TextView(act);
    metricName.setText(name);
    metricName.setTextColor(ClinicalUi.primaryText(act));
    metricName.setTextSize(TypedValue.COMPLEX_UNIT_SP,15.0f);
    metricName.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    TextView metricValue=new TextView(act);
    metricValue.setText(value);
    metricValue.setTextColor(ClinicalUi.secondaryText(act));
    metricValue.setTextSize(TypedValue.COMPLEX_UNIT_SP,15.0f);
    metricValue.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    LinearLayout row=new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ClinicalUi.dp(act,58));
    row.setPaddingRelative(ClinicalUi.dp(act,16),ClinicalUi.dp(act,8),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,8));
    row.addView(metricName,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    row.addView(metricValue,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    return row;
    }

private static void phoneShowNutrients(MainActivity act,int id,boolean showZero,
        TriConsumer<String,Float,String> give) {
    help.hidekeyboard(act);
    String foodName=Natives.idfoodlabel(id);
    int[] values=Natives.getcomponents(id);
    ArrayList<View> rows=new ArrayList<>();
    for(int index=0;index<values.length;index++) {
        int raw=values[index];
        if(!((showZero&&raw!=-1)||raw>0)) continue;
        String display;
        if(raw==-3) display=act.getString(R.string.trace);
        else if(raw==-2) display=act.getString(R.string.unknown);
        else {
            float number=raw/1000.0f;
            display=number<0.1f?Float.toString(number):ondecimal(number,10);
            if(raw>=0&&compunits[index]!=null&&!compunits[index].isEmpty())
                display=display+" "+compunits[index];
            }
        rows.add(phoneMetricRow(act,compnames[index],display));
        }
    Button close=phoneHeaderButton(act,R.string.closename);
    Button use=ClinicalUi.button(act,act.getString(R.string.meal_modern_use_food),
            ClinicalUi.ButtonRole.PRIMARY);
    CheckDirectionBox zero=new CheckDirectionBox(act);
    zero.setText(R.string.showzero);
    zero.setChecked(showZero);
    LinearLayout content=phoneContent(act);
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.meal_modern_nutrients_title),close));
    TextView title=ClinicalUi.body(act,foodName);
    title.setTextColor(ClinicalUi.primaryText(act));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP,18.0f);
    title.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),0);
    content.addView(title);
    TextView intro=ClinicalUi.body(act,
            act.getString(R.string.meal_modern_nutrients_intro));
    intro.setPadding(ClinicalUi.dp(act,4),ClinicalUi.dp(act,6),
            ClinicalUi.dp(act,4),0);
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_per_100g)));
    content.addView(ClinicalUi.card(act,rows.toArray(new View[0])));
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.meal_modern_options_section)));
    content.addView(ClinicalUi.card(act,ClinicalUi.toggleRow(act,zero,
            act.getString(R.string.meal_modern_show_zero_hint))));
    LinearLayout.LayoutParams useParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    useParams.topMargin=ClinicalUi.dp(act,18);
    content.addView(use,useParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT),false);
    setonback(()->removeContentView(screen));
    close.setOnClickListener(view->doonback());
    zero.setOnCheckedChangeListener((button,checked)->{
        if(checked==showZero) return;
        doonback();
        phoneShowNutrients(act,id,checked,give);
        });
    use.setOnClickListener(view->{
        removeContentView(screen);
        act.poponback();
        act.poponback();
        give.accept(foodName,values[0]/100000.0f,compunits[0]);
        });
    }
static public class MealItemViewAdapter extends RecyclerView.Adapter<MealItemViewHolder> {
    Consptr ingrindex;
    final int[] mealptrar;
    MealItemViewAdapter(final int[] mealptrar,Consptr ingrindex) {
       this.mealptrar=mealptrar;
       this.ingrindex=ingrindex;
        }

    @NonNull
    @Override
    public MealItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Button view=clinicalListButton(parent);
        return new MealItemViewHolder(view,ingrindex);
        }

    @Override
    public void onBindViewHolder(final MealItemViewHolder holder, int pos) {
        TextView text=(TextView)holder.itemView;
        float amount=Natives.getitemamount(mealptrar[0],pos);
        String ingre= Natives.getitemingredientname(mealptrar[0],pos);
        if(ingre==null|| Float.isNaN(amount))
            text.setText(R.string.error);
        else {
            StringBuilder build=new StringBuilder();
            if(amount<10)
                build.append("    ");
            else {
                if(amount<100)
                    build.append("  ");
                }
            build.append(Math.round(amount));
            build.append("   ");
            build.append(ingre);
            text.setText(build.toString());
            }
        }
        @Override
        public int getItemCount() {
                return Natives.getmealitemnr(mealptrar[0]);
            }

}
static void askround(MainActivity act,Runnable runner ,View parent) {
    if(!isWearable) {
        phoneAskRound(act,runner,parent);
        return;
        }
    EnableControls(parent,false);
    TextView label=getlabel(act,R.string.roundto);
    EditText edit=new EditText(act);edit.setText(Float.toString(Natives.getroundto()));
    edit.setInputType(InputType.TYPE_CLASS_NUMBER |InputType.TYPE_NUMBER_FLAG_DECIMAL);//| InputType.IME_FLAG_NO_FULLSCREEN);
    edit.setImeOptions( EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN| EditorInfo.IME_ACTION_DONE);
    edit.setMinEms(4);
    Button Save=getbutton(act,R.string.save);
    Button Cancel=getbutton(act,R.string.cancel);
    Layout lay=new Layout(act,(l,w,h)->{
//        int height=GlucoseCurve.getheight();
/*
        int width=GlucoseCurve.getwidth();
        l.setY(MainActivity.systembarTop);
        if(width>w)
            l.setX((width-w)/2);
        else
            l.setX(0);
*/
        return new int[]{w,h};},new View[]{label,edit}    ,new View[]{Cancel,Save});
       int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0);
       label.setPadding(pad,0,pad,0);
       lay.setPadding(pad,0,pad,0);
        lay.setBackgroundColor(Applic.backgroundcolor);

    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
    params.topMargin=MainActivity.systembarTop;
    act.addMyContentView(lay, params);
    setonback(() -> {
        removeContentView(lay);
        help.hidekeyboard(act);
        EnableControls(parent,true);
        act.hideSystemUI();
        });
    Cancel.setOnClickListener(v-> {
        doonback();
        });
    Save.setOnClickListener(v-> {
        doonback();
        float round=edit2float(edit);
        Natives.setroundto(round);
        runner.run();
        });
    }

static Layout menuview(final NumberView numb, MainActivity act, int mealptr, ObjIntConsumer<Float> give,Runnable endrun) {
    if(!isWearable)
        return phoneMealConstructor(numb,act,mealptr,give,endrun);
    RecyclerView recycle = new RecyclerView(act);
    LinearLayoutManager lin = new LinearLayoutManager(act);
    recycle.setLayoutManager(lin);

    Button add=getbutton(act,R.string.additem);
    Button Help=getbutton(act,R.string.helpname);
        Help.setOnClickListener(v-> help.helplight(tk.glucodata.R.string.mealhelp,act));
    Button roundlabel=getbutton(act,act.getString(R.string.round)+Natives.getroundto());

    recycle.setLayoutParams(new ViewGroup.LayoutParams( MATCH_PARENT , MATCH_PARENT));
    Button close=getbutton(act,R.string.closename);
    Button repeat=getbutton(act,R.string.repeat);
    float     carb=Natives.carbinmeal(mealptr);
    if(carb==0.0f)
        repeat.setVisibility(INVISIBLE);
    give.accept(carb,mealptr);
    float[] carbar={carb};
    int[] mealptrar={mealptr};
        int width=GlucoseCurve.getwidth();
        int height=GlucoseCurve.getheight();

    Layout lay=new Layout(act,(l,w,h)-> {
    /*
        if(!smallScreen&&width>w) {
            if(numb.noroom)
                l.setX(width-w);
            else {
                int half= (width-MainActivity.systembarRight)/2;
                int bij=(half-w)/4;
                l.setX(half+bij );
                }
               }
*/
        return new int[]{w,h};
        },new View[]{roundlabel,repeat},new View[]{recycle},new View[] {add,Help,close});
    //    lay.setY(MainActivity.systembarTop);
    roundlabel.setOnClickListener(v-> 
        askround(act,()->{
        give.accept(carbar[0],mealptrar[0]);
        roundlabel.setText(act.getString(R.string.round)+Natives.getroundto());
        },lay));
        lay.setBackgroundColor(Applic.backgroundcolor);
    lay.setMinimumWidth((width-MainActivity.systembarRight)/2);
    //var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP|Gravity.RIGHT);
//    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, height-MainActivity.systembarTop-MainActivity.systembarBottom, Gravity.RIGHT);
    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, MATCH_PARENT, Gravity.RIGHT);
    params.rightMargin=MainActivity.systembarRight;
    params.topMargin=MainActivity.systembarTop*3/4;
    act.addMyContentView(lay, params);
//    act.addMyContentView(lay, smallScreen?new ViewGroup.LayoutParams(  MATCH_PARENT, (height-MainActivity.systembarTop)):new ViewGroup.LayoutParams(WRAP_CONTENT, (height-MainActivity.systembarTop-MainActivity.systembarBottom)));
    repeat.setOnClickListener(v->{
         removeContentView(lay);
         act.hideSystemUI();
         if(mealptrar[0]!=0) {
           if(numb.currentnum!=0&&numb.currentnum!= numio.newhit) {
                {if(doLog) {Log.i(LOG_ID,"repeat");};};
            Natives.hitsetmealptr(numb.currentnum,mealptrar[0]);
            }    
            int ptrcp=Natives.cpmeal(mealptrar[0]);
            act.poponback();
            numb.addnumberwithmenu(act,ptrcp);
        }
    });
    close.setOnClickListener(v-> doonback());
    setonback(() -> {
        lay.setVisibility(GONE);
        removeContentView(lay);
        act.hideSystemUI();
//        give.accept(mealptrar[0],carbar[0]);
        endrun.run();
        });
    Consptr consar=new Consptr();
    MealItemViewAdapter foodadapt = new MealItemViewAdapter(mealptrar,consar); //USE
    IntConsumer onsave= newmealptr-> {
        if(newmealptr>=0) {
            carbar[0]=Natives.carbinmeal(newmealptr);
            mealptrar[0]=newmealptr;
            give.accept(carbar[0],newmealptr);
            foodadapt.notifyDataSetChanged();
            }
        lay.setVisibility(VISIBLE);
        };
    IntConsumer hiercons=i-> {
        lay.setVisibility(GONE);
        numshowkeyboard(numb,act);
        menuitem(act,numb,mealptrar[0],i,onsave,carbar[0]);
        };
    consar.cons=hiercons;
    recycle.setAdapter(foodadapt);

    add.setOnClickListener(v-> {
        lay.setVisibility(GONE);
        numshowkeyboard(numb,act);
        menuitem(act,numb,mealptrar[0],-1,onsave,carbar[0]);
        });
    
    return lay;
    }

static void  numhidekeyboard(NumberView numb,MainActivity act) {
    if(!smallScreen)
            numb.hidekeyboard();
    else
        help.hidekeyboard(act);
    }
static void  numshowkeyboard(NumberView numb,MainActivity act) {
    if(!smallScreen)
        numb.showkeyboard(act);
    else
        help.showkeyboard(act,numb.valueedit);
    }
public static String ondecimal(final float fl,final float nr) {
    return Float.toString(Math.round(fl*nr)/nr);
    }
static void menuitem(MainActivity act, NumberView numb, int mealptr, int pos, IntConsumer give,float carbmealin) {
    if(!isWearable) {
        phoneEditMealItem(act,numb,mealptr,pos,give,carbmealin);
        return;
        }
 
    TextView ingrlabel=getlabel(act,R.string.ingredient);
    Button Ingredient=getbutton(act,R.string.select);
    Button Save=getbutton(act,R.string.save);
    Button Cancel=getbutton(act,R.string.cancel);
    Button Delete=getbutton(act,R.string.delete);
    TextView carblabel=getlabel(act,R.string.carbohydrate);
    TextView carbos=new TextView(act);
    carbos.setPadding(0,(int)(tk.glucodata.GlucoseCurve.metrics.density*12.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*9.0));

    TextView totallabel=getlabel(act,R.string.total);
    editfocus focushere=new editfocus();
    EditText total=smallScreen?geteditwearos(act):geteditview(act,focushere);
    total.setMinEms(5);
    TextView mealtotallabel=getlabel(act,R.string.mealtotal);
    EditText mealtotal=smallScreen?geteditwearos(act):geteditview(act,focushere);
    mealtotal.setMinEms(5);
    TextView amountlabel=getlabel(act,R.string.quantity);
    EditText amount    = smallScreen?geteditwearos(act):geteditview(act,focushere);


    amount.requestFocus();

    editfocus.setedittext(amount);
    amount.setMinEms(5);
    float[] cargs={0.0f};
    int [] ingred={-1};
    if(pos>=0) {
         float am=Natives.getitemamount( mealptr, pos);
         amount.setText(""+Math.round(am*10.0f)/10.0f);
        int ingr=Natives.getitemingredient(mealptr, pos);
         ingred[0]=ingr;
        Ingredient.setText(Natives.ingredientName(ingr));
        String unit=Natives.ingredientUnitName(ingr);
        float carb=Natives.ingredientCarb(ingr);
        carbos.setText(carb+act.getString(R.string.per)+unit);
        cargs[0]=carb;
         float tot=am*carb;
         total.setText(ondecimal(tot,10));
         mealtotal.setText(ondecimal(carbmealin,10));
         carbmealin-=tot;

        }
    else
        Delete.setVisibility(INVISIBLE);
    final float carbmeal=carbmealin;
    boolean[] changing={false};
    amount.addTextChangedListener( new TextWatcher() {
           public void afterTextChanged(Editable ed) {
               if(!changing[0]) {
                changing[0]=true;
                 float am=str2float( ed.toString());
                 float tot=am*cargs[0];
                total.setText(ondecimal(tot,10));
                mealtotal.setText(ondecimal(carbmeal+tot,10));
                changing[0]=false;
                }
           }
           public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
           public void onTextChanged(CharSequence s, int start, int before, int count) { }
          });
    total.addTextChangedListener( new TextWatcher() {
           public void afterTextChanged(Editable ed) {
               if(!changing[0]) {
                if(cargs[0]>0.0f) {
                    changing[0]=true;
                     float tot=str2float( ed.toString());
                     float am=tot/cargs[0];
                    amount.setText(ondecimal(am,10));
                    mealtotal.setText(ondecimal(carbmeal+tot,10));
                    changing[0]=false;
                    }
                }
               }
           public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
           public void onTextChanged(CharSequence s, int start, int before, int count) { }
          });
    mealtotal.addTextChangedListener( new TextWatcher() {
           public void afterTextChanged(Editable ed) {
               if(!changing[0]) {
                if(cargs[0]>0.0f) {
                    changing[0]=true;
                     float mealtot=str2float( ed.toString());
                     float tot=mealtot-carbmeal;
                     float am=tot/cargs[0];
                    amount.setText(ondecimal(am,10));
                    total.setText(ondecimal(tot,10));
                    changing[0]=false;
                    }
                }
               }
           public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
           public void onTextChanged(CharSequence s, int start, int before, int count) { }
          });
    Layout lay=new Layout(act,(l,w,h)-> {
        int width=GlucoseCurve.getwidth();
        int hei=GlucoseCurve.getheight();
        if(smallScreen) {
            l.setX((width-w)/2);
            }
        else {
            var whalf=width/2;
            if(whalf>w)
                l.setX(whalf-w);
            else
                l.setX(0);
            }
        if(!smallScreen&&hei>h)
            l.setY((hei-h)*.5f);
        else
            l.setY(MainActivity.systembarTop*.75f);
        return new int[]{w,h};
        },new View[]{amountlabel,amount},new View[]{ingrlabel,Ingredient},new View[]{carblabel,carbos},new View[]{totallabel,total}, new View[]{mealtotallabel,mealtotal},
            new View[] {Delete,Cancel,Save});
       int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0);
       lay.setPadding(pad,0,pad,0);
    act.addMyContentView(lay, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
      lay.post(lay::requestLayout);

        lay.setBackgroundColor(Applic.backgroundcolor);

    Delete.setOnClickListener(v-> {
        int newmealptr=Natives.deletefrommeal(mealptr,pos);
        give.accept(newmealptr);
        removeContentView(lay);
            numhidekeyboard(numb,act);
        act.hideSystemUI();
        act.poponback();
        });
    Cancel.setOnClickListener(v-> doonback());
     setonback(() -> {
        lay.setVisibility(GONE);
        give.accept(-1);
        removeContentView(lay);
            numhidekeyboard(numb,act);
        act.hideSystemUI();
         });
    Ingredient.setOnClickListener(v-> {
        
        selectingredient(act,numb, i->{
    if(i<0) {
        if(ingred[0]>=0&&i==(-ingred[0]-1)) {
            ingred[0]=-1;
            Ingredient.setText(R.string.select);
            }
        }
    else {
        ingred[0] = i;
        Ingredient.setText(Natives.ingredientName(i));
        String unit = Natives.ingredientUnitName(i);
        float carb = Natives.ingredientCarb(i);
        carbos.setText(carb + act.getString(R.string.per) + unit);
        cargs[0] = carb;
        float am = str2float(amount.getText().toString());
        float tot = am * carb;
        total.setText(ondecimal(tot, 10));
    }
        editfocus.getedittext().requestFocus();
        });});
    Save.setOnClickListener(v-> {
        if(ingred[0]==-1) {
            Applic.argToaster(act, R.string.specifyingredient, Toast.LENGTH_SHORT);
            return;
            }
         float am=str2float( amount.getText().toString());
         if(am==0.0f) {
            Applic.argToaster(act, R.string.specifyhowmuch, Toast.LENGTH_SHORT);
            return;
             }    
        int newmealptr=Natives.changemealitem(mealptr,pos,ingred[0],am);
        lay.setVisibility(GONE);
        removeContentView(lay);
            numhidekeyboard(numb,act);
        act.hideSystemUI();
        give.accept(newmealptr);
        act.poponback();
        });
    }

static public class IngredientViewAdapter extends RecyclerView.Adapter<IngredientViewHolder> {
    int[] results=null;
    Consptr ingrindex;
    IngredientViewAdapter(Consptr ingrindex) {
        this.ingrindex=ingrindex;
        }
    void setResults(int[] res) {
        results=res;
        notifyDataSetChanged();
        }
    @NonNull
    @Override
    public IngredientViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Button view=clinicalListButton(parent);
        return new IngredientViewHolder(view,this);

    }
    @Override
    public void onBindViewHolder(final IngredientViewHolder holder, int pos) {
        int showpos=results==null?pos:results[pos];
        TextView text=(TextView)holder.itemView;
        text.setText(Natives.ingredientName(showpos));
        }
        @Override
        public int getItemCount() {
            if(results!=null)
                return results.length;
            return Natives.ingredientNr();
            }

    }
static private void doSearchIngr(MainActivity act,View view,IngredientViewAdapter adapt) {
    if(!isWearable) {
        phoneSearchIngredients(act,view,adapt);
        return;
        }
    act.showSystemUI();
    view.setVisibility(INVISIBLE);
    var ingredient=getlabel(act,R.string.ingredient);
    EditText searchstr= new EditText(act);
    searchstr.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    int searchoptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN| EditorInfo.IME_ACTION_SEARCH;
    searchstr.setImeOptions(searchoptions);
    searchstr.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    int width=GlucoseCurve.getwidth();
    var cancel=getbutton(act,R.string.cancel);
    var search=getbutton(act,R.string.search);
    ingredient.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*5.0),0,0,0);
    var lay=new Layout(act,(l,w,h)-> {
    /*
        l.setY(MainActivity.systembarTop*.75f);
        l.setX(MainActivity.systembarLeft);
        w=width-MainActivity.systembarLeft-MainActivity.systembarRight;
        */
        return new int[]{w,h};
        },new View[]{ingredient,searchstr,search,cancel});
    lay.setBackgroundResource(R.drawable.helpbackground);

    setonback(() -> {
        view.setVisibility(VISIBLE);
        removeContentView(lay);
        act.hideSystemUI();
        hidekeyboard(act);
        });
    cancel.setOnClickListener(v->  doonback());

    //act.addMyContentView(lay, new ViewGroup.LayoutParams(width-MainActivity.systembarLeft-MainActivity.systembarRight ,WRAP_CONTENT));

    var  params = new FrameLayout.LayoutParams(  MATCH_PARENT,WRAP_CONTENT, Gravity.TOP|Gravity.START);
    params.topMargin=(int)(MainActivity.systembarTop*.75f);
    params.setMarginStart(0);
    params.setMarginEnd(MainActivity.systembarEnd);;
   act.addMyContentView(lay, params);
    Runnable searchrun=()-> {
                int[] res=Natives.searchIngredient(searchstr.getText().toString());
                if(res!=null) {
                    adapt.setResults(res);
                    doonback();
                    }
                 else {
                    Applic.argToaster(act, "Regex error, try again", Toast.LENGTH_LONG);
                    }
                    };
    searchstr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                || actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchrun.run();
                return true;
               }
            return false;
            }
        });

    search.setOnClickListener(v->  
             searchrun.run()
            );
    }
static private void selectingredient(MainActivity act,NumberView numb,IntConsumer setindex) {
    if(!isWearable) {
        phoneSelectIngredient(act,numb,setindex);
        return;
        }
    numhidekeyboard(numb,act);
    RecyclerView recycle = new RecyclerView(act);
    LinearLayoutManager lin = new LinearLayoutManager(act);
    recycle.setLayoutManager(lin);
    var add=getbutton(act,R.string.define);
    Button close=getbutton(act,R.string.closename);
    CheckDirectionBox edit=new CheckDirectionBox(act);
    edit.setText(R.string.edit);
    var search=getbutton(act,R.string.search);
    recycle.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, MATCH_PARENT));
    int height=GlucoseCurve.getheight();
    int width=GlucoseCurve.getwidth();
    int viewwidth=(int)(width*.56);
    recycle.setMinimumWidth(viewwidth);
//    int ypos=MainActivity.systembarTop*3/4;
    Layout lay=new Layout(act,(l,w,h)-> {
    /*
      var af=MainActivity.systembarTop*3/4;
        l.setY(af);
       l.setX((width-w)/2);
        return new int[]{w,h-af-MainActivity.systembarBottom};
       */
        return new int[]{w,h};
        },new View[]{recycle},new View[] {add,edit,search,close});

//    lay.setY(ypos);
//    lay.setMinimumWidth(viewwidth);
    final int wasonback=MainActivity.onbacknr();
    IntConsumer hiercons=i-> {
        /*lay.setVisibility(GONE);
        removeContentView(lay);*/
        while( MainActivity.onbacknr()>wasonback) doonback();
        setindex.accept(i);
        };
    Consptr consar=new Consptr(hiercons);
    IngredientViewAdapter foodadapt = new IngredientViewAdapter(consar); //USE
    recycle.setAdapter(foodadapt);
    search.setOnClickListener(v-> doSearchIngr(act,search,foodadapt));

    edit.setOnCheckedChangeListener((buttonView, isChecked)-> {
        if(isChecked) {
            consar.cons=i-> {
                edit.setChecked(false);
                defineingredient(act,foodadapt,recycle,i,setindex,lay);
                };
            }
        else
            consar.cons=hiercons;
        });


    lay.setBackgroundResource(R.drawable.dialogbackground);

//    act.addMyContentView(lay, new ViewGroup.LayoutParams(smallScreen?MATCH_PARENT:WRAP_CONTENT, height));
    var  params =    new FrameLayout.LayoutParams( smallScreen?MATCH_PARENT:WRAP_CONTENT, MATCH_PARENT, Gravity.TOP|Gravity.CENTER_HORIZONTAL); 
    params.topMargin=MainActivity.systembarTop*3/4;
//    params.topMargin=0;
    act.addMyContentView(lay, params);
    lay.invalidate();
    lay.setVisibility(VISIBLE);
    lay.bringToFront();
    close.setOnClickListener(v-> doonback());
    setonback(() -> {
        numshowkeyboard(numb,act);
        lay.setVisibility(GONE);
        removeContentView(lay);
        act.hideSystemUI();
        });
    add.setOnClickListener(v-> defineingredient(act,foodadapt,recycle,-1,setindex,lay));
    
    }
static void    defineingredient(MainActivity act ,IngredientViewAdapter  foodadapt,RecyclerView recycle,int pos, IntConsumer  setindex,View parent) {
    if(!isWearable) {
        phoneDefineIngredient(act,foodadapt,recycle,pos,setindex,parent);
        return;
        }

    EnableControls(parent,false);
    recycle.suppressLayout(true);
    act.showSystemUI();
    act.showui=true;
    TextView.OnEditorActionListener     actlist= new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || actionId == EditorInfo.IME_ACTION_DONE) {
        //        act.hideSystemUI();
                 tk.glucodata.help.hidekeyboard(act);
                 {if(doLog) {Log.i(LOG_ID,"onEditorAction");};};
// hidekeyboard();
                return true;
               }
            return false;
            }};

    TextView namelabel=getlabel(act,R.string.name);
    EditText name=new EditText(act);
        name.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        name.setImeOptions(editoptions);
        name.setMinEms(10);

    TextView unitlabel=getlabel(act,R.string.unit);
    EditText unit=new EditText(act);
        unit.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        unit.setImeOptions(editoptions);
        unit.setMinEms(2);
    TextView carblabel=getlabel(act,R.string.carbperunit);
    EditText carb=new EditText(act);
    carb.setMinEms(5);
    carb.setInputType(InputType.TYPE_CLASS_NUMBER |InputType.TYPE_NUMBER_FLAG_DECIMAL);//| InputType.IME_FLAG_NO_FULLSCREEN);
//    carb.setKeyListener(DigitsKeyListener.getInstance("0123456789^*/+-()."));
    carb.setImeOptions( EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN| EditorInfo.IME_ACTION_DONE);
        name.setOnEditorActionListener(actlist);
        unit.setOnEditorActionListener(actlist);
        carb.setOnEditorActionListener(actlist);
    Button Save=getbutton(act,R.string.save);
    Button Cancel=getbutton(act,R.string.cancel);
    Button Delete=getbutton(act,R.string.delete);
    Button Database=getbutton(act,R.string.database);
    Database.setOnClickListener(v-> {
        final Layout[] fooddat={null};
        fooddat[0]=fooddatabase(act,(n,val,u)-> {
            removeContentView(fooddat[0]);
            name.setText(n);
            unit.setText(u);
            carb.setText(Float.toString(val));
            });

        });
    int usedunit=0;
    if(pos>=0) {
        carb.setText(""+Natives.ingredientCarb(pos));
        usedunit=Natives.ingredientUnit(pos)+1;
        name.setText(Natives.ingredientName(pos));
        if(!Natives.ingredientdeleteable(pos))
             Delete.setVisibility(GONE);
        }
    else {
        Delete.setVisibility(GONE);
         }
    ArrayList<String> unitstr=Natives.getunits();
        Spinner spinner=new Spinner(act);
    if(unitstr!=null&&unitstr.size()>0) {
        final int minheight= GlucoseCurve.dpToPx(48);
        spinner.setMinimumHeight(minheight);
        avoidSpinnerDropdownFocus(spinner);
        LabelAdapter<String> numspinadapt=new LabelAdapter<String>(act,unitstr,0);
        spinner.setAdapter(numspinadapt);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
                unit.setText(unitstr.get(position));        
                }
            @Override
            public  void onNothingSelected (AdapterView<?> parent) {
            } });
    //    spinner.clearAnimation();
        spinner.setSelection(usedunit);
        }
    else
        spinner.setVisibility(GONE);
    Layout inlay=new Layout(act,(l,w,h)-> {
//        int height=GlucoseCurve.getheight();
/*
        int width=GlucoseCurve.getwidth();
        if(width>w)
            l.setX((width-w)/2);
        else
            l.setX(0);
        l.setY(MainActivity.systembarTop);
        */
        return new int[]{w,h};
        },new View[]{namelabel,name,unitlabel,unit,spinner},new View[]{Database,carblabel,carb},new View[] {Cancel,Delete,Save});
    final var lay= new HorizontalScrollView(act);
    lay.addView(inlay, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) );
        lay.setSmoothScrollingEnabled(false);
       lay.setVerticalScrollBarEnabled(false);
        lay.setHorizontalScrollBarEnabled(Applic.horiScrollbar);
        lay.setFillViewport(true);

       int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0);
       lay.setPadding(pad,0,pad,pad/2);
      lay.setBackgroundResource(R.drawable.dialogbackground);
   // act.addMyContentView(lay, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL|Gravity.TOP);
    params.topMargin=MainActivity.systembarTop;
    params.leftMargin=MainActivity.systembarLeft/2;
    params.rightMargin=MainActivity.systembarRight/2;
     act.addMyContentView(lay, params);

    name.requestFocus();
    Save.setOnClickListener(v-> {
        final float flcarb=edit2float(carb);
        Natives.saveingredient(pos,name.getText().toString(), unit.getText().toString(),flcarb);

        foodadapt.notifyDataSetChanged();
        lay.setVisibility(GONE);
        removeContentView(lay);
        recycle.suppressLayout(false);
        EnableControls(parent,true);
        act.showui=false;
        act.hideSystemUI();
        act.poponback();
        if(pos<0)
            recycle.scrollToPosition(foodadapt.getItemCount()-1);
        });
    Runnable endproc= ()-> {
        lay.setVisibility(GONE);
        removeContentView(lay);
        recycle.suppressLayout(false);
        EnableControls(parent,true);
        act.showui=false;
        act.hideSystemUI();
        };
    Cancel.setOnClickListener(v-> {
        endproc.run();
        act.poponback();
        });
    
    setonback(endproc);
    Delete.setOnClickListener(v-> {
        if(pos>=0) {
            Natives.deleteingredient(pos);
            setindex.accept(-pos-1);
            }
        foodadapt.notifyDataSetChanged();
        endproc.run();
        act.poponback();
        });

    }

static public class MealDatabaseViewAdapter extends RecyclerView.Adapter<MealDatabaseViewHolder> {
    long[] hitptr;
    TriConsumer<String,Float,String> give;
    MealDatabaseViewAdapter(long[] hitptr,TriConsumer<String,Float,String> give) {
       this.hitptr=hitptr;
       this.give=give;
        }

    @Override
    public MealDatabaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Button view=clinicalListButton(parent);
        return new MealDatabaseViewHolder(view,hitptr,give);
        }

    @Override
    public void onBindViewHolder(final MealDatabaseViewHolder holder, int pos) {
        TextView text=(TextView)holder.itemView;
        String label=hitptr[0]==0L?Natives.idfoodlabel(pos):Natives.foodlabel(hitptr[0],pos);
        text.setText(label);
        }
        @Override
        public int getItemCount() {
            if(hitptr[0]==0L)
                return Natives.foodnr();;
            return Natives.foodhitnr( hitptr[0]);
             }

}
final static private Random random=new Random();
static Layout  fooddatabase(MainActivity act, TriConsumer<String,Float,String> give) {
    if(!isWearable)
        return phoneFoodDatabase(act,give);
    act.themeLightBars();
    RecyclerView recycle = new RecyclerView(act);
    LinearLayoutManager lin = new LinearLayoutManager(act);
    recycle.setLayoutManager(lin);
    long[] hitptr    ={0L};
    MealDatabaseViewAdapter foodadapt = new MealDatabaseViewAdapter(hitptr,give); //USE
    recycle.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    recycle.setAdapter(foodadapt);
    Button searchbutton=getbutton(act,R.string.search);
    EditText searchstr= new EditText(act);
    searchstr.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    int searchoptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN| EditorInfo.IME_ACTION_SEARCH;
    searchstr.setImeOptions(searchoptions);
    searchstr.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    searchstr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            || actionId == EditorInfo.IME_ACTION_SEARCH) {
            hidekeyboard(act);
            hitptr[0]=Natives.foodsearch(searchstr.getText().toString());
            foodadapt.notifyDataSetChanged();
            return true;
           }
        return false;
        }
    });


    Button Close=getbutton(act,R.string.closename);
    Button Help=getbutton(act,R.string.helpname);
//    act.hideSystemUI();
    int fnr=Natives.foodnr();
    recycle.scrollToPosition(random.nextInt(fnr));
    Layout lay=new Layout(act,(l,w,h)->{
    /*
        int width=GlucoseCurve.getwidth();
//        l.setY(MainActivity.systembarTop);
        if(width>w) {
            l.setX((width-w)/2);
                 }
        else
            l.setX(0);
            */
    //    return new int[]{w,h-MainActivity.systembarTop};
        return new int[]{w,h};

        },new View[]{Help,searchstr,searchbutton,Close,}    ,new View[]{recycle});
     //  int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0);
    //   lay.setPadding(pad,0,pad,0);
      lay.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop/2,MainActivity.systembarRight,MainActivity.systembarBottom);
        lay.setBackgroundColor(Applic.backgroundcolor);
    //act.addMyContentView(lay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
/*
    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
    params.topMargin=MainActivity.systembarTop;
   context.addMyContentView(layout, params);
   */
    act.addMyContentView(lay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    searchstr.requestFocus();
    Help.setOnClickListener(v-> { 
//        act.hideSystemUI();
//        help.help(R.string.searchhelp,act);
        help.help(R.string.nutrients,act);
        });
    Close.setOnClickListener(v-> { 
        doonback();
        });
    setonback(() -> {
        removeContentView(lay);
    //    act.hideSystemUI();
        Natives.freefoodptr(hitptr[0]);
        act.lightBars(!Natives.getInvertColors());

        });
    searchbutton.setOnClickListener(v-> { 
        hidekeyboard(act);
    //    act.hideSystemUI();
//        Natives.freefoodptr(hitptr[0]);
        hitptr[0]=Natives.foodsearch(searchstr.getText().toString());
        foodadapt.notifyDataSetChanged();
        });
    return lay;
    }
static String[] compnames=Natives.getcomponentlabels( );
static String[] compunits=Natives.getcomponentunits( );
static void    shownutrients(MainActivity act,int id,boolean showzero,TriConsumer<String,Float,String> give) {
    if(!isWearable) {
        phoneShowNutrients(act,id,showzero,give);
        return;
        }
    hidekeyboard(act);
//    act.hideSystemUI();
    TextView ingred=new TextView(act);
    final String label= Natives.idfoodlabel(id);
    ingred.setText(label);
//        ingred.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
   if(!isWearable)
           ingred.setTextSize(TypedValue.COMPLEX_UNIT_PX,Applic.largefontsize);
    int[] ingr=Natives.getcomponents(id);
    int rows=2;
    for(int el:ingr) {
        if((showzero&&el!=-1)||el>0)
            rows++;
        }
    GridLayout grid=new GridLayout(act);
      grid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
      grid.setTextDirection(View.TEXT_DIRECTION_LTR);
        int cols=4;
        grid.setColumnCount(cols);
        grid.setRowCount(rows);
    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
    params.columnSpec = GridLayout.spec(0, 4); 
//    ingr.setLayoutParams(params);
    grid.addView(ingred,params);

    GridLayout.LayoutParams para = new GridLayout.LayoutParams();
    para.columnSpec = GridLayout.spec(1, 3); 
    TextView per100=getlabel(act,R.string.compositionof100gram);
    grid.addView(per100,para);
   int pad=(int)(GlucoseCurve.metrics.density*5.0);
        for(int i=0;i<ingr.length;i++) {
        if((showzero&&ingr[i]!=-1)||ingr[i]>0) {
            TextView name = new TextView(act);
            name.setText(compnames[i]);
            name.setPadding(pad,0,pad,0);
            GridLayout.LayoutParams par = new GridLayout.LayoutParams();
            par.columnSpec = GridLayout.spec(0, 2);
            grid.addView(name,par);
//Tr -3
//N  -2
//"" -1
//0.0 0

            String strval;
            switch(ingr[i]) {
                case -3: strval=act.getString(R.string.trace);break;
                case -2: strval=act.getString(R.string.unknown);break;
                default:  {
                    float val=(float)ingr[i]/1000.0f;
                    strval=val<0.1f?Float.toString(val):ondecimal(val,10);
                    };
                }
            TextView value=new TextView(act);
            value.setText(strval);
            grid.addView(value);
            TextView un=new TextView(act);
            if(ingr[i]>=0)
                un.setText(compunits[i]);
            grid.addView(un);
            }
        }
        CheckDirectionBox zero=new CheckDirectionBox(act);
        zero.setText(R.string.showzero);
        zero.setChecked(showzero);
       zero.setPadding(0,0,0,0);
    GridLayout.LayoutParams parzero = new GridLayout.LayoutParams();
 int padzero=(int)    (GlucoseCurve.metrics.density*7.0);
    parzero.setMargins(padzero, 0, padzero, 0);
    ScrollView scroll=new ScrollView(act);
   /* 
    grid.setmeasure((l,w,h)-> {
        int height=GlucoseCurve.getheight();
        int width=GlucoseCurve.getwidth();
      int x=width>w?((width-w)/2):0;
      scroll.setX(x);
        if(!smallScreen&&height>h) {
            scroll.setY((height-h)/2);
         }
        else   {
          var above=MainActivity.systembarTop*3/4;
          scroll.setY(above);
         if((height-above)>h) 
            scroll.setPadding(0,0,0,0);
         else
                  scroll.setPadding(0,0,0,above);
        }

            });
            */
    //      scroll.setPadding(0,0,0,MainActivity.systembarTop*3/4);

    Button Select=getbutton(act,R.string.select);
    grid.addView(Select);
    zero.setOnCheckedChangeListener((buttonView, isChecked)-> {
        doonback();
//        act.hideSystemUI();
        shownutrients(act,id, isChecked,give) ;
        return;
        });
    grid.addView(zero,parzero);
    Select.setOnClickListener(v-> {
        removeContentView(scroll);
//        act.hideSystemUI();
        act.poponback();
        act.poponback();
        give.accept(label,ingr[0]/100000.0f,compunits[0]);
        });
    Button Close=getbutton(act,R.string.closename);
    GridLayout.LayoutParams closeparams = new GridLayout.LayoutParams();
    closeparams.columnSpec = GridLayout.spec(2, 2);
    grid.addView(Close,closeparams);
//    act.addMyContentView(grid,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
//    scroll.setFillViewport(true);
       grid.setPadding(pad,0,0,0);
    scroll.addView(grid);
    scroll.setSmoothScrollingEnabled(false);
        scroll.setVerticalScrollBarEnabled(Applic.scrollbar);



    //act.addMyContentView(scroll,new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

    var  params2 =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
    params2.topMargin=MainActivity.systembarTop;
   act.addMyContentView(scroll, params2);
    scroll.setBackgroundResource(R.drawable.dialogbackground);
    Close.setOnClickListener(v-> doonback());
    setonback(() -> {
        removeContentView(scroll);
//        act.hideSystemUI();
        });

}
/*
static private String getIngredientName(int pos) {
    byte[] bytes=Natives.ingredientNameBytes(pos);
    try {
      return new String(bytes);
      }
      catch(Throwable th) {
         Log.stack(LOG_ID,"getIngredientName("+pos+")",th);
         return "illegal character";
         }
   }
*/
} 
