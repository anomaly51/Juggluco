package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Guards the backend-owned intake flow and its phone media contract. */
public class IntakeFlowContractTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    private static final Pattern LEGACY_SAVE_NUM = Pattern.compile(
            "\\bNatives\\s*\\.\\s*saveNum\\s*\\(");

    @Test
    public void createIsBackendConfirmedBeforeTheLocalGraphCacheChanges()
            throws Exception {
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        String create = methodBody(repository,
                "void createInsulin(IntakeDraft draft",
                "private IntakeApiClient client");

        assertTrue("Intake create must call the backend",
                create.contains("api -> api.createInsulin(draft)"));
        assertTrue("Local graph state may change only in the checked success callback",
                create.contains("onSuccess(IntakeEvent created)")
                        && create.contains("mergeConfirmedEvent(created)"));
        assertTrue("Backend changes must invalidate in-flight results",
                create.contains("executeForCurrentBackend"));
        assertFalse("The backend-first flow must not contain a native fallback",
                create.contains("Natives."));
    }

    @Test
    public void createUsesAStableIdempotencyKeyAndBackendEndpoint()
            throws Exception {
        String draft = mainJava("tk/glucodata/IntakeDraft.java");
        String client = mainJava("tk/glucodata/IntakeApiClient.java");

        assertTrue(draft.contains("clientEventId = UUID.randomUUID().toString()"));
        assertTrue(client.contains("request.put(\"client_event_id\", draft.clientEventId)"));
        assertTrue(client.contains("\"POST\", \"/v1/insulin-events\""));
        assertFalse(client.contains("\"POST\", \"/v1/intakes\""));
        assertTrue(client.contains("draft.clientEventId"));
        assertTrue(client.contains("\"Idempotency-Key\", idempotencyKey"));
    }

    @Test
    public void mealPhotosAndEditableVoiceTextUseOnlyTheBackend()
            throws Exception {
        String client = mainJava("tk/glucodata/IntakeApiClient.java");
        String chat = methodBody(client,
                "MealChatSession.Turn sendMealChatMessage",
                "String transcribeAudio");
        String transcription = methodBody(client,
                "String transcribeAudio",
                "IntakeEvent confirmMealChatSession");
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        String composer = mainJava("tk/glucodata/IntakeComposer.java");

        assertTrue(chat.contains("+ sessionId + \"/messages\""));
        assertTrue(chat.contains("for (File photo : photos)"));
        assertTrue(chat.contains("\"photos\", photo"));
        assertTrue(transcription.contains("\"POST\", \"/v1/transcriptions\""));
        assertTrue(transcription.contains("\"audio\", audio"));
        assertTrue(transcription.contains("optString(\"text\", \"\")"));
        assertTrue("Cancellation must be checked before connecting",
                transcription.indexOf("cancellation.throwIfCancelled()")
                        < transcription.indexOf("connection.connect()"));
        assertTrue("Cancellation must be checked after connecting",
                transcription.indexOf("connection.connect()")
                        < transcription.lastIndexOf(
                                "cancellation.throwIfCancelled()"));
        assertTrue("Multipart audio writes must be cancellation-aware",
                client.contains("writeFile(output, boundary, \"audio\", audio,")
                        && client.contains("mimeForAudio(audio), cancellation)"));
        assertTrue("Upload worker owns temporary audio cleanup",
                repository.contains("finally {")
                        && repository.contains("audio.delete()"));
        assertTrue("A new composer must not delete fresh in-flight audio",
                composer.contains("STALE_MEDIA_AGE_MS")
                        && composer.contains("file.lastModified() <= cutoff"));
        assertTrue(repository.contains(
                "api.transcribeAudio(audio, cancellation)"));
        assertTrue(repository.contains("transcriptionExecutor.submit"));
        assertTrue(repository.contains("RequestCancellation cancellation"));
        assertTrue(composer.contains("repository.transcribeAudio(audio"));
        assertTrue(composer.contains("transcriptionCall.cancel()"));
        assertTrue(composer.contains("insertEditableTranscript(transcript)"));
        assertTrue(composer.contains(
                "repository.sendMealChat(mealSessionId, text, photos,"));
        assertFalse("Raw voice must not remain a chat attachment",
                composer.contains("pendingAudio"));
        assertFalse("The typed meal-chat API must not accept raw audio",
                chat.contains("File audio"));
    }

    @Test
    public void newIntakeClassesNeverUseTheLegacySaveNumPath()
            throws Exception {
        Path directory = commonPath(Paths.get(
                "src", "main", "java", "tk", "glucodata"));
        int checked = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
                directory, "Intake*.java")) {
            for (Path file : files) {
                checked++;
                String source = read(file);
                assertFalse(file.getFileName()
                                + " must persist through IntakeApiClient, not Natives.saveNum",
                        LEGACY_SAVE_NUM.matcher(source).find());
            }
        }
        assertTrue("Expected the new Intake Java layer", checked >= 4);
    }

    @Test
    public void backendChangesCannotPublishOldHistoryOrConfirmedEvents()
            throws Exception {
        String repository = mainJava("tk/glucodata/IntakeRepository.java");

        assertTrue(repository.contains("volatile long configurationGeneration"));
        assertTrue(repository.contains("generation != configurationGeneration"));
        assertTrue(repository.contains("CONFIGURATION_CHANGED"));
        assertTrue(repository.contains("onSuccess(List<IntakeEvent> fresh)"));
        assertTrue(repository.contains("replaceEvents(fresh)"));
        assertTrue(repository.contains("onSuccess(IntakeEvent confirmed)"));
        assertTrue(repository.contains("mergeConfirmedEvent(confirmed)"));
    }

    @Test
    public void phoneManifestDeclaresVoiceAndScopedPhotoSharing()
            throws Exception {
        Document manifest = xml(commonPath(Paths.get(
                "src", "mobile", "AndroidManifest.xml")));

        assertTrue(hasAndroidAttribute(manifest.getElementsByTagName(
                        "uses-permission"), "name",
                "android.permission.RECORD_AUDIO"));

        Element provider = elementWithAndroidAttribute(
                manifest.getElementsByTagName("provider"),
                "name", "androidx.core.content.FileProvider");
        assertNotNull("The phone build needs a FileProvider for camera output",
                provider);
        assertEquals("${applicationId}.intake.files",
                androidAttribute(provider, "authorities"));
        assertEquals("false", androidAttribute(provider, "exported"));
        assertEquals("true", androidAttribute(provider, "grantUriPermissions"));

        Element pathsMetadata = elementWithAndroidAttribute(
                provider.getElementsByTagName("meta-data"),
                "name", "android.support.FILE_PROVIDER_PATHS");
        assertNotNull(pathsMetadata);
        assertEquals("@xml/intake_file_paths",
                androidAttribute(pathsMetadata, "resource"));

        Path pathsFile = commonPath(Paths.get(
                "src", "mobile", "res", "xml", "intake_file_paths.xml"));
        assertTrue("The FileProvider metadata must resolve to a paths resource",
                Files.isRegularFile(pathsFile));
        assertEquals("paths", xml(pathsFile).getDocumentElement().getTagName());
    }

    private static String methodBody(String source, String startToken,
            String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue("Missing method contract: " + startToken, start >= 0);
        assertTrue("Missing method boundary after: " + startToken, end > start);
        return source.substring(start, end);
    }

    private static boolean hasAndroidAttribute(NodeList nodes, String name,
            String value) {
        return elementWithAndroidAttribute(nodes, name, value) != null;
    }

    private static Element elementWithAndroidAttribute(NodeList nodes,
            String name, String value) {
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element
                    && value.equals(androidAttribute((Element) node, name))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NAMESPACE, name);
    }

    private static Document xml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static String mainJava(String relative) throws IOException {
        return read(commonPath(Paths.get("src", "main", "java")
                .resolve(relative)));
    }

    private static Path commonPath(Path relative) {
        return Files.exists(relative) ? relative : Paths.get("Common").resolve(relative);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
