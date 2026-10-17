package tk.glucodata;

import android.content.Context;
import android.net.Uri;

import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

/** Registers packaged critical tones with Robolectric's deterministic player. */
final class CriticalAlarmTestMedia {
    private CriticalAlarmTestMedia() {}

    static void resetAndRegister(Context context) {
        ShadowMediaPlayer.resetStaticState();
        for (CriticalAlarmSoundCatalog.Tone tone
                : CriticalAlarmSoundCatalog.tones()) {
            Uri uri = Uri.parse("android.resource://" + context.getPackageName()
                    + '/' + tone.soundRes);
            ShadowMediaPlayer.addMediaInfo(
                    DataSource.toDataSource(context, uri),
                    new ShadowMediaPlayer.MediaInfo(60_000, 0));
        }
    }

    static void reset() {
        ShadowMediaPlayer.resetStaticState();
    }
}
