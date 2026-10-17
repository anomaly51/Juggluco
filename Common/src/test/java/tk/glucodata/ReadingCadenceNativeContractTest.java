package tk.glucodata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guard for the bounded current-sensor cadence bridge. */
public class ReadingCadenceNativeContractTest {
    @Test
    public void javaAndJniExposeBoundedCurrentSensorTimestamps()
            throws Exception {
        String natives=source("src","main","java","tk","glucodata",
                "Natives.java");
        String bridge=source("src","main","cpp","watch",
                "watchvalue.cpp");

        assertTrue(natives.contains(
                "long[] recentCurrentSensorReadingTimes(int limit)"));
        String method=between(bridge,
                "fromjava(recentCurrentSensorReadingTimes)",
                "int getglucosestr");
        assertTrue(method.contains(
                "std::clamp<int>(requestedLimit,0,maximumLimit)"));
        assertTrue(method.contains("maximumLimit=256"));
        assertTrue(method.contains("getlaststream(maxwatchage)"));
        assertTrue(method.contains("hist->getPolldata()"));
        assertTrue(method.contains("poll.valid(0)"));
        assertTrue(method.contains("static_cast<jlong>(poll.t)*1000LL"));
        assertInOrder(method,
                "std::sort(timestamps.begin(),timestamps.end())",
                "std::unique(timestamps.begin(),timestamps.end())",
                "timestamps.size()>static_cast<size_t>(limit)",
                "timestamps.begin()+timestamps.size()-limit");
        assertTrue(!method.contains("std::reverse(timestamps.begin()"));
    }

    private static String source(String... parts) throws Exception {
        Path path=Paths.get(parts[0],java.util.Arrays.copyOfRange(
                parts,1,parts.length));
        if(!Files.exists(path)) path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String between(String value,String start,String end) {
        int first=value.indexOf(start);
        int last=value.indexOf(end,first);
        assertTrue(first>=0&&last>first);
        return value.substring(first,last);
    }

    private static void assertInOrder(String value,String... snippets) {
        int previous=-1;
        for(String snippet:snippets) {
            int current=value.indexOf(snippet,previous+1);
            assertTrue("Missing or out-of-order snippet: "+snippet,
                    current>previous);
            previous=current;
        }
    }
}
