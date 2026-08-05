package tk.glucodata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;

/** Dependency-free HTTP transport for glucose forecasting endpoints. */
final class ForecastApiClient {
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int REQUEST_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final String baseUrl;
    private final String token;

    ForecastApiClient(String baseUrl, String token) {
        this.baseUrl = IntakeRepository.normalizeBackendUrl(baseUrl);
        this.token = token == null ? "" : token.trim();
    }

    JSONObject uploadReadings(List<ForecastReading> readings)
            throws IOException, JSONException {
        return uploadReadings(readings, null);
    }

    JSONObject uploadReadings(List<ForecastReading> readings,
            Boolean backfillComplete) throws IOException, JSONException {
        boolean includeReadingUtcOffsets = true;
        Boolean effectiveBackfillComplete = backfillComplete;
        while (true) {
            try {
                return request("POST", "/v1/glucose/readings",
                        readingsBody(readings, effectiveBackfillComplete,
                                includeReadingUtcOffsets));
            } catch (BackendHttpException error) {
                // Older backends only know the batch timezone and may also
                // predate the history boundary. Retry solely when their 422
                // response explicitly rejects either new field.
                boolean retry = false;
                if (includeReadingUtcOffsets
                        && error.unsupportedReadingUtcOffset) {
                    includeReadingUtcOffsets = false;
                    retry = true;
                }
                if (effectiveBackfillComplete != null
                        && error.unsupportedBackfillComplete) {
                    effectiveBackfillComplete = null;
                    retry = true;
                }
                if (!retry) throw error;
                if ((readings == null || readings.isEmpty())
                        && effectiveBackfillComplete == null) {
                    return new JSONObject();
                }
            }
        }
    }

    static JSONObject readingsBody(List<ForecastReading> readings,
            Boolean backfillComplete) throws JSONException {
        return readingsBody(readings, backfillComplete, true);
    }

    static JSONObject readingsBody(List<ForecastReading> readings,
            Boolean backfillComplete, boolean includeReadingUtcOffsets)
            throws JSONException {
        JSONObject body = new JSONObject();
        JSONArray values = new JSONArray();
        ForecastReading firstReading = null;
        if (readings != null) {
            for (ForecastReading reading : readings) {
                if (reading != null) {
                    if (firstReading == null) firstReading = reading;
                    values.put(reading.toJson(includeReadingUtcOffsets));
                }
            }
        }
        body.put("readings", values);
        int batchUtcOffsetMinutes = firstReading == null
                ? TimeZone.getDefault().getOffset(
                        System.currentTimeMillis()) / 60_000
                : firstReading.utcOffsetMinutes;
        body.put("utc_offset_minutes", batchUtcOffsetMinutes);
        if (backfillComplete != null) {
            body.put("backfill_complete", backfillComplete.booleanValue());
        }
        return body;
    }

    ForecastSnapshot currentForecast() throws IOException, JSONException {
        return ForecastSnapshot.fromJson(request(
                "GET", "/v1/forecast/current", null));
    }

    ForecastModelStatus forecastStatus() throws IOException, JSONException {
        return ForecastModelStatus.fromJson(request(
                "GET", "/v1/forecast/status", null));
    }

    private JSONObject request(String method, String path, JSONObject body)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                baseUrl + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(REQUEST_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Juggluco-Client", "android");
            if (!token.isEmpty()) {
                connection.setRequestProperty(
                        "Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (BufferedOutputStream output = new BufferedOutputStream(
                        connection.getOutputStream())) {
                    output.write(bytes);
                }
            }
            return readJson(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(HttpURLConnection connection)
            throws IOException, JSONException {
        int status = connection.getResponseCode();
        InputStream raw = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = raw == null ? "" : readString(raw);
        if (status < 200 || status >= 300) {
            throw new BackendHttpException(status, response);
        }
        return response.trim().isEmpty()
                ? new JSONObject() : new JSONObject(response);
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
            if (detail instanceof String
                    && !((String) detail).trim().isEmpty()) {
                return ((String) detail).trim();
            }
        } catch (JSONException ignored) {
            // Never surface an upstream HTML body or provider response.
        }
        return "Backend request failed (HTTP " + status + ")";
    }

    private static final class BackendHttpException extends IOException {
        final boolean unsupportedBackfillComplete;
        final boolean unsupportedReadingUtcOffset;

        BackendHttpException(int status, String response) {
            super(errorMessage(status, response));
            unsupportedBackfillComplete = status == 422
                    && response != null
                    && response.contains("backfill_complete")
                    && response.contains("extra_forbidden");
            unsupportedReadingUtcOffset = status == 422
                    && response != null
                    && response.contains("utc_offset_minutes")
                    && response.contains("readings")
                    && response.contains("extra_forbidden");
        }
    }
}
