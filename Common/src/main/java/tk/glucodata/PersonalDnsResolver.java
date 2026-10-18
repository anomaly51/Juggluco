package tk.glucodata;

import android.net.DnsResolver;
import android.os.CancellationSignal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Refresh a newly published name through Android's configured DNS, including Private DNS/VPN. */
@android.annotation.TargetApi(29)
final class PersonalDnsResolver {
    private PersonalDnsResolver() {}

    interface Query {
        void start(int flags, CancellationSignal cancellation, DnsResolver.Callback<List<InetAddress>> callback);
    }

    static void resolve(String host) throws Exception {
        await((flags, cancellation, callback) -> DnsResolver.getInstance().query(
                null, host, flags, Runnable::run, cancellation, callback), 10_000);
    }

    static void await(Query query, long timeoutMillis) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        CancellationSignal cancellation = new CancellationSignal();
        AtomicReference<List<InetAddress>> addresses = new AtomicReference<>();
        try {
            // Bypass stale negative resolver answers, keeping the configured
            // network/provider. This is not proof that HTTPS or a browser works:
            // the caller still verifies the public page and data independently.
            query.start(DnsResolver.FLAG_NO_CACHE_LOOKUP, cancellation,
                    new DnsResolver.Callback<List<InetAddress>>() {
                @Override public void onAnswer(List<InetAddress> answer, int rcode) {
                    if (rcode == 0) addresses.set(answer);
                    completed.countDown();
                }
                @Override public void onError(DnsResolver.DnsException error) { completed.countDown(); }
            });
            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
                    || addresses.get() == null || addresses.get().isEmpty())
                throw new UnknownHostException("Personal link DNS unavailable on this network");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally { cancellation.cancel(); }
    }
}
