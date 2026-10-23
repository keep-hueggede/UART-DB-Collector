package WebServer;

import Model.UserAccount;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store for Google logins.
 *
 * Security properties:
 * - 256-bit tokens from SecureRandom (unpredictable, single use per login)
 * - only the SHA-256 hash of a token is stored, so the raw token never
 *   sits in the session map (or a heap dump) on the server side
 * - server-side expiry + explicit invalidation on logout (state can be
 *   revoked even if the client keeps a copy)
 * - cookie is HttpOnly + SameSite=Lax (CSRF-safe for state changing
 *   requests); Secure flag configurable for TLS setups
 * - Bearer header supported as alternative for the native app where
 *   cross-origin cookies are not available
 */
public class SessionManager {

    public static final String COOKIE_NAME = "UARTDB_SESSION";
    public static final long SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HASH_ALGO = "SHA-256";

    public static class Session {
        public final int userId;
        public final String email;
        public final String name;
        public final String pictureUrl;
        public final long expiresAt;

        Session(int userId, String email, String name, String pictureUrl, long expiresAt) {
            this.userId = userId;
            this.email = email;
            this.name = name;
            this.pictureUrl = pictureUrl;
            this.expiresAt = expiresAt;
        }
    }

    /** key = sha256(token) -> session */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Issues a fresh token for the user (tokens are never reused -> no fixation). */
    public String create(UserAccount user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(hash(token), new Session(
                user.getId(), user.getEmail(), user.getName(), user.getPictureUrl(),
                System.currentTimeMillis() + SESSION_TTL_MS));
        purgeExpired();
        return token;
    }

    /** Returns the session for the request headers, or null if not authenticated. */
    public Session resolve(String cookieHeader, String authorizationHeader) {
        String token = null;
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorizationHeader.substring(7).trim();
        }
        if ((token == null || token.isEmpty()) && cookieHeader != null) {
            token = extractCookie(cookieHeader, COOKIE_NAME);
        }
        if (token == null || token.isEmpty() || token.length() > 512) return null;

        String key = hash(token);
        Session s = sessions.get(key);
        if (s == null) return null;
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(key);
            return null;
        }
        return s;
    }

    /** Invalidates the session presented by the request headers (logout). */
    public void invalidate(String cookieHeader, String authorizationHeader) {
        String token = null;
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorizationHeader.substring(7).trim();
        }
        if ((token == null || token.isEmpty()) && cookieHeader != null) {
            token = extractCookie(cookieHeader, COOKIE_NAME);
        }
        if (token != null && !token.isEmpty()) {
            sessions.remove(hash(token));
        }
    }

    public static String buildCookie(String token, boolean secure, long maxAgeSec) {
        return COOKIE_NAME + "=" + token
                + "; Path=/"
                + "; Max-Age=" + maxAgeSec
                + "; HttpOnly"
                + "; SameSite=Lax"
                + (secure ? "; Secure" : "");
    }

    public static String clearCookie(boolean secure) {
        return COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
    }

    static String extractCookie(String header, String name) {
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) {
                return p.substring(eq + 1);
            }
        }
        return null;
    }

    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }
}
