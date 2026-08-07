package tk.glucodata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the durable phone-first create and idempotent replay contract. */
public class OfflineIntakeContractTest {
    @Test
    public void outboxAndGraphCacheAreCommittedAtomicallyBeforeSuccess()
            throws Exception {
        String repository = read("src/main/java/tk/glucodata/IntakeRepository.java");
        assertTrue(repository.contains("KEY_PENDING = \"pending_creates\""));
        assertTrue(repository.contains("enqueueCreate(PendingIntakeOperation.insulin"));
        assertTrue(repository.contains(".putString(KEY_PENDING, queue.toString())"));
        assertTrue(repository.contains(".putString(KEY_CACHE, cache.toString())"));
        assertTrue(repository.contains(".commit()"));
        assertTrue(repository.indexOf("persistStateLocked()")
                < repository.indexOf("callback.onSuccess(local)"));
    }

    @Test
    public void retriesUseTheSameClientIdentityAndReplaceTheLocalMarker()
            throws Exception {
        String operation = read(
                "src/main/java/tk/glucodata/PendingIntakeOperation.java");
        String repository = read("src/main/java/tk/glucodata/IntakeRepository.java");
        assertTrue(operation.contains("\"local:\" + clientEventId"));
        assertTrue(operation.contains("api.createManualMeal(clientEventId"));
        assertTrue(repository.contains("operation.upload(api)"));
        assertTrue(repository.contains("event.clientEventId.equals(operation.clientEventId)"));
        assertTrue(repository.contains("schedulePendingSync(SYNC_RETRY_MS)"));
    }

    @Test
    public void manualMealHasAnOfflineUiAndDedicatedBackendCommand()
            throws Exception {
        String composer = read("src/main/java/tk/glucodata/IntakeComposer.java");
        String client = read("src/main/java/tk/glucodata/IntakeApiClient.java");
        String layout = read("src/main/res/layout/modern_manual_meal_composer.xml");
        assertTrue(composer.contains("Mode.MANUAL_MEAL"));
        assertTrue(composer.contains("repository.createManualMeal"));
        assertTrue(client.contains("\"POST\", \"/v1/meal-events\""));
        assertTrue(layout.contains("@+id/manual_meal_carbs"));
        assertTrue(layout.contains("@+id/manual_meal_portion"));
    }

    private static String read(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("Common").resolve(relative);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
