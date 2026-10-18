package tk.glucodata;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable installation identity. APK updates keep it; a new installation does not clone it. */
public final class PersonalConnection {
    private static PersonalConnection instance;
    private final Context context;
    private final IntakeRepository repository;
    private final PersonalConnectionStore store;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private volatile boolean busy;
    private volatile int progress;
    private volatile int status=R.string.personal_connection_waiting;
    private volatile String viewerUrl="";
    private volatile boolean legacy;
    private long lastAttempt;
    private volatile long startedAt, nextRetry, verifiedAt, readingAt;
    private volatile int attempts;
    private final Runnable retryTask=()->ensure(false);

    private PersonalConnection(Context context) {
        this.context=context.getApplicationContext();
        repository=IntakeRepository.get(context);
        store=new PersonalConnectionStore(this.context);
    }

    public static synchronized PersonalConnection get(Context context) {
        if(instance==null) instance=new PersonalConnection(context);
        return instance;
    }

    public static void initialize(Context context) { get(context).ensure(false); }
    public boolean busy() { return busy; }
    public int progress() { return progress; }
    public int status() { return status; }
    public String viewerUrl() { return viewerUrl; }
    public boolean needsClaim() { return legacy; }
    public long startedAt() { return startedAt; }
    public long nextRetry() { return nextRetry; }
    public long verifiedAt() { return verifiedAt; }
    public long readingAt() { return readingAt; }
    public int attempts() { return attempts; }
    public void observe(Runnable listener) { listeners.addIfAbsent(listener); listener.run(); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }

    private void publish(int percent,int message) {
        progress=percent;
        status=message;
        main.post(()-> { for(Runnable listener:listeners) listener.run(); });
    }

    /** Foreground retries are throttled; opening Settings can explicitly retry. */
    public synchronized void ensure(boolean explicit) {
        long now=android.os.SystemClock.elapsedRealtime();
        if(busy || (!explicit && ((progress==100&&now-lastAttempt<120_000)
                ||(lastAttempt>0&&now-lastAttempt<10_000)))) return;
        busy=true;
        worker.execute(()->run(false,null));
    }

    public synchronized void claimExisting() {
        if(busy) return;
        busy=true;
        worker.execute(()->run(true,null));
    }

    public synchronized void rotate(String label) {
        if(busy || viewerUrl.isEmpty()) return;
        busy=true;
        worker.execute(()->run(false,label));
    }

    private JSONObject load() throws Exception {
        return store.load();
    }

    private void save(JSONObject value) throws Exception {
        store.save(value);
    }

    private void run(boolean claim,String rotationLabel) {
        boolean retry=false;
        boolean verifying=false;
        boolean requestingRotation=false;
        main.removeCallbacks(retryTask);
        nextRetry=0;
        lastAttempt=android.os.SystemClock.elapsedRealtime();
        if(startedAt==0||progress==100||rotationLabel!=null) {
            startedAt=lastAttempt;
            attempts=0;
        }
        attempts++;
        try {
            publish(1,R.string.personal_connection_identity);
            JSONObject identity=load();
            IntakeRepository.BackendIdentity before=repository.backendIdentity();
            if(identity==null) {
                legacy=!before.token.isEmpty();
                // An upgrade must never replace an existing user's backend/history.
                if(legacy&&!claim) {
                    publish(0,R.string.personal_connection_existing);
                    return;
                }
                if(before.url.contains("/devices/")) {
                    publish(0,R.string.personal_connection_manual);
                    return;
                }
                byte[] bytes=new byte[32];
                new SecureRandom().nextBytes(bytes);
                identity=new JSONObject().put("id",UUID.randomUUID().toString())
                        .put("secret",Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.NO_PADDING|Base64.URL_SAFE))
                        .put("base",before.url).put("claim",claim);
                // Persist before the first request: a lost response cannot create a second account.
                save(identity);
            }
            publish(10,R.string.personal_connection_connecting);
            String base=identity.getString("base");
            String id=identity.getString("id");
            String secret=identity.getString("secret");
            String api=base+"/devices/"+id;
            String viewer=identity.optString("viewer","");
            boolean configured=before.url.equals(api)&&before.token.equals(secret);
            boolean original=before.url.equals(base) && (identity.optBoolean("claim") ? !before.token.isEmpty() : before.token.isEmpty());
            if(!configured&&!original) {
                viewerUrl="";
                publish(0,R.string.personal_connection_manual);
                return;
            }
            viewerUrl=viewer;
            legacy=false;
            IntakeApiClient client=new IntakeApiClient(base,secret);
            JSONObject response;
            if(rotationLabel!=null) {
                if(!PersonalConnectionPolicy.validLabel(rotationLabel)) {
                    publish(0,R.string.personal_connection_label_invalid);
                    return;
                }
                // Persist the operation before sending; retries reuse the exact operation.
                if(!identity.has("rotation_id")) {
                    identity.put("rotation_id",UUID.randomUUID().toString()).put("rotation_label",rotationLabel);
                    save(identity);
                }
            }
            if(viewer.isEmpty()) {
                response=client.connectionRequest("POST",identity.optBoolean("claim")?
                        "/v1/installations/claim":"/v1/installations",
                        new JSONObject().put("installation_id",id),
                        identity.optBoolean("claim")?before.token:null);
            } else if(identity.has("rotation_id")) {
                requestingRotation=true;
                response=client.connectionRequest("POST","/devices/"+id+"/connection",
                        new JSONObject().put("operation_id",identity.getString("rotation_id"))
                                .put("label",identity.optString("rotation_label")),null);
            } else {
                response=client.connectionRequest("GET","/devices/"+id+"/connection",null,null);
            }
            String path=response.getString("viewer_path");
            String publicUrl=response.isNull("viewer_url")?"":response.optString("viewer_url","");
            if(!id.equals(response.getString("installation_id"))
                    ||!response.getString("api_path").equals("/devices/"+id)
                    ||!PersonalConnectionPolicy.validViewerPath(path))
                throw new IllegalArgumentException("Invalid connection response");
            if(!publicUrl.isEmpty()&&!PersonalConnectionPolicy.validViewerUrl(publicUrl,base))
                throw new IllegalArgumentException("Invalid viewer domain");
            publish(25,R.string.personal_connection_created);
            String nextViewer=publicUrl.isEmpty()?base+path:publicUrl;
            identity.put("viewer",nextViewer);
            identity.remove("rotation_id");
            identity.remove("rotation_label");
            save(identity);
            publish(25,R.string.personal_connection_saving);
            synchronized(repository) {
                if(!repository.backendIdentity().sameAs(before)
                        ||!repository.configure(api,secret)) {
                    retry=true;
                    publish(25,R.string.personal_connection_pending);
                    return;
                }
            }
            viewerUrl=nextViewer;
            verifying=true;
            // Server readiness cannot prove DNS/HTTPS on the phone's Wi-Fi,
            // mobile carrier or VPN. Always run the public path locally.
            long latest=PersonalLinkVerifier.verify(nextViewer,base,path,this::publish,new PersonalLinkVerifier.Network());
            JSONObject finalState=client.connectionRequest("GET","/devices/"+id+"/connection",null,null);
            String finalUrl=finalState.optString("viewer_url","");
            if(finalUrl.isEmpty()) finalUrl=base+finalState.getString("viewer_path");
            if(!nextViewer.equals(finalUrl)||!id.equals(finalState.getString("installation_id")))
                throw new java.io.IOException("Connection changed while verifying");
            synchronized(repository) {
                IntakeRepository.BackendIdentity current=repository.backendIdentity();
                if(!current.url.equals(api)||!current.token.equals(secret))
                    throw new java.io.IOException("Phone connection changed while verifying");
            }
            readingAt=latest;
            verifiedAt=System.currentTimeMillis();
            publish(100,R.string.personal_connection_ready);
        } catch(IntakeApiClient.ApiException error) {
            retry=error.statusCode>=500||error.statusCode==429;
            if(requestingRotation&&(error.statusCode==409||error.statusCode==422)) {
                // Definitive rejection: retain the working link and let the user
                // choose another name. Only uncertain network errors replay it.
                try {
                    JSONObject identity=load();
                    identity.remove("rotation_id");
                    identity.remove("rotation_label");
                    save(identity);
                } catch(Exception persistenceError) {
                    publish(progress,R.string.personal_connection_retry);
                    return;
                }
                publish(progress,error.statusCode==409?R.string.personal_connection_name_taken:
                        R.string.personal_connection_label_invalid);
                return;
            }
            publish(progress,error.statusCode==409?R.string.personal_connection_conflict:
                    error.statusCode==401?R.string.personal_connection_auth_error:
                    error.statusCode==429?R.string.personal_connection_capacity:
                    R.string.personal_connection_retry);
        } catch(Exception error) {
            retry=verifying||error instanceof java.io.IOException;
            // Never log credential-bearing JSON, URL, response bodies or provider secrets.
            publish(progress,error instanceof java.net.UnknownHostException?R.string.personal_connection_dns_phone_wait:
                    error instanceof javax.net.ssl.SSLException?R.string.personal_connection_tls_failed:
                    verifying?R.string.personal_connection_verification_retry:R.string.personal_connection_retry);
        } finally {
            busy=false;
            if(retry) {
                long delay=listeners.isEmpty()?60_000L:Math.min(30_000L,10_000L*attempts);
                nextRetry=android.os.SystemClock.elapsedRealtime()+delay;
                main.postDelayed(retryTask,delay);
            }
            main.post(()-> { for(Runnable listener:listeners) listener.run(); });
        }
    }
}
