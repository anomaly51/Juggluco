package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalConnectionVisualTest {
    @Test public void realStepsWrapOnFoldLandscapeLargeTextAndBothSystemThemes() throws Exception {
        try {
            for(String theme:new String[]{"notnight","night"}) for(String language:new String[]{"ru","en"})
                for(float scale:new float[]{1f,1.8f}) for(int[] size:new int[][]{{375,812},{800,375},{800,1000}}) {
                    RuntimeEnvironment.setQualifiers(language+"-w"+size[0]+"dp-h"+size[1]+"dp-"+theme+"-mdpi");
                    RuntimeEnvironment.setFontScale(scale);
                    Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
                    PersonalConnectionView view=new PersonalConnectionView(context);
                    view.setPadding(16,12,16,16);
                    view.render(40,R.string.personal_connection_dns_phone_wait,"https://gluco-my-sugar-abcdefghijklmnopqrstuvwxyz.api-api-api.com/",false,false);
                    long now=android.os.SystemClock.elapsedRealtime();
                    view.renderTiming(now-30_000,now+10_000,2,0,0,40);
                    view.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
                    view.layout(0,0,size[0],size[1]);
                    assertTrue(view.scroll.getHeight()>0);
                    assertTrue(view.primary.getBottom()<=size[1]-16);
                    assertTrue(view.primary.getHeight()>=48);
                    for(android.widget.TextView step:view.steps) assertTrue(step.getLayout().getHeight()<=step.getHeight());
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(ClinicalUi.primaryText(context),ClinicalUi.window(context))>=4.5);
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(ClinicalUi.accent(context),ClinicalUi.window(context))>=4.5);
                    File output=new File("build/reports/personal-connection/"+language+"-"+theme+"-"+size[0]+"x"+size[1]+"-"+scale+".png");
                    output.getParentFile().mkdirs();
                    Bitmap bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    try(FileOutputStream stream=new FileOutputStream(output)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); }
                    bitmap.recycle();
                }
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }
}
