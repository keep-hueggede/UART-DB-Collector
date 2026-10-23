package WebServer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One-time OAuth state values (CSRF protection of the Google login). */
public class OAuthStateStore {

    public static final long STATE_TTL_MS = 10 * 60 * 1000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** state -> expiry (epoch ms) */
    private final Map<String, Long> pending = new ConcurrentHashMap<>();

    /** Issues a fresh random state and drops expired ones. */
    public String issue() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue() < now);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pending.put(state, now + STATE_TTL_MS);
        return state;
    }

    /** True exactly once for a known, unexpired state. */
    public boolean consume(String state) {
        if (state == null) return false;
        Long expiry = pending.remove(state);
        return expiry != null && expiry >= System.currentTimeMillis();
    }
}
