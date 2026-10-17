package tk.glucodata;

import android.app.Application;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdateDownloadTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final class Connection extends HttpURLConnection {
        int code; byte[] body;
        Map<String,String> headers = new HashMap<>();
        Connection(int code, byte[] body) throws Exception { super(new URL(AppUpdateRelease.FEED)); this.code=code; this.body=body; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }
    AppUpdateDownload transfer(Connection... connections) {
        Queue<Connection> responses = new ArrayDeque<>(Arrays.asList(connections));
        return new AppUpdateDownload(new AtomicBoolean(), url -> responses.remove());
    }
    AppUpdateRelease release(byte[] data) throws Exception {
        File source=temp.newFile();
        Files.write(source.toPath(), data);
        JSONObject b=AppUpdateReleaseTest.build().put("size", data.length)
                .put("sha256", AppUpdateDownload.sha256(source));
        return AppUpdateReleaseTest.select(AppUpdateReleaseTest.feed(b));
    }
    @Test public void missingReleaseIsNormal() throws Exception {
        assertNull(transfer(new Connection(404, new byte[0])).manifest());
    }
    @Test public void boundsManifestSize() throws Exception {
        assertThrows(IOException.class, () -> transfer(new Connection(200, new byte[65537])).manifest());
    }
    @Test public void neverFollowsRedirectToUnknownHost() throws Exception {
        Connection c=new Connection(302,new byte[0]); c.headers.put("Location","https://evil.example/key");
        assertThrows(IOException.class, () -> transfer(c).manifest());
    }
    @Test public void cancellationPreventsNetworkRequests() {
        AppUpdateDownload download=new AppUpdateDownload(new AtomicBoolean(true), url -> { fail("Network"); return null; });
        assertThrows(IOException.class, download::manifest);
    }
    @Test public void resumesAndVerifiesWholeFile() throws Exception {
        byte[] content="a synthetic APK payload".getBytes(StandardCharsets.UTF_8);
        AppUpdateRelease r=release(content);
        File directory=temp.newFolder();
        File partial=new File(directory,r.sha256+".apk");
        Files.write(partial.toPath(), Arrays.copyOf(content,4));
        Connection c=new Connection(206,Arrays.copyOfRange(content,4,content.length));
        c.headers.put("Content-Range","bytes 4-"+(content.length-1)+"/"+content.length);
        assertArrayEquals(content, Files.readAllBytes(transfer(c).apk(r,directory).toPath()));
        assertEquals("bytes=4-", c.getRequestProperty("Range"));
    }
    @Test public void serverIgnoringRangeRestartsInsteadOfAppending() throws Exception {
        byte[] content="synthetic payload".getBytes(StandardCharsets.UTF_8);
        AppUpdateRelease r=release(content);
        File directory=temp.newFolder();
        Files.write(new File(directory,r.sha256+".apk").toPath(), new byte[]{1,2});
        assertArrayEquals(content, Files.readAllBytes(transfer(new Connection(200,content)).apk(r,directory).toPath()));
    }
    @Test public void rejectsWrongRangeAndBadHash() throws Exception {
        byte[] content="synthetic".getBytes(StandardCharsets.UTF_8);
        AppUpdateRelease r=release(content);
        File directory=temp.newFolder();
        File partial=new File(directory,r.sha256+".apk");
        Files.write(partial.toPath(),new byte[]{1});
        assertThrows(IOException.class, () -> transfer(new Connection(206,new byte[0])).apk(r,directory));
        assertThrows(IOException.class, () -> transfer(new Connection(200,new byte[content.length])).apk(r,directory));
        assertFalse(partial.exists());
    }
    @Test public void completeVerifiedCacheNeedsNoNetwork() throws Exception {
        byte[] content="synthetic".getBytes(StandardCharsets.UTF_8);
        AppUpdateRelease r=release(content);
        File directory=temp.newFolder();
        Files.write(new File(directory,r.sha256+".apk").toPath(),content);
        assertArrayEquals(content,Files.readAllBytes(transfer().apk(r,directory).toPath()));
    }
    @Test public void reportsResumedBytesAndCompletionIncludingCachedFile() throws Exception {
        byte[] data = "a synthetic progress payload".getBytes(StandardCharsets.UTF_8);
        AppUpdateRelease r = release(data);
        File directory = temp.newFolder();
        Files.write(new File(directory,r.sha256+".apk").toPath(),Arrays.copyOf(data,4));
        Connection response = new Connection(206,Arrays.copyOfRange(data,4,data.length));
        response.headers.put("Content-Range","bytes 4-"+(data.length-1)+"/"+data.length);
        List<Long> progress = new ArrayList<>();
        new AppUpdateDownload(new AtomicBoolean(), url -> response, (bytes,total) -> {
            assertEquals(data.length,total);
            progress.add(bytes);
        }).apk(r,directory);
        assertEquals(4L,(long)progress.get(0));
        assertEquals(data.length,(long)progress.get(progress.size()-1));
        progress.clear();
        new AppUpdateDownload(new AtomicBoolean(), url -> { fail("Unexpected request"); return null; },
                (bytes,total) -> progress.add(bytes)).apk(r,directory);
        assertEquals(Arrays.asList((long)data.length),progress);
    }
}

