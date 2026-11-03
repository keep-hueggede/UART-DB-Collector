package WebServer;

import Repository.UserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-run setup: as long as there is no admin, a one-time code is written to
 * a file in the server's working directory. Whoever can read that file (i.e. has
 * access to the installation) proves it on the setup page; the Google account
 * that then logs in becomes the first admin and setup closes for good.
 */
public class SetupService {

    public static final String CODE_FILE_NAME = "setup-code.txt";
    public static final String CLAIM_COOKIE = "UARTDB_SETUP";
    public static final long CLAIM_TTL_MS = 30 * 60 * 1000;

    private static final int MAX_FAILED_ATTEMPTS = 10;
    // no 0/O/1/I/L to avoid typos when copying by hand
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final Path codeFile;
    private volatile String code;              // null = setup closed
    private int failedAttempts;
    private final Map<String, Long> claims = new ConcurrentHashMap<>();   // hash(token) -> expiry

    public SetupService(UserRepository users, Path workDir) {
        this.users = users;
        this.codeFile = workDir.resolve(CODE_FILE_NAME).toAbsolutePath().normalize();
    }

    /** Opens setup (new code file) when no admin exists, otherwise removes a stale file. */
    public synchronized void init() throws IOException {
        if (users.adminCount() > 0) {
            close();
        } else {
            newCode();
        }
    }

    public boolean isRequired() {
        return code != null;
    }

    public Path codeFile() {
        return codeFile;
    }

    /** Checks the code; returns a short-lived claim token, or null if wrong or setup is closed. */
    public synchronized String verify(String candidate) throws IOException {
        if (code == null) return null;
        String given = candidate == null ? "" : candidate.replace("-", "").replace(" ", "").strip().toUpperCase();
        boolean ok = MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                System.err.println("Einrichtung: zu viele falsche Codes - neuer Code in " + codeFile);
                newCode();
            }
            return null;
        }
        failedAttempts = 0;
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        claims.put(SessionManager.hash(token), System.currentTimeMillis() + CLAIM_TTL_MS);
        return token;
    }

    /** From the OAuth callback: makes the user the first admin if the browser holds a valid claim. */
    public synchronized boolean completeIfClaimed(String claimToken, int userId) throws IOException {
        if (code == null || claimToken == null || claimToken.isEmpty() || claimToken.length() > 512) return false;
        Long expiry = claims.get(SessionManager.hash(claimToken));
        if (expiry == null || expiry < System.currentTimeMillis()) return false;
        boolean promoted = users.claimFirstAdmin(userId);
        close();
        return promoted;
    }

    private void newCode() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        String newCode = sb.toString();
        Files.writeString(codeFile, newCode + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(codeFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system (Windows development)
        }
        this.code = newCode.replace("-", "");
        this.failedAttempts = 0;
        this.claims.clear();
        System.out.println("Einrichtung: noch kein Admin - Einrichtungscode in " + codeFile);
    }

    private void close() throws IOException {
        this.code = null;
        this.claims.clear();
        Files.deleteIfExists(codeFile);
    }
}
