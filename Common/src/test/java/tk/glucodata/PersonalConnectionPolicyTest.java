package tk.glucodata;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalConnectionPolicyTest {
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
