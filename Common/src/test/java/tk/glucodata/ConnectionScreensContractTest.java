package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards validation and accessibility contracts shared by the redesigned connection screens. */
public class ConnectionScreensContractTest {
    @Test
    public void connectionControlsMeetAndroidTouchTargetMinimum() {
        assertTrue(ConnectionUi.MIN_TOUCH_DP >= 48);
    }

    @Test
    public void webServerRejectsReservedAndOutOfRangePorts() {
        assertTrue(Nightscout.validHttpServerPort(1024));
        assertTrue(Nightscout.validHttpServerPort(65535));
        assertFalse(Nightscout.validHttpServerPort(1023));
        assertFalse(Nightscout.validHttpServerPort(17580));
        assertFalse(Nightscout.validHttpServerPort(65536));
    }

    @Test
    public void nightscoutUploaderAcceptsOnlyHttpEndpointsWithHosts() {
        assertTrue(NightPost.validNightscoutUrl("https://example.org/api/v1"));
        assertTrue(NightPost.validNightscoutUrl("http://192.0.2.1:1337"));
        assertFalse(NightPost.validNightscoutUrl(""));
        assertFalse(NightPost.validNightscoutUrl("example.org"));
        assertFalse(NightPost.validNightscoutUrl("ftp://example.org"));
    }

    @Test
    public void libreViewRequiresCredentialsOnlyWhenEnabled() {
        assertTrue(Libreview.validLibreCredentials("", "", false));
        assertTrue(Libreview.validLibreCredentials("a@b", "123", true));
        assertTrue(Libreview.validLibreCredentials(repeat('a', 255), repeat('p', 36), true));
        assertFalse(Libreview.validLibreCredentials("ab", "123", true));
        assertFalse(Libreview.validLibreCredentials("a@b", "12", true));
        assertFalse(Libreview.validLibreCredentials(repeat('a', 256), "123", true));
        assertFalse(Libreview.validLibreCredentials("a@b", repeat('p', 37), true));
    }

    @Test
    public void relayAndMirrorPortsKeepNativeBoundaries() {
        assertTrue(TurnServer.validTurnPort(1));
        assertTrue(TurnServer.validTurnPort(65535));
        assertFalse(TurnServer.validTurnPort(0));
        assertFalse(TurnServer.validTurnPort(65536));

        assertTrue(Backup.validMirrorPort(1024, 443));
        assertTrue(Backup.validMirrorPort(65535, 443));
        assertFalse(Backup.validMirrorPort(1023, 443));
        assertFalse(Backup.validMirrorPort(17580, 443));
        assertFalse(Backup.validMirrorPort(8443, 8443));
        assertFalse(Backup.validMirrorPort(65536, 443));
    }

    @Test
    public void nestedConnectionEditorsKeepValidationAndDateBoundaries() {
        assertTrue(Libreview.validLibreAccountId("0"));
        assertTrue(Libreview.validLibreAccountId("9223372036854775807"));
        assertFalse(Libreview.validLibreAccountId(""));
        assertFalse(Libreview.validLibreAccountId("12.5"));
        assertFalse(Libreview.validLibreAccountId("9223372036854775808"));

        long now=2_000_000_000_000L;
        assertEquals(89L*24L*60L*60L*1000L,
                now-Libreview.defaultLibreResendStart(now));

        assertTrue(MeterConfig.validBloodLabelSelection(0,3));
        assertTrue(MeterConfig.validBloodLabelSelection(1,3));
        assertFalse(MeterConfig.validBloodLabelSelection(-1,3));
        assertFalse(MeterConfig.validBloodLabelSelection(2,3));
    }

    @Test
    public void nestedConnectionFlowsUseModernFullScreenShells() throws Exception {
        String libre=mobileSource("tk/glucodata/Libreview.java");
        assertTrue(libre.contains("connection_account_id_title"));
        assertTrue(libre.contains("connection_resend_title"));
        assertTrue(libre.contains("ConnectionUi.confirmSheet"));

        String meter=mobileSource("tk/glucodata/MeterConfig.java");
        assertTrue(meter.contains("connection_meter_config_title"));
        assertTrue(meter.contains("ConnectionUi.fullScreen"));

        String backup=mainSource("tk/glucodata/Backup.java");
        assertTrue(backup.contains("connection_auto_qr_title"));
        assertTrue(backup.contains("legacyMakeAutoQR"));

        String qr=source(Paths.get("src","mobileSi","java","tk","glucodata","QRmake.java"));
        assertTrue(qr.contains("connection_qr_title"));
        assertTrue(qr.contains("ConnectionUi.fullScreen"));
    }

    @Test
    public void mirrorOverviewUsesOnePageScrollerWithoutNestedListGestureCapture()
            throws Exception {
        String backup=mainSource("tk/glucodata/Backup.java");
        int overviewStart=backup.indexOf("private void clinicalBackupView");
        int overviewEnd=backup.indexOf("public  void realmkbackupview",overviewStart);
        assertTrue(overviewStart>=0);
        assertTrue(overviewEnd>overviewStart);
        String overview=backup.substring(overviewStart,overviewEnd);
        assertTrue(overview.contains("ScrollView screen=ConnectionUi.screen(act,content)"));
        assertTrue(overview.contains("hosts.setNestedScrollingEnabled(false)"));
        assertTrue(overview.contains(
                "content.addView(hosts,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))"));
        assertTrue(overview.contains("removeContentView(screen)"));
        assertTrue(overview.contains("ConnectionUi.fullScreen(act,screen)"));
    }

    @Test
    public void nestedMirrorReturnsToSettingsWithoutReopeningMore() throws Exception {
        String backup=mainSource("tk/glucodata/Backup.java");
        int overviewStart=backup.indexOf("private void clinicalBackupView");
        int overviewEnd=backup.indexOf("public  void realmkbackupview",overviewStart);
        String overview=backup.substring(overviewStart,overviewEnd).replaceAll("\\s+"," ");
        assertTrue(overview.contains(
                "if(lightback) { act.showui=false;"));
        assertTrue(overview.contains(
                "if(Menus.on) Menus.show(act);"));
        int restoreStart=overview.indexOf("if(lightback) { act.showui=false;");
        int restoreEnd=overview.indexOf("};",restoreStart);
        assertTrue(restoreStart>=0);
        assertTrue(restoreEnd>restoreStart);
        assertTrue(overview.substring(restoreStart,restoreEnd).contains("Menus.show(act)"));
    }

    private static String mainSource(String relative) throws IOException {
        return source(Paths.get("src","main","java").resolve(relative));
    }

    private static String mobileSource(String relative) throws IOException {
        return source(Paths.get("src","mobile","java").resolve(relative));
    }

    private static String source(Path relative) throws IOException {
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            result.append(value);
        return result.toString();
    }
}
