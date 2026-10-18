package tk.glucodata;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalConnectionPolicyTest {
    @Test public void unpublishedDomainsMustNotBeQueriedButLegacyPathsStillWork() {
        assertTrue(PersonalConnectionPolicy.publicationPending("https://user-abc23.api-api-api.com/",false));
        assertFalse(PersonalConnectionPolicy.publicationPending("https://user-abc23.api-api-api.com/",true));
        assertFalse(PersonalConnectionPolicy.publicationPending("",false));
    }
    @Test public void personalDnsUrlIsHttpsAndBoundToTheConfiguredProductionZone() {
        String base="https://juggluco-general1.api-api-api.com";
        String host="gluco-my-sugar-abcdefghijklmnopqrstuvwxyz.api-api-api.com";
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://"+host+"/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://g-abcdefghijkl.api-api-api.com/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://g-my-sugar-abcdefghijkl.api-api-api.com/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://user-abcdefghijkl.api-api-api.com/",base));
        assertTrue(PersonalConnectionPolicy.validViewerUrl("https://vlad.api-api-api.com/",base));
        for(String bad:new String[]{null,"http://"+host+"/","https://"+host+":8443/",
                "https://owner@"+host+"/","https://"+host+"/?profile=x","https://"+host+"/#token",
                "https://"+host+"/viewer/","https://-name.api-api-api.com/","https://"+host+".evil.invalid/",
                "https://name-.api-api-api.com/","https://g-abcdefghijkl.evil.invalid/",
                "https://internal.api-api-api.com/","https://api.api-api-api.com/",
                "https://job-crm-general1.api-api-api.com/","https://foo.bar.api-api-api.com/",
                "https://g-abcdefghijkl.api-api-api.com/?token=bad","http://g-abcdefghijkl.api-api-api.com/"})
            assertFalse(String.valueOf(bad),PersonalConnectionPolicy.validViewerUrl(bad,base));
        assertFalse(PersonalConnectionPolicy.validViewerUrl("https://"+host+"/","https://other.example.com"));
    }
    @Test public void labelsAreOptionalAndCannotInjectUrlComponents() {
        assertTrue(PersonalConnectionPolicy.validLabel(""));
        assertTrue(PersonalConnectionPolicy.validLabel("my-sugar"));
        assertTrue(PersonalConnectionPolicy.validLabel("a".repeat(57)));
        assertTrue(PersonalConnectionPolicy.validLabel("vlad"));
        for(String bad:new String[]{null,"../other","a?profile=x","a/b","-hello","hello-","Сахар","a b","a".repeat(58),
                "user-abcdefghijkl","api","internal","juggluco-general1","online-shop-general2"})
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
