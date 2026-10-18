package tk.glucodata;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalConnectionPolicyTest {
    @Test public void personalDnsUrlIsHttpsAndBoundToTheConfiguredProductionZone() {
        String base="https://juggluco-general1.api-api-api.com";
        String host="gluco-my-sugar-abcdefghijklmnopqrstuvwxyz.api-api-api.com";
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://"+host+"/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://g-abcdefghijkl.api-api-api.com/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://g-my-sugar-abcdefghijkl.api-api-api.com/",base));
        for(String bad:new String[]{null,"http://"+host+"/","https://"+host+":8443/",
                "https://owner@"+host+"/","https://"+host+"/?profile=x","https://"+host+"/#token",
                "https://"+host+"/viewer/","https://gluco-short.api-api-api.com/","https://"+host+".evil.invalid/",
                "https://g-abcdef.api-api-api.com/","https://g-abcdefghijkl.evil.invalid/",
                "https://g-abcdefghijkl.api-api-api.com/?token=bad","http://g-abcdefghijkl.api-api-api.com/"})
            assertFalse(String.valueOf(bad),PersonalConnectionPolicy.validViewerUrl(bad,base));
        assertFalse(PersonalConnectionPolicy.validViewerUrl("https://"+host+"/","https://other.example.com"));
    }
    @Test public void labelsAreOptionalAndCannotInjectUrlComponents() {
        assertTrue(PersonalConnectionPolicy.validLabel(""));
        assertTrue(PersonalConnectionPolicy.validLabel("my-sugar"));
        for(String bad:new String[]{null,"../other","a?profile=x","a/b","-hello","Сахар","a b","abcdefghijklmnopqrstuvwxyz"})
            assertFalse(PersonalConnectionPolicy.validLabel(bad));
    }
    @Test public void viewerResponseCannotRedirectToAnotherHostOrUseShortGuessableIds() {
        assertTrue(PersonalConnectionPolicy.validViewerPath("/viewer/?profile=abcdefghijklmnopqrstuv"));
        assertTrue(PersonalConnectionPolicy.validViewerPath("/viewer/?profile=my-sugar-abcdefghijklmnopqrstuv"));
        for(String bad:new String[]{null,"https://evil.invalid/","//evil.invalid/","/viewer/?profile=1234",
                "/viewer/?profile=abcdefghijklmnopqrstuv&token=private"})
            assertFalse(PersonalConnectionPolicy.validViewerPath(bad));
    }
}
