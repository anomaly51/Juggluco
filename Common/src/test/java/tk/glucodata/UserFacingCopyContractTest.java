package tk.glucodata;

import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/** Keeps implementation jargon and AI marketing out of user-visible copy. */
public class UserFacingCopyContractTest {
    private static final Pattern XML_TEXT = Pattern.compile(">([^<]+)<");
    private static final Pattern FORBIDDEN_COPY = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:AI|ИИ)(?![\\p{L}\\p{N}_])"
                    + "|\\blearning\\b|\\blearned\\b|\\btraining\\b"
                    + "|\\btrained\\b|обуч|переобуч");

    @Test
    public void resourcesDoNotExposeAiLearningMarketing() throws Exception {
        Path resources = Paths.get("src", "main", "res");
        if (!Files.isDirectory(resources)) {
            resources = Paths.get("Common").resolve(resources);
        }
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".xml"))::iterator) {
                String xml = new String(Files.readAllBytes(file),
                        StandardCharsets.UTF_8);
                Matcher text = XML_TEXT.matcher(xml);
                while (text.find()) {
                    Matcher forbidden = FORBIDDEN_COPY.matcher(text.group(1));
                    if (forbidden.find()) {
                        fail(file + " exposes unnecessary AI/training copy: "
                                + forbidden.group());
                    }
                }
            }
        }
    }
}
