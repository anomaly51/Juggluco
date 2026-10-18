package tk.glucodata;

import android.app.Application;
import android.net.DnsResolver;
import android.os.CancellationSignal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class PersonalDnsResolverTest {
    @Test public void requestsFreshDnsWithoutDisablingCacheStorage() throws Exception {
        InetAddress address=InetAddress.getByAddress(new byte[]{1,1,1,1});
        PersonalDnsResolver.await((flags,cancel,callback)-> {
            assertEquals(DnsResolver.FLAG_NO_CACHE_LOOKUP,flags);
            assertEquals(0,flags & DnsResolver.FLAG_NO_CACHE_STORE);
            callback.onAnswer(Collections.singletonList(address),0);
        },100);
    }

    @Test public void emptyAnswerFailureAndTimeoutCannotClaimDnsIsReady() {
        assertThrows(UnknownHostException.class,()->PersonalDnsResolver.await(
                (flags,cancel,callback)->callback.onAnswer(Collections.emptyList(),0),100));
        assertThrows(UnknownHostException.class,()->PersonalDnsResolver.await(
                (flags,cancel,callback)->callback.onAnswer(Collections.emptyList(),3),100));
        assertThrows(UnknownHostException.class,()->PersonalDnsResolver.await(
                (flags,cancel,callback)->callback.onError(new DnsResolver.DnsException(1,null)),100));
        CancellationSignal[] signal={null};
        assertThrows(UnknownHostException.class,()->PersonalDnsResolver.await(
                (flags,cancel,callback)->signal[0]=cancel,1));
        assertTrue(signal[0].isCanceled());
    }
}
