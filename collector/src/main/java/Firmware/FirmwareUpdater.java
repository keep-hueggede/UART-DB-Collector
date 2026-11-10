package Firmware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the sensor firmware on the receiver (ESP32-C6) up to date: fetches the
 * newest GitHub release of the sensor firmware, verifies its SHA-256 and sends
 * it over the UART line by line ("FW BEGIN/DATA/END", each answered with a
 * {"fw":...} line). The receiver offers it to the sensors, which update
 * themselves over Thread.
 */
public class FirmwareUpdater {

    /** Writes one command line to the receiver. */
    public interface Link {
        void send(String line);
    }

    public enum Result { UP_TO_DATE, UPLOADED, NO_RELEASE, RECEIVER_UNAVAILABLE }

    private static final int CHUNK = 384;            // 512 base64 chars per line
    private static final int RETRIES = 3;
    private static final long END_TIMEOUT_MS = 30_000;

    private final Link link;
    private final String repo;
    private final String asset;
    private final String apiUrl;
    private final String downloadUrl;
    private final Path dir;
    private final long replyTimeoutMs;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<JsonNode> replies = new LinkedBlockingQueue<>();
    // sensor firmware the receiver holds, as last reported (null = unknown)
    private volatile String receiverFirmware;

    public FirmwareUpdater(Properties p, Link link) {
        this.link = link;
        this.repo = p.getProperty("FIRMWARE.REPO", "keep-hueggede/ESP12-BME").trim();
        this.asset = p.getProperty("FIRMWARE.ASSET", "esp-bme.bin").trim();
        this.apiUrl = p.getProperty("FIRMWARE.API_URL", "https://api.github.com").trim();
        this.downloadUrl = p.getProperty("FIRMWARE.DOWNLOAD_URL", "https://github.com").trim();
        this.dir = Path.of(p.getProperty("FIRMWARE.DIR", "./firmware").trim());
        this.replyTimeoutMs = Long.parseLong(p.getProperty("FIRMWARE.REPLY_TIMEOUT_MS", "5000").trim());
    }

    public String receiverFirmware() {
        return receiverFirmware;
    }

    public static boolean enabled(Properties p) {
        return Boolean.parseBoolean(p.getProperty("FIRMWARE.ENABLED", "true").trim());
    }

    public static long checkHours(Properties p) {
        return Math.max(1, Long.parseLong(p.getProperty("FIRMWARE.CHECK_HOURS", "6").trim()));
    }

    /** Called for every UART line that starts with {"fw". */
    public void onReply(String line) {
        try {
            JsonNode node = json.readTree(line);
            if (node != null && node.has("fw")) replies.offer(node);
        } catch (IOException ignored) {
            // garbled line: the waiting command times out and is retried
        }
    }

    /** One update cycle: newest release -> receiver, if it does not have it yet. */
    public synchronized Result check() throws IOException, InterruptedException {
        Release release = latestRelease();
        if (release == null) return Result.NO_RELEASE;
        byte[] image = image(release);
        String sha = sha256(image);

        JsonNode status = command("FW STATUS", "status", replyTimeoutMs);
        if (status == null) return Result.RECEIVER_UNAVAILABLE;
        String held = status.path("version").asText("");
        receiverFirmware = held.isEmpty() ? null : held;
        if (release.version.equals(status.path("version").asText()) && sha.equals(status.path("sha256").asText())) {
            return Result.UP_TO_DATE;
        }
        long capacity = status.path("capacity").asLong(0);
        if (capacity > 0 && image.length > capacity) {
            throw new IOException("image (" + image.length + " bytes) does not fit the receiver (" + capacity + ")");
        }

        System.out.println("Firmware: sende " + release.version + " (" + image.length + " Bytes) an den Empfaenger");
        long started = System.currentTimeMillis();
        expect(command("FW BEGIN " + release.version + " " + image.length + " " + sha, "ready", replyTimeoutMs), "BEGIN");
        for (int off = 0; off < image.length; off += CHUNK) {
            int len = Math.min(CHUNK, image.length - off);
            String line = "FW DATA " + off + " " + Base64.getEncoder().encodeToString(slice(image, off, len));
            JsonNode ack = null;
            for (int attempt = 0; attempt < RETRIES && ack == null; attempt++) {
                ack = command(line, "ack", replyTimeoutMs);
            }
            expect(ack, "DATA at " + off);
            if (ack.path("off").asLong() != off + len) {
                throw new IOException("receiver acknowledged " + ack.path("off").asLong() + ", expected " + (off + len));
            }
        }
        expect(command("FW END", "stored", END_TIMEOUT_MS), "END");
        receiverFirmware = release.version;
        System.out.printf("Firmware: %s auf dem Empfaenger gespeichert (%d s), wird den Sensoren angeboten%n",
                release.version, (System.currentTimeMillis() - started) / 1000);
        return Result.UPLOADED;
    }

    // ----------------------------------------------------------------- GitHub

    private record Release(String tag, String version) {}

    private Release latestRelease() throws IOException, InterruptedException {
        HttpResponse<String> res = get(apiUrl + "/repos/" + repo + "/releases/latest", HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null;
        if (res.statusCode() != 200) throw new IOException("GitHub API answered HTTP " + res.statusCode());
        String tag = json.readTree(res.body()).path("tag_name").asText("");
        if (!tag.matches("v?[0-9A-Za-z.\\-]{1,30}")) throw new IOException("unexpected release tag: " + tag);
        return new Release(tag, tag.startsWith("v") ? tag.substring(1) : tag);
    }

    /** Release image, cached in FIRMWARE.DIR and always checked against the release's SHA256SUMS. */
    private byte[] image(Release release) throws IOException, InterruptedException {
        String base = downloadUrl + "/" + repo + "/releases/download/" + release.tag + "/";
        HttpResponse<String> sums = get(base + "SHA256SUMS", HttpResponse.BodyHandlers.ofString());
        if (sums.statusCode() != 200) throw new IOException("SHA256SUMS: HTTP " + sums.statusCode());
        String expected = null;
        for (String l : sums.body().split("\n")) {
            String[] parts = l.trim().split("\\s+\\*?");
            if (parts.length == 2 && parts[1].equals(asset)) expected = parts[0].toLowerCase();
        }
        if (expected == null) throw new IOException(asset + " missing in SHA256SUMS");

        Path cached = dir.resolve(release.version + "-" + asset);
        if (Files.isRegularFile(cached)) {
            byte[] data = Files.readAllBytes(cached);
            if (sha256(data).equals(expected)) return data;
        }
        HttpResponse<byte[]> res = get(base + asset, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException(asset + ": HTTP " + res.statusCode());
        if (!sha256(res.body()).equals(expected)) throw new IOException(asset + ": checksum mismatch");
        Files.createDirectories(dir);
        Files.write(cached, res.body());
        return res.body();
    }

    private <T> HttpResponse<T> get(String url, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json, */*")
                .header("User-Agent", "uart-db-collector")
                .GET().build();
        return http.send(req, handler);
    }

    // --------------------------------------------------------------- receiver

    /** Sends one command and waits for the reply of the given type (null on timeout). */
    private JsonNode command(String line, String type, long timeoutMs) throws IOException, InterruptedException {
        replies.clear();
        link.send(line + "\n");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            JsonNode reply = left > 0 ? replies.poll(left, TimeUnit.MILLISECONDS) : null;
            if (reply == null) return null;
            String got = reply.path("fw").asText();
            if (got.equals(type)) return reply;
            if (got.equals("error")) throw new IOException("receiver: " + reply.path("msg").asText());
        }
    }

    private static void expect(JsonNode reply, String step) throws IOException {
        if (reply == null) throw new IOException("receiver did not answer " + step);
    }

    private static byte[] slice(byte[] data, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
