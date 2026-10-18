package tk.glucodata;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalLinkVerifierTest {
    private static final String BASE="https://juggluco-general1.api-api-api.com";
    private static final String HOST="gluco-abcdefghijklmnopqrstuvwxyz.api-api-api.com";
    private static final String URL="https://"+HOST+"/";
    private static final String PATH="/viewer/?profile=abcdefghijklmnopqrstuv";
    private static final class Fake implements PersonalLinkVerifier.Transport {
        boolean dns=true, wrongHost, missingScript, wrongManifest, invalidSnapshot, emptyAsset, remoteScript;
        String failPath;
        final List<String> requests=new ArrayList<>();
        public void resolve(String host) throws Exception { if(!dns) throw new UnknownHostException(); }
        public String get(String url) throws Exception {
            requests.add(url);
            assertTrue("Only public same-origin requests",url.startsWith(URL));
            if(failPath!=null&&url.endsWith(failPath)) throw new IOException("Unavailable");
            if(url.endsWith("/ready")) return "{\"ready\":true,\"host\":\""+(wrongHost?"other.example":HOST)+"\"}";
            if(url.equals(URL)) return "<div id=\"root\"></div>"+(missingScript?"":
                    "<script src=\""+(remoteScript?"https://other.example/":"/viewer/assets/")+"main.js\"></script><link href=\"/viewer/assets/main.css\">");
            if(url.endsWith(".js")||url.endsWith(".css")) return emptyAsset?"":"application file";
            if(url.endsWith(".webmanifest")) return "{\"start_url\":\""+(wrongManifest?"/other":"/")+"\"}";
            if(url.endsWith("/snapshot")) return invalidSnapshot?"{}":"{\"api_version\":\"v1\",\"current_glucose\":null,\"glucose_history\":[],\"intake_events\":[]}";
            throw new AssertionError(url);
        }
    }
    private List<Integer> stages=new ArrayList<>();
    private long verify(Fake fake) throws Exception {
        return PersonalLinkVerifier.verify(URL,BASE,PATH,(percent,message)->stages.add(percent),fake);
    }
    @Test public void fullPublicPathMustPassAndEmptyProfileIsNotInventedData() throws Exception {
        Fake fake=new Fake();
        assertEquals(0,verify(fake));
        assertEquals(java.util.Arrays.asList(40,55,70,85,95),stages);
        assertFalse("Only controller's final ownership check may publish 100",stages.contains(100));
        assertEquals(6,fake.requests.size());
    }
    @Test public void phoneDnsFailureCannotBecomeReady() {
        Fake fake=new Fake(); fake.dns=false;
        assertThrows(UnknownHostException.class,()->verify(fake));
        assertEquals(java.util.Arrays.asList(40),stages);
        assertTrue(fake.requests.isEmpty());
    }
    @Test public void wrongHttpsHostCannotBecomeReady() {
        Fake fake=new Fake(); fake.wrongHost=true;
        assertThrows(IOException.class,()->verify(fake));
        assertEquals(Integer.valueOf(55),stages.get(stages.size()-1));
    }
    @Test public void htmlWithoutRunnableBundleCannotBecomeReady() {
        Fake fake=new Fake(); fake.missingScript=true;
        assertThrows(IOException.class,()->verify(fake));
        assertEquals(Integer.valueOf(70),stages.get(stages.size()-1));
    }
    @Test public void remoteAssetIsNotFetched() {
        Fake fake=new Fake(); fake.remoteScript=true;
        assertThrows(IOException.class,()->verify(fake));
        assertEquals(2,fake.requests.size());
    }
    @Test public void missingOrEmptyAssetsCannotBecomeReady() {
        Fake fake=new Fake(); fake.failPath="main.js";
        assertThrows(IOException.class,()->verify(fake));
        Fake empty=new Fake(); empty.emptyAsset=true;
        assertThrows(IOException.class,()->verify(empty));
    }
    @Test public void wrongManifestOrInvalidSnapshotCannotBecomeReady() {
        Fake fake=new Fake(); fake.wrongManifest=true;
        assertThrows(IOException.class,()->verify(fake));
        Fake invalid=new Fake(); invalid.invalidSnapshot=true;
        assertThrows(IOException.class,()->verify(invalid));
        assertEquals(Integer.valueOf(85),stages.get(stages.size()-1));
    }
}
