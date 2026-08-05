package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Prevents hardware-only phone routes from falling back to centered legacy panels. */
public class HardwareOnboardingUxContractTest {
    private static final Pattern STRING_NAME=Pattern.compile("<string\\s+name=\"([^\"]+)\"");

    @Test
    public void sibionicsScannerAndAccountUseFullScreenClinicalShells() throws Exception {
        String scan=source(Paths.get("src","mobileSi","java","tk","glucodata","PhotoScan.java"));
        assertTrue(scan.contains("ClinicalUi.verticalContent"));
        assertTrue(scan.contains("FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
        assertFalse(scan.contains("FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT"));

        String account=source(Paths.get("src","mobileSi","java","tk","glucodata","GetGS3ID.java"));
        assertTrue(account.contains("hardware_server_account_title"));
        assertTrue(account.contains("hardware_manual_account_title"));
        assertTrue(account.contains("ClinicalUi.scrollScreen"));
        assertFalse(account.contains("(int)(width*0.7f)"));
        assertFalse(account.contains("(int)(width*.65f)"));
    }

    @Test
    public void novoPenImportUsesSafeScrollableFullScreenForm() throws Exception {
        String scan=source(Paths.get("src","mobile","java","tk","glucodata","NovoPen","Scan.java"));
        assertTrue(scan.contains("hardware_novopen_title"));
        assertTrue(scan.contains("ClinicalUi.scrollScreen"));
        assertTrue(scan.contains("FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
        assertFalse(scan.contains("params.topMargin=MainActivity.systembarTop*4/5"));
    }

    @Test
    public void hardwareStringsHaveEnglishRussianParity() throws Exception {
        Set<String> english=stringNames(source(Paths.get("src","main","res","values",
                "hardware_onboarding_strings.xml")));
        Set<String> russian=stringNames(source(Paths.get("src","main","res","values-ru",
                "hardware_onboarding_strings.xml")));
        assertEquals(english,russian);
        assertTrue(english.size()>=20);
    }

    private static Set<String> stringNames(String xml) {
        Set<String> names=new HashSet<>();
        Matcher matcher=STRING_NAME.matcher(xml);
        while(matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static String source(Path relative) throws IOException {
        if(!Files.exists(relative)) relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
