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
    public void createIsDurableOnPhoneBeforeBackgroundSynchronization()
            throws Exception {
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        String create = methodBody(repository,
                "void createInsulin(IntakeDraft draft",
                "private IntakeApiClient client");

        assertTrue("Intake create must enter the durable phone outbox",
                create.contains("enqueueCreate(PendingIntakeOperation.insulin"));
        assertTrue("Outbox and graph cache must be committed together",
                repository.contains("persistStateLocked()")
                        && repository.contains(".commit()"));
        assertTrue("Pending commands must later reach the backend",
                repository.contains("operation.upload(api)"));
        assertTrue("Backend acknowledgement must replace the pending marker",
                repository.contains("acknowledgeCreate(operation, confirmed)"));
        assertFalse("The local-first flow must not contain a native fallback",
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
    public void backendUrlAllowsPrivateWifiButStillRequiresHttpsOnTheInternet()
            throws Exception {
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        assertTrue(repository.contains("isPrivateIpv4(host)"));
        assertTrue(repository.contains("octets[0] == 10"));
        assertTrue(repository.contains("octets[0] == 172"));
        assertTrue(repository.contains("octets[0] == 192 && octets[1] == 168"));
        assertTrue(repository.contains("Use HTTPS for a non-local backend URL"));
    }

    @Test
    public void voiceTextAndPhotosUseOneCancellableBackendTurn()
            throws Exception {
        String client = mainJava("tk/glucodata/IntakeApiClient.java");
        String chat = methodBody(client,
                "IntakeChatTurn sendIntakeChatTurn",
                "IntakeChatTurn undoIntakeChatAction");
        String repository = mainJava("tk/glucodata/IntakeRepository.java");
        String composer = mainJava("tk/glucodata/IntakeComposer.java");

        assertTrue(chat.contains("/v1/intake-chat/sessions/"));
        assertTrue(chat.contains("+ encodedSession + \"/turns\""));
        assertTrue(chat.contains("\"client_turn_id\""));
        assertTrue(chat.contains("\"occurred_at_ms\""));
        assertTrue(chat.contains("for (File photo : photos)"));
        assertTrue(chat.contains("\"photos\", photo"));
        assertTrue(chat.contains("\"audio\", audio"));
        assertTrue("Cancellation must be checked before connecting",
                chat.indexOf("cancellation.throwIfCancelled()")
                        < chat.indexOf("connection.connect()"));
        assertTrue("Cancellation must be checked after connecting",
                chat.indexOf("connection.connect()")
                        < chat.lastIndexOf(
                                "cancellation.throwIfCancelled()"));
        assertTrue("Multipart audio writes must be cancellation-aware",
                client.contains("writeFile(output, boundary, \"audio\", audio,")
                        && client.contains("mimeForAudio(audio), cancellation)"));
        assertTrue("A new composer must not delete fresh in-flight audio",
                composer.contains("STALE_MEDIA_AGE_MS")
                        && composer.contains("file.lastModified() <= cutoff"));
        assertTrue(repository.contains("intakeChatExecutor.submit"));
        assertTrue(repository.contains("RequestCancellation cancellation"));
        assertTrue(composer.contains(
                "repository.sendIntakeChat(intakeSessionId"));
        assertTrue(composer.contains("sendIntakeTurn(completed)"));
        assertFalse("Closing the surface must not hide a possible backend commit",
                composer.contains("intakeTurnCall.cancel()"));
        assertTrue("A completed receipt must merge before UI cancellation is observed",
                repository.contains("mergeIntakeChatTurn(result)")
                        && repository.contains("cancellation.isCancelled()"));
        assertFalse("Raw voice must not remain a chat attachment",
                composer.contains("pendingAudio"));
        assertTrue("The primary turn must accept raw voice in one round-trip",
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
        assertTrue(repository.contains("reconcileFreshEvents(fresh)"));
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
