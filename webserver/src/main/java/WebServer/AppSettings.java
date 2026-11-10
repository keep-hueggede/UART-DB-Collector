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
    public static final String MAIL_HOST = "mail.host";
    public static final String MAIL_PORT = "mail.port";
    public static final String MAIL_SECURITY = "mail.security";
    public static final String MAIL_USER = "mail.user";
    public static final String MAIL_PASSWORD = "mail.password";
    public static final String MAIL_FROM = "mail.from";

    // Strato defaults; the user name is the full mail address
    static final String DEFAULT_MAIL_HOST = "smtp.strato.de";
    static final SmtpMailer.Security DEFAULT_MAIL_SECURITY = SmtpMailer.Security.SSL;

    static final int DEFAULT_SESSION_TTL_DAYS = 30;
    static final int MAX_SESSION_TTL_DAYS = 365;
    private static final int MAX_VALUE_LENGTH = 500;

    private final SettingsRepository repo;
    private final GoogleAuth google;
    private final SessionManager sessions;
    private final SmtpMailer smtp = new SmtpMailer();
    private Mailer mailer = smtp;

    public AppSettings(SettingsRepository repo, GoogleAuth google, SessionManager sessions) {
        this.repo = repo;
        this.google = google;
        this.sessions = sessions;
    }

    public Mailer mailer() {
        return mailer;
    }

    /** Replaces the SMTP mailer (tests). */
    public void useMailer(Mailer mailer) {
        this.mailer = mailer;
    }

    /**
     * Takes over GOOGLE.* and MAIL.* from app.config once (while the database has
     * no such values), so existing installations keep their login.
     */
    public void importFromConfig(Properties p) {
        Map<String, String> current = repo.all();
        if (!current.containsKey(MAIL_USER) && !p.getProperty("MAIL.USER", "").isBlank()) {
            String port = p.getProperty("MAIL.PORT", "").trim();
            updateMail(p.getProperty("MAIL.HOST", DEFAULT_MAIL_HOST).trim(), port.isEmpty() ? null : Integer.valueOf(port),
                    p.getProperty("MAIL.SECURITY", DEFAULT_MAIL_SECURITY.name()).trim(), p.getProperty("MAIL.USER").trim(),
                    p.getProperty("MAIL.PASSWORD", "").trim(), p.getProperty("MAIL.FROM", "").trim());
            System.out.println("Einstellungen: E-Mail-Versand aus app.config uebernommen (MAIL.* dort nicht mehr noetig)");
        }
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
        SmtpMailer.Security security = mailSecurity(s);
        String user = s.getOrDefault(MAIL_USER, "");
        smtp.configure(new SmtpMailer.Config(s.getOrDefault(MAIL_HOST, DEFAULT_MAIL_HOST), mailPort(s, security),
                security, user, s.getOrDefault(MAIL_PASSWORD, ""), mailFrom(s)));
    }

    /** Values for the settings page; the client secret is never sent back, only whether it is set. */
    public Map<String, Object> view() {
        Map<String, String> s = repo.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("googleClientId", s.getOrDefault(GOOGLE_CLIENT_ID, ""));
        out.put("googleClientSecretSet", !s.getOrDefault(GOOGLE_CLIENT_SECRET, "").isEmpty());
        out.put("googleRedirectUri", s.getOrDefault(GOOGLE_REDIRECT_URI, ""));
        out.put("sessionTtlDays", ttlDays(s));
        SmtpMailer.Security security = mailSecurity(s);
        out.put("mailHost", s.getOrDefault(MAIL_HOST, DEFAULT_MAIL_HOST));
        out.put("mailPort", mailPort(s, security));
        out.put("mailSecurity", security.name());
        out.put("mailUser", s.getOrDefault(MAIL_USER, ""));
        out.put("mailPasswordSet", !s.getOrDefault(MAIL_PASSWORD, "").isEmpty());
        out.put("mailFrom", s.getOrDefault(MAIL_FROM, ""));
        out.put("mailConfigured", mailer.isConfigured());
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

    /** Mail settings for admin notifications; null means "leave unchanged", as in update(). */
    public void updateMail(String host, Integer port, String security, String user, String password, String from) {
        Map<String, String> values = new HashMap<>();
        if (host != null) {
            String h = clean(host, "mailHost");
            if (!h.isEmpty() && !h.matches("[A-Za-z0-9.\\-]+")) throw new IllegalArgumentException("mailHost is invalid");
            values.put(MAIL_HOST, h);
        }
        if (port != null) {
            if (port < 0 || port > 65535) throw new IllegalArgumentException("mailPort must be 0..65535");
            values.put(MAIL_PORT, String.valueOf(port)); // 0 = default for the security mode
        }
        if (security != null) {
            try {
                values.put(MAIL_SECURITY, SmtpMailer.Security.valueOf(security.strip().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("mailSecurity must be STARTTLS, SSL or NONE");
            }
        }
        if (user != null) values.put(MAIL_USER, clean(user, "mailUser"));
        if (password != null) values.put(MAIL_PASSWORD, clean(password, "mailPassword"));
        if (from != null) {
            String f = clean(from, "mailFrom");
            if (!f.isEmpty() && !f.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new IllegalArgumentException("mailFrom must be a mail address");
            }
            values.put(MAIL_FROM, f);
        }
        repo.putAll(values);
        apply();
    }

    private static SmtpMailer.Security mailSecurity(Map<String, String> s) {
        try {
            return SmtpMailer.Security.valueOf(s.getOrDefault(MAIL_SECURITY, DEFAULT_MAIL_SECURITY.name()));
        } catch (IllegalArgumentException e) {
            return DEFAULT_MAIL_SECURITY;
        }
    }

    private static int mailPort(Map<String, String> s, SmtpMailer.Security security) {
        try {
            int port = Integer.parseInt(s.getOrDefault(MAIL_PORT, "0"));
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // fall back to the default port
        }
        return switch (security) {
            case SSL -> 465;
            case STARTTLS -> 587;
            case NONE -> 25;
        };
    }

    // sender defaults to the login name, which is the mail address at Strato
    private static String mailFrom(Map<String, String> s) {
        String from = s.getOrDefault(MAIL_FROM, "");
        String user = s.getOrDefault(MAIL_USER, "");
        return !from.isEmpty() ? from : (user.contains("@") ? user : "");
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
