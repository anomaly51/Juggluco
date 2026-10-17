package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
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
@Config(sdk=35, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppUpdateFooterVisualTest {
    @Test public void portraitLandscapeAndLargeTextKeepVersionVisible() throws Exception {
        for (int[] size : new int[][]{{375,812},{812,375},{360,640}}) {
            RuntimeEnvironment.setQualifiers("w"+size[0]+"dp-h"+size[1]+"dp-mdpi");
            for (float scale : new float[]{1f, 1.8f}) {
                Context app=RuntimeEnvironment.getApplication();
                android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());
                config.fontScale=scale;
                Context context=new ContextThemeWrapper(app.createConfigurationContext(config),R.style.AppTheme_ClinicalDark);
                View root=LayoutInflater.from(context).inflate(R.layout.menus,null,false);
                View panel=root.findViewById(R.id.modern_menu_panel);
                ViewGroup.LayoutParams params=panel.getLayoutParams();
                params.width=Math.min(400,Math.round(size[0]*0.88f));
                panel.setLayoutParams(params);
                TextView version=root.findViewById(R.id.modern_menu_version);
                version.setText(AppUpdates.versionLabel());
                ((TextView)root.findViewById(R.id.about)).setText(R.string.photo);
                root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
                root.layout(0,0,size[0],size[1]);
                assertTrue(version.getHeight()>0);
                assertTrue(version.getLayout().getHeight() <= version.getHeight());
                assertTrue(version.getWidth() <= params.width);
                int[] location=new int[2]; version.getLocationOnScreen(location);
                assertTrue(location[1]+version.getHeight() <= size[1]);
                int surface=context.getColor(R.color.lifeprompt_surface_container);
                assertTrue(ColorUtils.calculateContrast(version.getCurrentTextColor(),surface)>=4.5);
                Bitmap bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(bitmap); canvas.drawColor(Color.BLACK); root.draw(canvas);
                File target=new File("build/reports/android-updates/more-"+size[0]+"x"+size[1]+"-"+scale+".png");
                target.getParentFile().mkdirs();
                try(FileOutputStream stream=new FileOutputStream(target)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));
                }
                bitmap.recycle();
            }
        }
    }
}

