package tk.glucodata;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the target-36 foreground capability used by app-owned alarm audio. */
public class CriticalAlarmForegroundServiceContractTest {
    @Test
    public void manifestDeclaresBothSensorAndAlarmPlaybackCapabilities()
            throws Exception {
        String manifest = source(Paths.get("src", "main",
                "AndroidManifest.xml"));
        assertTrue(manifest.contains(
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"));
        assertTrue(manifest.contains(
                "android:foregroundServiceType=\"connectedDevice|mediaPlayback\""));
    }

    @Test
    public void runningSensorServiceDynamicallyAddsAndDropsPlaybackType()
            throws Exception {
        String notify = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "Notify.java"));
        assertTrue(notify.contains(
                "ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"));
        assertTrue(notify.contains(
                "ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK"));
        assertTrue(notify.contains(
                "service.startForeground(glucosenotificationid,notification,types)"));

        String service = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "keeprunning.java"));
        assertTrue(service.contains("promoteCriticalAlarmAudio()"));
        assertTrue(service.contains("demoteCriticalAlarmAudio()"));
        assertTrue(service.contains(
                "Notify.setCriticalAlarmAudioForeground(service,true)"));
        assertTrue(service.contains(
                "Notify.setCriticalAlarmAudioForeground(service,false)"));
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }
}
