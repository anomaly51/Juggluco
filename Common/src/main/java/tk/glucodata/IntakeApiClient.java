package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small dependency-free HTTP client for the user-owned localhost backend. */
final class IntakeApiClient {
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int REQUEST_TIMEOUT_MS = 20_000;
    private static final int AI_TIMEOUT_MS = 120_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int LIST_PAGE_SIZE = 500;
    private static final int MAX_LIST_PAGES = 40;

    static final class ApiException extends IOException {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /** Allows the UI owner to abort a long multipart upload/read immediately. */
    static final class RequestCancellation {
        private boolean cancelled;
        private HttpURLConnection connection;

        synchronized boolean attach(HttpURLConnection value) {
            if (cancelled) {
                value.disconnect();
                return false;
            }
            connection = value;
            return true;
        }

        synchronized void detach(HttpURLConnection value) {
            if (connection == value) connection = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
            connection = null;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        void throwIfCancelled() throws IOException {
            if (isCancelled()) throw new IOException("Request cancelled");
        }
    }

    private final String baseUrl;
    private final String token;

    IntakeApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = IntakeEvent.clean(token);
    }

    JSONObject health() throws IOException, JSONException {
        return requestJson("GET", "/v1/health", null, REQUEST_TIMEOUT_MS, null);
    }

    List<IntakeEvent> list(long fromMs, long toMs)
            throws IOException, JSONException {
        Map<String, IntakeEvent> result = new LinkedHashMap<>();
        long cursor = -1L;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            String path = "/v1/intakes?from_ms=" + Math.max(0L, fromMs)
                    + "&to_ms=" + Math.max(0L, toMs)
                    + "&limit=" + LIST_PAGE_SIZE
                    + (cursor >= 0L ? "&after_sync_version=" + cursor : "");
            JSONObject response = requestJson("GET", path, null,
                    REQUEST_TIMEOUT_MS, null);
            JSONArray items = response.optJSONArray("items");
            int count = items == null ? 0 : items.length();
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (item.optBoolean("deleted", false)) {
                        result.remove(id);
                    } else {
                        IntakeEvent event = IntakeEvent.fromJson(item);
                        result.put(event.id, event);
                    }
                }
            }
            long next = response.optLong("next_sync_version", cursor);
            if (count < LIST_PAGE_SIZE) {
                return new ArrayList<>(result.values());
            }
            if (next <= cursor) {
                throw new IOException("Backend intake pagination did not advance");
            }
            cursor = next;
        }
        throw new IOException("Backend returned too many intake pages");
    }

    IntakeEvent createInsulin(IntakeDraft draft)
            throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("client_event_id", draft.clientEventId);
        request.put("occurred_at_ms", draft.occurredAtMs);
        request.put("insulin_units", draft.insulinUnits);
        request.put("insulin_name", IntakeEvent.clean(draft.insulinName));
        JSONObject response = requestJson("POST", "/v1/insulin-events",
                request, REQUEST_TIMEOUT_MS,
                draft.clientEventId);
        JSONObject event = response.optJSONObject("item");
        return IntakeEvent.fromJson(event == null ? response : event);
    }

    IntakeEvent createManualMeal(String clientEventId, long occurredAtMs,
            String mealText, float carbsGrams, Float portionGrams)
            throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("client_event_id", clientEventId);
        request.put("occurred_at_ms", occurredAtMs);
        request.put("meal_text", IntakeEvent.clean(mealText));
        request.put("carbs_g", carbsGrams);
        if (portionGrams != null) request.put("portion_g", portionGrams);
        JSONObject response = requestJson("POST", "/v1/meal-events",
                request, REQUEST_TIMEOUT_MS, clientEventId);
        JSONObject event = response.optJSONObject("item");
        return IntakeEvent.fromJson(event == null ? response : event);
    }

    MealChatSession createMealChatSession(String clientEventId,
            long occurredAtMs) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("client_event_id", clientEventId);
        request.put("occurred_at_ms", occurredAtMs);
        return MealChatSession.fromJson(requestJson("POST",
                "/v1/meal-chat/sessions", request, REQUEST_TIMEOUT_MS,
                clientEventId));
    }

    MealChatSession getMealChatSession(String sessionId)
            throws IOException, JSONException {
        return MealChatSession.fromJson(requestJson("GET",
                "/v1/meal-chat/sessions/" + sessionId, null,
                REQUEST_TIMEOUT_MS, null));
    }

    MealChatSession updateMealChatTime(String sessionId, long occurredAtMs)
            throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("occurred_at_ms", occurredAtMs);
        return MealChatSession.fromJson(requestJson("PUT",
                "/v1/meal-chat/sessions/" + sessionId + "/time",
                request, REQUEST_TIMEOUT_MS, null));
    }

    MealChatSession.Turn sendMealChatMessage(String sessionId, String text,
            List<File> photos) throws IOException, JSONException {
        String boundary = "----JugglucoMealChat" + UUID.randomUUID();
        HttpURLConnection connection = open("POST",
                "/v1/meal-chat/sessions/" + sessionId + "/messages",
                AI_TIMEOUT_MS);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(connection.getOutputStream()))) {
                String cleanText = IntakeEvent.clean(text);
                if (!cleanText.isEmpty()) {
                    writeField(output, boundary, "text", cleanText);
                }
                if (photos != null) {
                    for (File photo : photos) {
                        writeFile(output, boundary, "photos", photo,
                                "image/jpeg");
                    }
                }
                output.writeBytes("--" + boundary + "--\r\n");
                output.flush();
            }
            return MealChatSession.Turn.fromJson(readJson(connection));
        } finally {
            connection.disconnect();
        }
    }

    String transcribeAudio(File audio) throws IOException, JSONException {
        return transcribeAudio(audio, null);
    }

    String transcribeAudio(File audio, RequestCancellation cancellation)
            throws IOException, JSONException {
        if (audio == null || !audio.isFile() || audio.length() <= 0L) {
            throw new IOException("Voice recording is empty");
        }
        String boundary = "----JugglucoTranscription" + UUID.randomUUID();
        HttpURLConnection connection = open("POST", "/v1/transcriptions",
                AI_TIMEOUT_MS);
        if (cancellation != null && !cancellation.attach(connection)) {
            throw new IOException("Request cancelled");
        }
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            // On Android, disconnect() before the connection has created its
            // internal HTTP engine can be a no-op. Connect explicitly, then
            // re-check cancellation before opening or reading the audio file.
            if (cancellation != null) cancellation.throwIfCancelled();
            connection.connect();
            if (cancellation != null) cancellation.throwIfCancelled();
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(connection.getOutputStream()))) {
                writeFile(output, boundary, "audio", audio,
                        mimeForAudio(audio), cancellation);
                if (cancellation != null) cancellation.throwIfCancelled();
                output.writeBytes("--" + boundary + "--\r\n");
                output.flush();
            }
            if (cancellation != null) cancellation.throwIfCancelled();
            return IntakeEvent.clean(readJson(connection)
                    .optString("text", ""));
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }
    }

    IntakeEvent confirmMealChatSession(String sessionId)
            throws IOException, JSONException {
        JSONObject response = requestJson("POST",
                "/v1/meal-chat/sessions/" + sessionId + "/confirm",
                new JSONObject(), REQUEST_TIMEOUT_MS, null);
        JSONObject event = response.optJSONObject("item");
        return IntakeEvent.fromJson(event == null ? response : event);
    }

    JSONObject deleteIntake(String eventId)
            throws IOException, JSONException {
        String cleanId = IntakeEvent.clean(eventId);
        if (cleanId.isEmpty()) {
            throw new IOException("Intake event ID is missing");
        }
        String encoded = URLEncoder.encode(cleanId,
                StandardCharsets.UTF_8.name()).replace("+", "%20");
        return requestJson("DELETE", "/v1/intakes/" + encoded, null,
                REQUEST_TIMEOUT_MS, null);
    }

    IntakeEvent updateMealPortion(String eventId, float portionGrams)
            throws IOException, JSONException {
        String cleanId = IntakeEvent.clean(eventId);
        if (cleanId.isEmpty()) {
            throw new IOException("Intake event ID is missing");
        }
        if (!Float.isFinite(portionGrams) || portionGrams < 0.0f) {
            throw new IOException("Consumed portion is invalid");
        }
        String encoded = URLEncoder.encode(cleanId,
                StandardCharsets.UTF_8.name()).replace("+", "%20");
        JSONObject request = new JSONObject();
        request.put("portion_g", portionGrams);
        JSONObject response = requestJson("PUT", "/v1/intakes/" + encoded
                        + "/meal-portion", request, REQUEST_TIMEOUT_MS, null);
        JSONObject item = response.optJSONObject("item");
        return IntakeEvent.fromJson(item == null ? response : item);
    }

    private JSONObject requestJson(String method, String path, JSONObject body,
            int timeoutMs, String idempotencyKey)
            throws IOException, JSONException {
        HttpURLConnection connection = open(method, path, timeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            connection.setRequestProperty("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (BufferedOutputStream output = new BufferedOutputStream(
                    connection.getOutputStream())) {
                output.write(bytes);
            }
        }
        return readJson(connection);
    }

    private HttpURLConnection open(String method, String path, int timeoutMs)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Juggluco-Client", "android");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static JSONObject readJson(HttpURLConnection connection)
            throws IOException, JSONException {
        int status = connection.getResponseCode();
        InputStream raw = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = raw == null ? "" : readString(raw);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new ApiException(status, errorMessage(status, response));
        }
        if (response.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(response);
    }

    private static String readString(InputStream raw) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(raw);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Backend response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String errorMessage(int status, String response) {
        try {
            Object detail = new JSONObject(response).opt("detail");
            if (detail instanceof String && !((String) detail).trim().isEmpty()) {
                return ((String) detail).trim();
            }
        } catch (JSONException ignored) {
            // Do not expose an upstream HTML body or sensitive provider response.
        }
        return "Backend request failed (HTTP " + status + ")";
    }

    private static void writeField(DataOutputStream output, String boundary,
            String name, String value) throws IOException {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private static void writeFile(DataOutputStream output, String boundary,
            String field, File file, String mime) throws IOException {
        writeFile(output, boundary, field, file, mime, null);
    }

    private static void writeFile(DataOutputStream output, String boundary,
            String field, File file, String mime,
            RequestCancellation cancellation) throws IOException {
        if (file == null || !file.isFile()) {
            return;
        }
        if (cancellation != null) cancellation.throwIfCancelled();
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + safeFileName(file.getName()) + "\"\r\n");
        output.writeBytes("Content-Type: " + mime + "\r\n\r\n");
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(file))) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (cancellation != null) cancellation.throwIfCancelled();
                output.write(buffer, 0, count);
            }
        }
        if (cancellation != null) cancellation.throwIfCancelled();
        output.writeBytes("\r\n");
    }

    private static String safeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String mimeForAudio(File audio) {
        if (audio == null) {
            return "audio/mp4";
        }
        String name = audio.getName().toLowerCase();
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".aac")) return "audio/aac";
        return "audio/mp4";
    }
}
