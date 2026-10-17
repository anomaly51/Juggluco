package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppUpdatePanelVisualTest {
    @Test public void narrowFoldLandscapeAndLargeTextRemainReadable() throws Exception {
        for (String language : new String[]{"en", "ru"}) {
            for (int[] size : new int[][]{{360,800},{800,360},{700,800},{320,640}}) {
                RuntimeEnvironment.setQualifiers(language+"-w"+size[0]+"dp-h"+size[1]+"dp-mdpi");
                RuntimeEnvironment.setFontScale(size[0] == 320 ? 1.8f : 1f);
                try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
                    Activity activity = controller.get();
                    activity.setTheme(R.style.AppTheme_ClinicalDark);
                    AppUpdates.prefs(activity).edit().clear().putString("state", "downloading")
                            .putLong("target", BuildConfig.VERSION_CODE+1)
                            .putString("target_name", "10.9.8.next").commit();
                    AppUpdates.downloadedBytes=10*1048576;
                    AppUpdates.totalBytes=28*1048576;
                    AlertDialog dialog=AppUpdatePanel.show(activity, () -> {});
                    View root=dialog.getWindow().getDecorView();
                    int width=Math.min(480,size[0]-32), height=size[1]-48;
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                    root.layout(0,0,width,height);
                    Button action=root.findViewWithTag("update_action");
                    TextView text=root.findViewWithTag("update_explanation");
                    assertTrue(action.getHeight()>=48);
                    assertTrue(text.getLayout().getHeight()<=text.getHeight());
                    assertTrue(text.getWidth()<width);
                    ScrollView scroll=(ScrollView)action.getParent().getParent();
                    assertTrue(scroll.getChildAt(0).getHeight()>=scroll.getHeight());
                    File output=new File("build/reports/android-updates/panel-"+language+"-"+size[0]+"x"+size[1]+".png");
                    output.getParentFile().mkdirs();
                    Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(bitmap));
                    try(FileOutputStream stream=new FileOutputStream(output)) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));
                    }
                    bitmap.recycle();
                    dialog.dismiss();
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
    }
}
