package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureControlsTest {
    Context context;

    @Before public void setup() {
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
        FeatureControls.preferences(context).edit().clear().commit();
    }

    @Test public void absentPreferenceIsOffForNewInstallsAndUpgrades() {
        assertFalse(FeatureControls.isAddEnabled(context));
        FeatureControls.preferences(context).edit().putString("other_setting","preserved").commit();
        assertFalse(FeatureControls.isAddEnabled(context));
        FeatureControls.setAddEnabled(context,true);
        assertTrue(FeatureControls.isAddEnabled(new ContextThemeWrapper(context,R.style.AppTheme_ClinicalDark)));
        FeatureControls.setAddEnabled(context,false);
        assertFalse(FeatureControls.isAddEnabled(context));
        assertEquals("preserved",FeatureControls.preferences(context).getString("other_setting",null));
    }

    @Test public void rowAndSwitchBothPersistAndRefreshTheRealDashboardImmediately() {
        View root=LayoutInflater.from(context).inflate(R.layout.modern_dashboard_chrome,null,false);
        View add=root.findViewById(R.id.modern_dashboard_add_intake);
        assertEquals(View.GONE,add.getVisibility()); // No first-frame flash from XML.
        FeatureControls.applyToDashboard(root);
        int[] callbacks={0};
        LinearLayout row=FeatureControls.addButtonSetting(context,()-> {
            callbacks[0]++;
            FeatureControls.applyToDashboard(root);
        });
        SwitchCompat toggle=findSwitch(row);
        assertFalse(toggle.isChecked());
        assertEquals(context.getString(R.string.feature_controls_add_button),toggle.getContentDescription());
        assertFalse(add.isEnabled());
        assertFalse(add.isFocusable());
        row.performClick();
        assertTrue(toggle.isChecked());
        assertTrue(FeatureControls.isAddEnabled(context));
        assertEquals(View.VISIBLE,add.getVisibility());
        assertTrue(add.isEnabled());
        assertTrue(add.isFocusable());
        assertTrue(findSwitch(FeatureControls.addButtonSetting(context,()-> {})).isChecked());
        toggle.performClick();
        assertFalse(FeatureControls.isAddEnabled(context));
        assertEquals(View.GONE,add.getVisibility());
        assertEquals(2,callbacks[0]);
        assertEquals(View.VISIBLE,root.findViewById(R.id.modern_dashboard_menu).getVisibility());
        assertEquals(View.VISIBLE,root.findViewById(R.id.modern_dashboard_graph_card).getVisibility());
    }

    @Test public void hiddenButtonLeavesNoDockGapAndMoreRemainsAccessible() {
        for(boolean rail:new boolean[]{false,true}) {
            View root=LayoutInflater.from(context).inflate(R.layout.modern_dashboard_chrome,null,false);
            LinearLayout dock=root.findViewById(R.id.modern_dashboard_actions);
            dock.setOrientation(rail?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
            View add=root.findViewById(R.id.modern_dashboard_add_intake);
            View more=root.findViewById(R.id.modern_dashboard_menu);
            if(rail) for(int i=0;i<dock.getChildCount();i++)
                dock.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(72,68));
            FeatureControls.applyToDashboard(root);
            int width=rail?72:375, height=rail?300:64;
            dock.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            dock.layout(0,0,width,height);
            assertEquals(View.GONE,add.getVisibility());
            assertTrue(more.isEnabled());
            assertTrue(more.getWidth()>=48 && more.getHeight()>=48);
            if(!rail) assertEquals(width,more.getWidth());
        }
    }

    @Test public void settingsRouteAndComposerGuardUseTheSamePreference() throws Exception {
        Path base=Files.exists(Path.of("src/main"))?Path.of("src/main"):Path.of("Common/src/main");
        String settings=new String(Files.readAllBytes(base.resolve("java/tk/glucodata/settings/Settings.java")),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(settings.contains("featureEntry.setOnClickListener(view->featurePage.show(featureEntry))"));
        assertTrue(settings.contains("context,context::refreshFeatureControls"));
        String activity=new String(Files.readAllBytes(base.resolve("java/tk/glucodata/MainActivity.java")),java.nio.charset.StandardCharsets.UTF_8);
        int start=activity.indexOf("void showIntakeComposer()");
        String composer=activity.substring(start,activity.indexOf("intakeComposer.show();",start));
        assertTrue(composer.contains("if(!FeatureControls.isAddEnabled(this)) return;"));
    }

    @Test public void controlRemainsReadableInBothLanguagesLargeTextAndFoldWidths() throws Exception {
        try {
            for(String language:new String[]{"en","ru"}) for(String mode:new String[]{"notnight","night"})
                for(int width:new int[]{375,800,700}) for(float scale:new float[]{1f,1.8f}) {
                    RuntimeEnvironment.setQualifiers(language+"-w"+width+"dp-"+mode+"-mdpi");
                    RuntimeEnvironment.setFontScale(scale);
                    Context themed=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
                    LinearLayout content=ClinicalUi.verticalContent(themed);
                    content.setBackgroundColor(ClinicalUi.window(themed));
                    content.addView(ClinicalUi.header(themed,themed.getString(R.string.feature_controls_title),
                            ClinicalUi.button(themed,themed.getString(R.string.settings_back),ClinicalUi.ButtonRole.SECONDARY)));
                    content.addView(ClinicalUi.body(themed,themed.getString(R.string.feature_controls_intro)));
                    LinearLayout row=FeatureControls.addButtonSetting(themed,()-> {});
                    content.addView(ClinicalUi.card(themed,row));
                    content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    content.layout(0,0,width,content.getMeasuredHeight());
                    assertTrue(findSwitch(row).getHeight()>=48);
                    assertTextFits(content);
                    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,content.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                    content.draw(new android.graphics.Canvas(bitmap));
                    java.io.File output=new java.io.File("build/reports/feature-controls/"+language+"-"+mode+"-"+width+"-"+scale+".png");
                    output.getParentFile().mkdirs();
                    try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)) {
                        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));
                    }
                    bitmap.recycle();
                }
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }

    static SwitchCompat findSwitch(ViewGroup row) {
        for(int i=0;i<row.getChildCount();i++) if(row.getChildAt(i) instanceof SwitchCompat)
            return (SwitchCompat)row.getChildAt(i);
        throw new AssertionError("Missing native switch");
    }

    static void assertTextFits(View view) {
        if(view instanceof TextView && !(view instanceof SwitchCompat)) {
            TextView text=(TextView)view;
            assertTrue(text.getText().toString(),text.getLayout().getHeight()<=text.getHeight());
            assertTrue(text.getWidth()>0);
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                View child=group.getChildAt(i);
                assertTrue(child.getRight()<=group.getWidth());
                assertTextFits(child);
            }
        }
    }
}
