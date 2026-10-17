package tk.glucodata;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real HTTP transport, synthetic profile: no public backend or provider calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalConnectionStateTest {
    private Context context;
    private ServerSocket server;
    private Thread serverThread;
    private volatile Throwable serverError;
    private String base,id,owner;
    private volatile boolean failNext;
    private final List<String> registrations=new ArrayList<>();
    private final List<String> rotations=new ArrayList<>();
    private String handle="abcdefghijklmnopqrstuv";

    @Before public void setUp() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("intake_backend",0).edit().clear().commit();
        new File(context.getNoBackupFilesDir(),"personal-connection.db").delete();
        Field repository=IntakeRepository.class.getDeclaredField("instance");
        repository.setAccessible(true);
        repository.set(null,null);
        server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        base="http://127.0.0.1:"+server.getLocalPort();
        serverThread=new Thread(()-> {
            while(!server.isClosed()) try(Socket socket=server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                String[] request=input.readLine().split(" ");
                String path=request[1];
                String authorization="";
                int length=0;
                String line;
                while((line=input.readLine())!=null&&!line.isEmpty()) {
                    if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    if(line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")) authorization=line.substring(line.indexOf(':')+1).trim();
                }
                char[] payload=new char[Math.min(length,8192)];
                int read=0;
                while(read<payload.length) { int n=input.read(payload,read,payload.length-read); if(n<0) break; read+=n; }
                JSONObject body=length==0?new JSONObject():new JSONObject(new String(payload));
                if(path.equals("/v1/installations")) {
                    id=body.getString("installation_id");
                    owner=authorization;
                    registrations.add(id+owner);
                } else if(request[0].equals("POST")) {
                    rotations.add(body.getString("operation_id"));
                    handle="changed-abcdefghijklmnopqrstuv";
                }
                int status=failNext?503:200;
                failNext=false;
                byte[] response=(status==503?new JSONObject().put("detail","temporary"):
                        new JSONObject().put("installation_id",id).put("api_path","/devices/"+id)
                                .put("viewer_path","/viewer/?profile="+handle)).toString().getBytes(StandardCharsets.UTF_8);
                String header="HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
            } catch(Exception error) { if(!server.isClosed()) serverError=error; }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        assertTrue(IntakeRepository.get(context).configure(base,""));
    }

    @After public void tearDown() throws Exception {
        server.close();
        serverThread.join(2000);
        assertNull(serverError);
    }

    private PersonalConnection freshController() throws Exception {
        Constructor<PersonalConnection> constructor=PersonalConnection.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private void run(PersonalConnection controller,String rotation) throws Exception {
        Method method=PersonalConnection.class.getDeclaredMethod("run",boolean.class,String.class);
        method.setAccessible(true);
        java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            executor.submit(()-> {
                try { method.invoke(controller,false,rotation); }
                catch(Exception error) { throw new RuntimeException(error); }
            }).get(10,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test public void lostRegistrationResponseAndProcessRestartKeepOneIdentity() throws Exception {
        failNext=true;
        PersonalConnection first=freshController();
        run(first,null);
        assertEquals(25,first.progress());
        assertEquals("",IntakeRepository.get(context).backendToken());
        PersonalConnection restarted=freshController();
        run(restarted,null);
        assertEquals(100,restarted.progress());
        assertTrue("Viewer must be committed before switching the backend",
                new PersonalConnectionStore(context).load().has("viewer"));
        assertEquals(2,registrations.size());
        assertEquals(registrations.get(0),registrations.get(1));
        assertEquals(base+"/devices/"+id,IntakeRepository.get(context).backendUrl());
        assertEquals(43,IntakeRepository.get(context).backendToken().length());
        PersonalConnection updatedApk=freshController();
        run(updatedApk,null);
        assertEquals(restarted.viewerUrl(),updatedApk.viewerUrl());
        assertEquals(2,registrations.size());
    }

    @Test public void uncertainRotationRetriesSameOperationWithoutChangingWriteIdentity() throws Exception {
        PersonalConnection connection=freshController();
        run(connection,null);
        assertEquals(100,connection.progress());
        assertFalse(connection.viewerUrl().isEmpty());
        String token=IntakeRepository.get(context).backendToken();
        failNext=true;
        run(connection,"changed");
        assertEquals(25,connection.progress());
        PersonalConnection restarted=freshController();
        run(restarted,null);
        assertEquals(100,restarted.progress());
        assertEquals(2,rotations.size());
        assertEquals(rotations.get(0),rotations.get(1));
        assertTrue(restarted.viewerUrl().contains("changed-"));
        assertEquals(token,IntakeRepository.get(context).backendToken());
    }

    @Test public void existingConnectionIsNotSilentlyReplaced() throws Exception {
        assertTrue(IntakeRepository.get(context).configure(base,"existing-owner-secret"));
        PersonalConnection connection=freshController();
        run(connection,null);
        assertTrue(connection.needsClaim());
        assertEquals("existing-owner-secret",IntakeRepository.get(context).backendToken());
        assertEquals(0,registrations.size());
    }
}
