package tk.glucodata;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded HTTPS transfers. No API tokens, cookies, redirects to arbitrary hosts or HTTP. */
final class AppUpdateDownload {
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
    private final AtomicBoolean cancelled;
    private final Connections connections;

    AppUpdateDownload() { this(new AtomicBoolean()); }
    AppUpdateDownload(AtomicBoolean cancelled) {
        this(cancelled, url -> (HttpURLConnection) url.openConnection());
    }
    AppUpdateDownload(AtomicBoolean cancelled, Connections connections) {
        this.cancelled = cancelled;
        this.connections = connections;
    }

    private void check() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new IOException("Update transfer interrupted");
    }

    private HttpURLConnection connect(String address, long offset) throws IOException {
        for (int redirects = 0; redirects < 5; redirects++) {
            check();
            if (!AppUpdateRelease.isAllowedDownload(address))
                throw new IOException("Untrusted update location");
            HttpURLConnection connection = connections.open(new URL(address));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Juggluco-Android-Updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Missing release location");
                address = new URL(new URL(address), location).toString();
            } else return connection;
        }
        throw new IOException("Too many release redirects");
    }

    String manifest() throws IOException {
        HttpURLConnection connection = connect(AppUpdateRelease.FEED, 0);
        try {
            if (connection.getResponseCode() == 404) return null; // No published updater release yet.
            if (connection.getResponseCode() != 200) throw new IOException("Release feed unavailable");
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = input.read(buffer)) != -1;) {
                    check();
                    if (output.size() + n > 65536) throw new IOException("Release feed too large");
                    output.write(buffer, 0, n);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    File apk(AppUpdateRelease release, File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private update cache");
        File file = new File(directory, release.sha256 + ".apk");
        // Only remove obsolete files in this dedicated updater-owned directory.
        File[] old = directory.listFiles();
        if (old != null) for (File item : old)
            if (item.isFile() && !item.equals(file)) item.delete();
        if (file.length() > release.size && !file.delete()) throw new IOException("Invalid cached APK");
        long offset = file.length();
        if (offset < release.size) {
            if (directory.getUsableSpace() < (release.size - offset) + release.size + 16L * 1024 * 1024)
                throw new IOException("Not enough space for update");
            HttpURLConnection connection = connect(release.url, offset);
            try {
                int status = connection.getResponseCode();
                boolean append = offset > 0 && status == 206;
                if (append) {
                    String expected = "bytes " + offset + "-" + (release.size - 1) + "/" + release.size;
                    if (!expected.equals(connection.getHeaderField("Content-Range")))
                        throw new IOException("Invalid partial APK");
                } else if (status == 200) offset = 0;
                else throw new IOException("APK download unavailable");
                long total = offset;
                try (InputStream input = connection.getInputStream();
                        FileOutputStream output = new FileOutputStream(file, append)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        check();
                        total += n;
                        if (total > release.size) throw new IOException("APK exceeds declared size");
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
            } finally { connection.disconnect(); }
        }
        if (file.length() != release.size) throw new IOException("Incomplete APK; will resume later");
        if (!release.sha256.equals(sha256(file))) {
            file.delete();
            throw new IOException("APK integrity check failed");
        }
        return file;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}

