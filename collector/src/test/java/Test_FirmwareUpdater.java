import Firmware.FirmwareUpdater;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release download and the UART upload protocol, against a stub of GitHub and
 * a simulated receiver that behaves like ESP-ThreadReceiver's handlePiCommand.
 */
public class Test_FirmwareUpdater {

    private HttpServer github;
    private Path dir;
    private byte[] image;
    private String sums;
    private boolean released = true;
    private FakeReceiver receiver;
    private FirmwareUpdater updater;

    @BeforeEach
    public void setUp() throws Exception {
        image = new byte[5000];
        new Random(1).nextBytes(image);
        sums = sha256(image) + "  esp-bme.bin\n" + "0".repeat(64) + "  esp-bme-full.bin\n";

        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/me/fw/releases/latest", ex ->
                reply(ex, released ? 200 : 404, "{\"tag_name\":\"v1.2.0\"}".getBytes(StandardCharsets.UTF_8)));
        github.createContext("/me/fw/releases/download/v1.2.0/SHA256SUMS", ex ->
                reply(ex, 200, sums.getBytes(StandardCharsets.UTF_8)));
        github.createContext("/me/fw/releases/download/v1.2.0/esp-bme.bin", ex -> reply(ex, 200, image));
        github.start();

        dir = Files.createTempDirectory("fw");
        String base = "http://127.0.0.1:" + github.getAddress().getPort();
        Properties p = new Properties();
        p.setProperty("FIRMWARE.REPO", "me/fw");
        p.setProperty("FIRMWARE.API_URL", base);
        p.setProperty("FIRMWARE.DOWNLOAD_URL", base);
        p.setProperty("FIRMWARE.DIR", dir.toString());
        p.setProperty("FIRMWARE.REPLY_TIMEOUT_MS", "300");
        receiver = new FakeReceiver();
        updater = new FirmwareUpdater(p, receiver);
        receiver.updater = updater;
    }

    @AfterEach
    public void tearDown() {
        github.stop(0);
    }

    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    public void uploadsNewReleaseToReceiver() throws Exception {
        assertEquals(FirmwareUpdater.Result.UPLOADED, updater.check());
        assertEquals("1.2.0", receiver.storedVersion);
        assertArrayEquals(image, receiver.stored);
        assertTrue(Files.exists(dir.resolve("1.2.0-esp-bme.bin")), "image is cached on the Pi");
        assertEquals(14, receiver.dataLines, "5000 bytes in 384-byte chunks");
    }

    @Test
    public void receiverWithCurrentImageIsLeftAlone() throws Exception {
        updater.check();
        receiver.commands.clear();
        assertEquals(FirmwareUpdater.Result.UP_TO_DATE, updater.check());
        assertEquals(List.of("FW STATUS"), receiver.commands);
    }

    @Test
    public void checksumMismatchUploadsNothing() {
        sums = "0".repeat(64) + "  esp-bme.bin\n";
        IOException e = assertThrows(IOException.class, () -> updater.check());
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertTrue(receiver.commands.isEmpty());
        assertFalse(Files.exists(dir.resolve("1.2.0-esp-bme.bin")));
    }

    @Test
    public void lostAckIsRetried() throws Exception {
        receiver.dropAckAt = 768;
        assertEquals(FirmwareUpdater.Result.UPLOADED, updater.check());
        assertArrayEquals(image, receiver.stored);
    }

    @Test
    public void receiverErrorAbortsUpload() {
        receiver.failEnd = true;
        IOException e = assertThrows(IOException.class, () -> updater.check());
        assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
        assertNull(receiver.storedVersion);
    }

    @Test
    public void silentReceiverIsReported() throws Exception {
        receiver.silent = true;
        assertEquals(FirmwareUpdater.Result.RECEIVER_UNAVAILABLE, updater.check());
    }

    @Test
    public void noReleaseYet() throws Exception {
        released = false;
        assertEquals(FirmwareUpdater.Result.NO_RELEASE, updater.check());
        assertTrue(receiver.commands.isEmpty());
    }

    @Test
    public void imageTooLargeForReceiver() {
        receiver.capacity = 1000;
        IOException e = assertThrows(IOException.class, () -> updater.check());
        assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
    }

    @Test
    public void releaseConfigDefaults() {
        assertTrue(FirmwareUpdater.enabled(new Properties()));
        assertEquals(6, FirmwareUpdater.checkHours(new Properties()));
        Properties off = new Properties();
        off.setProperty("FIRMWARE.ENABLED", "false");
        off.setProperty("FIRMWARE.CHECK_HOURS", "0");
        assertFalse(FirmwareUpdater.enabled(off));
        assertEquals(1, FirmwareUpdater.checkHours(off));
    }

    /** Same rules as handlePiCommand in ESP-ThreadReceiver.ino. */
    private static class FakeReceiver implements FirmwareUpdater.Link {
        FirmwareUpdater updater;
        final List<String> commands = new ArrayList<>();
        String storedVersion;
        byte[] stored;
        String storedSha = "";
        long capacity = 1_441_792;
        boolean silent, failEnd;
        int dropAckAt = -1;
        int dataLines;

        private String upVersion, upSha;
        private int upSize;
        private ByteArrayOutputStream written;

        @Override
        public void send(String line) {
            String cmd = line.strip();
            commands.add(cmd.startsWith("FW DATA") ? "FW DATA" : cmd);
            if (silent) return;
            String[] parts = cmd.split(" ");
            switch (parts[1]) {
                case "STATUS" -> answer("{\"fw\":\"status\",\"version\":\"" + (storedVersion == null ? "" : storedVersion)
                        + "\",\"size\":0,\"sha256\":\"" + storedSha + "\",\"capacity\":" + capacity + "}");
                case "BEGIN" -> {
                    storedVersion = null;
                    upVersion = parts[2];
                    upSize = Integer.parseInt(parts[3]);
                    upSha = parts[4];
                    written = new ByteArrayOutputStream();
                    answer("{\"fw\":\"ready\"}");
                }
                case "DATA" -> {
                    int off = Integer.parseInt(parts[2]);
                    byte[] data = Base64.getDecoder().decode(parts[3]);
                    if (off + data.length <= written.size()) {
                        // repeated chunk
                    } else if (off != written.size()) {
                        answer("{\"fw\":\"error\",\"msg\":\"unexpected offset\"}");
                        return;
                    } else {
                        written.write(data, 0, data.length);
                        dataLines++;
                    }
                    if (off == dropAckAt) {
                        dropAckAt = -1;
                        return;
                    }
                    answer("{\"fw\":\"ack\",\"off\":" + written.size() + "}");
                }
                case "END" -> {
                    try {
                        byte[] data = written.toByteArray();
                        if (failEnd || data.length != upSize || !sha256(data).equals(upSha)) {
                            answer("{\"fw\":\"error\",\"msg\":\"checksum mismatch\"}");
                            return;
                        }
                        stored = data;
                        storedVersion = upVersion;
                        storedSha = upSha;
                        answer("{\"fw\":\"stored\",\"version\":\"" + upVersion + "\"}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                default -> answer("{\"fw\":\"error\",\"msg\":\"unknown command\"}");
            }
        }

        private void answer(String line) {
            updater.onReply(line);
        }
    }
}
