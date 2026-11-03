package WebServer;

import Repository.SettingsRepository;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Settings that an admin edits in the web app. They live in the database and
 * take effect immediately; only DB.* and SERVER.* stay in app.config because
 * the server needs them before it can read the database.
 */
public class AppSettings {

    public static final String GOOGLE_CLIENT_ID = "google.clientId";
    public static final String GOOGLE_CLIENT_SECRET = "google.clientSecret";
    public static final String GOOGLE_REDIRECT_URI = "google.redirectUri";
    public static final String SESSION_TTL_DAYS = "session.ttlDays";

    static final int DEFAULT_SESSION_TTL_DAYS = 30;
    static final int MAX_SESSION_TTL_DAYS = 365;
    private static final int MAX_VALUE_LENGTH = 500;

    private final SettingsRepository repo;
    private final GoogleAuth google;
    private final SessionManager sessions;

    public AppSettings(SettingsRepository repo, GoogleAuth google, SessionManager sessions) {
        this.repo = repo;
        this.google = google;
        this.sessions = sessions;
    }

    /** Takes over GOOGLE.* from app.config once, so existing installations keep their login. */
    public void importFromConfig(Properties p) {
        Map<String, String> current = repo.all();
        if (current.containsKey(GOOGLE_CLIENT_ID)) return;
        String id = p.getProperty("GOOGLE.CLIENT_ID", "").trim();
        String secret = p.getProperty("GOOGLE.CLIENT_SECRET", "").trim();
        if (id.isEmpty() || secret.isEmpty()) return;
        Map<String, String> values = new HashMap<>();
        values.put(GOOGLE_CLIENT_ID, id);
        values.put(GOOGLE_CLIENT_SECRET, secret);
        values.put(GOOGLE_REDIRECT_URI, p.getProperty("GOOGLE.REDIRECT_URI", "").trim());
        repo.putAll(values);
        System.out.println("Einstellungen: Google-Login aus app.config uebernommen (GOOGLE.* dort nicht mehr noetig)");
    }

    /** Reads the stored settings and applies them to login and sessions. */
    public void apply() {
        Map<String, String> s = repo.all();
        google.configure(s.getOrDefault(GOOGLE_CLIENT_ID, ""), s.getOrDefault(GOOGLE_CLIENT_SECRET, ""),
                s.getOrDefault(GOOGLE_REDIRECT_URI, ""));
        sessions.setTtlMs(ttlDays(s) * 24L * 60 * 60 * 1000);
    }

    /** Values for the settings page; the client secret is never sent back, only whether it is set. */
    public Map<String, Object> view() {
        Map<String, String> s = repo.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("googleClientId", s.getOrDefault(GOOGLE_CLIENT_ID, ""));
        out.put("googleClientSecretSet", !s.getOrDefault(GOOGLE_CLIENT_SECRET, "").isEmpty());
        out.put("googleRedirectUri", s.getOrDefault(GOOGLE_REDIRECT_URI, ""));
        out.put("sessionTtlDays", ttlDays(s));
        return out;
    }

    /**
     * Validates and stores changed values; null means "leave unchanged" (so the
     * secret does not have to be re-entered). Throws IllegalArgumentException (-> 400).
     */
    public void update(String clientId, String clientSecret, String redirectUri, Integer sessionTtlDays) {
        Map<String, String> values = new HashMap<>();
        if (clientId != null) values.put(GOOGLE_CLIENT_ID, clean(clientId, "googleClientId"));
        if (clientSecret != null) values.put(GOOGLE_CLIENT_SECRET, clean(clientSecret, "googleClientSecret"));
        if (redirectUri != null) {
            String uri = clean(redirectUri, "googleRedirectUri");
            if (!uri.isEmpty() && !uri.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]+)?/auth/callback")) {
                throw new IllegalArgumentException("googleRedirectUri must look like https://<host>/auth/callback");
            }
            values.put(GOOGLE_REDIRECT_URI, uri);
        }
        if (sessionTtlDays != null) {
            if (sessionTtlDays < 1 || sessionTtlDays > MAX_SESSION_TTL_DAYS) {
                throw new IllegalArgumentException("sessionTtlDays must be 1.." + MAX_SESSION_TTL_DAYS);
            }
            values.put(SESSION_TTL_DAYS, String.valueOf(sessionTtlDays));
        }
        repo.putAll(values);
        apply();
    }

    private static String clean(String value, String field) {
        String v = value.strip();
        if (v.length() > MAX_VALUE_LENGTH || v.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return v;
    }

    private static int ttlDays(Map<String, String> s) {
        try {
            int days = Integer.parseInt(s.getOrDefault(SESSION_TTL_DAYS, String.valueOf(DEFAULT_SESSION_TTL_DAYS)));
            return (days < 1 || days > MAX_SESSION_TTL_DAYS) ? DEFAULT_SESSION_TTL_DAYS : days;
        } catch (NumberFormatException e) {
            return DEFAULT_SESSION_TTL_DAYS;
        }
    }
}
