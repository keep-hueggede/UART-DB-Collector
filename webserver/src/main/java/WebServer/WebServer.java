package WebServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server (JDK built-in, no extra dependencies).
 *
 * Serves the mobile PWA, the Google login flow and the user-scoped REST API.
 *
 * Security design:
 * - all /api endpoints except /api/health require a session (401 otherwise)
 * - every data query is scoped to the authenticated user (404 for unowned
 *   resources so ids cannot be enumerated)
 * - OAuth state parameter (CSRF) is random, one-time and bound to a cookie
 * - responses carry CSP / nosniff / frame-deny headers
 * - CORS allows cross-origin READS with bearer tokens only (no
 *   Access-Control-Allow-Credentials -> cookies never work cross-origin)
 * - error responses never contain stack traces or SQL
 */
public class WebServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final long STATE_TTL_MS = 10 * 60 * 1000;   // OAuth state lifetime
    private static final long LOGIN_TTL_SEC = 600;              // state cookie lifetime
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private static final Map<String, String> MIME = new HashMap<>() {{
        put("html", "text/html; charset=utf-8");
        put("js", "application/javascript; charset=utf-8");
        put("css", "text/css; charset=utf-8");
        put("json", "application/json; charset=utf-8");
        put("webmanifest", "application/manifest+json; charset=utf-8");
        put("svg", "image/svg+xml");
        put("png", "image/png");
        put("ico", "image/x-icon");
        put("woff2", "font/woff2");
    }};

    private final DbReader db;
    private final SessionManager sessions;
    private final GoogleAuth google;
    private final String host;
    private final int port;
    private final Path webRoot;
    private final boolean secureCookie;

    /** one-time OAuth state values: state -> expiry */
    private final Map<String, Long> pendingStates = new ConcurrentHashMap<>();

    private HttpServer server;

    public WebServer(DbReader db, SessionManager sessions, GoogleAuth google,
                     String host, int port, String webRoot, boolean secureCookie) {
        this.db = db;
        this.sessions = sessions;
        this.google = google;
        this.host = host;
        this.port = port;
        this.webRoot = Paths.get(webRoot == null ? "./web" : webRoot);
        this.secureCookie = secureCookie;
    }

    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(this.host, this.port), 0);
        this.server.createContext("/api/", this::handleApi);
        this.server.createContext("/auth/", this::handleAuth);
        this.server.createContext("/", this::handleStatic);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.start();
    }

    public void stop() {
        if (this.server != null) this.server.stop(0);
    }

    public int getPort() {
        return this.server.getAddress().getPort();
    }

    public boolean isAuthConfigured() {
        return google.isConfigured();
    }

    // ---------------------------------------------------------------- auth

    private void handleAuth(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        addCors(ex);
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        try {
            if (path.equals("/auth/login") && method.equals("GET")) {
                handleLogin(ex);
            } else if (path.equals("/auth/callback") && method.equals("GET")) {
                handleCallback(ex);
            } else if (path.equals("/auth/logout") && method.equals("POST")) {
                handleLogout(ex);
            } else {
                sendError(ex, 405, "method not allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(ex, 500, "internal error");
        }
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!google.isConfigured()) {
            sendError(ex, 503, "Google login is not configured (GOOGLE.CLIENT_ID / GOOGLE.CLIENT_SECRET)");
            return;
        }
        purgeStates();
        String state = randomToken();
        pendingStates.put(state, System.currentTimeMillis() + STATE_TTL_MS);

        String redirectUri = google.redirectUri(currentScheme(ex), ex.getRequestHeaders().getFirst("Host"));
        String loginUrl = google.loginUrl(redirectUri, state);

        ex.getResponseHeaders().add("Set-Cookie",
                "UARTDB_STATE=" + state + "; Path=/auth; Max-Age=" + LOGIN_TTL_SEC
                        + "; HttpOnly; SameSite=Lax" + (secureCookie ? "; Secure" : ""));
        ex.getResponseHeaders().add("Location", loginUrl);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void handleCallback(HttpExchange ex) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        String error = query.get("error");
        if (error != null && !error.isEmpty()) {
            redirect(ex, "/#auth-error");
            return;
        }

        String stateParam = query.get("state");
        String stateCookie = SessionManager.extractCookie(
                ex.getRequestHeaders().getFirst("Cookie") == null ? ""
                        : ex.getRequestHeaders().getFirst("Cookie"), "UARTDB_STATE");

        // one-time, server-side known and cookie-bound state (CSRF protection)
        boolean stateOk = stateParam != null && stateCookie != null
                && MessageDigest.isEqual(stateParam.getBytes(StandardCharsets.UTF_8),
                                          stateCookie.getBytes(StandardCharsets.UTF_8))
                && pendingStates.remove(stateParam) != null;
        if (!stateOk) {
            redirect(ex, "/#auth-error");
            return;
        }

        String code = query.get("code");
        if (code == null || code.isEmpty()) {
            redirect(ex, "/#auth-error");
            return;
        }

        try {
            String redirectUri = google.redirectUri(
                    currentScheme(ex), ex.getRequestHeaders().getFirst("Host"));
            GoogleAuth.GoogleUser gu = google.exchangeCode(code, redirectUri);
            var user = db.loginOrCreateUser(gu.sub, gu.email, gu.name, gu.pictureUrl);
            String token = sessions.create(user);

            ex.getResponseHeaders().add("Set-Cookie",
                    SessionManager.buildCookie(token, secureCookie, SessionManager.SESSION_TTL_MS / 1000));
            ex.getResponseHeaders().add("Set-Cookie",
                    "UARTDB_STATE=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Lax"
                            + (secureCookie ? "; Secure" : ""));
            // token in the fragment: never sent to the server, picked up by the
            // PWA (bearer mode) and removed from the URL right away
            redirect(ex, "/#token=" + token);
        } catch (Exception e) {
            // log details server-side only, never to the client
            System.err.println("Google login failed: " + e.getMessage());
            redirect(ex, "/#auth-error");
        }
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        sessions.invalidate(
                ex.getRequestHeaders().getFirst("Cookie"),
                ex.getRequestHeaders().getFirst("Authorization"));
        ex.getResponseHeaders().add("Set-Cookie", SessionManager.clearCookie(secureCookie));
        sendJson(ex, 200, Map.of("status", "logged-out"));
    }

    // ----------------------------------------------------------------- api

    private void handleApi(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        // public endpoint: liveness only, no data
        if (path.equals("/api/health")) {
            try {
                sendJson(ex, 200, db.health());
            } catch (Exception e) {
                e.printStackTrace();
                sendError(ex, 500, "internal error");
            }
            return;
        }

        SessionManager.Session user = sessions.resolve(
                ex.getRequestHeaders().getFirst("Cookie"),
                ex.getRequestHeaders().getFirst("Authorization"));

        if (user == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "unauthorized");
            err.put("authConfigured", google.isConfigured());
            sendJson(ex, 401, err);
            return;
        }

        try {
            dispatchApi(ex, method, path, user);
        } catch (BodyTooLargeException e) {
            sendError(ex, 413, "request too large");
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, "bad request");
        } catch (Exception e) {
            e.printStackTrace();
            sendError(ex, 500, "internal error");
        }
    }

    private void dispatchApi(HttpExchange ex, String method, String path,
                             SessionManager.Session user) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        if (path.equals("/api/auth/me")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("userId", user.userId);
            me.put("email", user.email);
            me.put("name", user.name);
            me.put("picture", user.pictureUrl);
            me.put("expiresAt", user.expiresAt);
            sendJson(ex, 200, me);

        } else if (path.equals("/api/devices")) {
            if (method.equals("GET")) {
                sendJson(ex, 200, db.devices(user.userId));
            } else if (method.equals("POST")) {
                JsonNode body = readJsonBody(ex);
                Integer deviceId = body != null && body.hasNonNull("deviceId")
                        ? body.get("deviceId").asInt() : null;
                String name = body != null && body.hasNonNull("name")
                        ? body.get("name").asText() : null;
                if (deviceId == null && (name == null || name.isBlank())) {
                    sendError(ex, 400, "deviceId or name required");
                    return;
                }
                Map<String, Object> device = db.linkDevice(user.userId, deviceId, name);
                if (device == null) {
                    sendError(ex, 404, "device not found");
                    return;
                }
                sendJson(ex, 200, device);
            } else {
                sendError(ex, 405, "method not allowed");
            }

        } else if (path.equals("/api/devices/available")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            sendJson(ex, 200, db.availableDevices(user.userId));

        } else if (path.startsWith("/api/devices/") && path.endsWith("/link")) {
            // explicit alias: POST /api/devices/{id}/link
            if (!method.equals("POST")) { sendError(ex, 405, "method not allowed"); return; }
            Integer id = parseIdOrNull(path.substring("/api/devices/".length(), path.length() - "/link".length()));
            if (id == null) { sendError(ex, 404, "not found"); return; }
            Map<String, Object> device = db.linkDevice(user.userId, id, null);
            if (device == null) { sendError(ex, 404, "device not found"); return; }
            sendJson(ex, 200, device);

        } else if (path.startsWith("/api/devices/") && method.equals("DELETE")) {
            Integer id = parseIdOrNull(path.substring("/api/devices/".length()));
            if (id == null) { sendError(ex, 404, "not found"); return; }
            boolean removed = db.unlinkDevice(user.userId, id);
            sendJson(ex, 200, Map.of("unlinked", removed));

        } else if (path.startsWith("/api/devices/") && method.equals("PUT")) {
            Integer id = parseIdOrNull(path.substring("/api/devices/".length()));
            if (id == null) { sendError(ex, 404, "not found"); return; }
            handleRenameDevice(ex, user, id);

        } else if (path.startsWith("/api/devices/")) {
            sendError(ex, 405, "method not allowed");

        } else if (path.equals("/api/sensors")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            sendJson(ex, 200, db.sensors(user.userId));

        } else if (path.startsWith("/api/sensors/")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            Integer id = parseIdOrNull(path.substring("/api/sensors/".length()));
            if (id == null) { sendError(ex, 404, "not found"); return; }
            Map<String, Object> sensor = db.sensor(user.userId, id);
            // unowned ids answer like missing ones -> no enumeration
            if (sensor == null) { sendError(ex, 404, "not found"); return; }
            sendJson(ex, 200, sensor);

        } else if (path.equals("/api/measurements/latest")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            sendJson(ex, 200, db.latestMeasurements(user.userId));

        } else if (path.equals("/api/measurements")) {
            if (!method.equals("GET")) { sendError(ex, 405, "method not allowed"); return; }
            String sensorId = query.get("sensorId");
            if (sensorId == null) {
                sendError(ex, 400, "missing query param: sensorId");
                return;
            }
            int sid = Integer.parseInt(sensorId);
            long now = System.currentTimeMillis();
            Long from = query.containsKey("from") ? Long.parseLong(query.get("from")) : null;
            Long to = query.containsKey("to") ? Long.parseLong(query.get("to")) : null;
            if (query.containsKey("window")) {
                from = now - Long.parseLong(query.get("window"));
                to = now;
            }
            int limit = 2000;
            if (query.containsKey("limit")) {
                limit = Integer.parseInt(query.get("limit"));
                if (limit < 1 || limit > 20000) {
                    sendError(ex, 400, "limit out of range");
                    return;
                }
            }
            Map<String, Object> out = db.measurements(user.userId, sid, from, to, limit);
            if (out == null) {
                sendError(ex, 404, "not found");
                return;
            }
            out.put("sensor", db.sensor(user.userId, sid));
            sendJson(ex, 200, out);

        } else {
            sendError(ex, 404, "unknown api endpoint");
        }
    }

    private static final int MAX_NAME_LENGTH = 40;

    /**
     * PUT /api/devices/{id} {"name": "..."} - rename a device the user has
     * linked. Requires application/json, so CORS preflights it; only the
     * authenticated owner may rename (404 otherwise).
     */
    private void handleRenameDevice(HttpExchange ex, SessionManager.Session user, int id)
            throws IOException {
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        if (ctype == null || !ctype.toLowerCase().startsWith("application/json")) {
            sendError(ex, 415, "content type must be application/json");
            return;
        }
        JsonNode body = readJsonBody(ex);
        JsonNode nameNode = body == null ? null : body.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            sendError(ex, 400, "missing field: name");
            return;
        }
        String name = nameNode.asText().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            sendError(ex, 400, "name must be 1-" + MAX_NAME_LENGTH
                    + " characters without control characters");
            return;
        }
        switch (db.renameDevice(user.userId, id, name)) {
            case NOT_FOUND -> sendError(ex, 404, "device not found");
            case NAME_TAKEN -> sendError(ex, 409, "name already in use");
            default -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("name", name);
                sendJson(ex, 200, out);
            }
        }
    }

    // --------------------------------------------------------------- static

    private void handleStatic(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "method not allowed");
            return;
        }
        String rawPath = ex.getRequestURI().getPath();
        if (rawPath.equals("/") || rawPath.isEmpty()) rawPath = "/index.html";
        if (rawPath.contains("..")) {
            sendError(ex, 404, "not found");
            return;
        }

        byte[] body = null;
        String mime = null;

        // 1) filesystem web root (nice for development, no rebuild needed)
        Path file = this.webRoot.resolve(rawPath.substring(1)).normalize();
        if (file.startsWith(this.webRoot.normalize()) && Files.isRegularFile(file)) {
            body = Files.readAllBytes(file);
            mime = mimeFor(file.getFileName().toString());
        }

        // 2) classpath (packaged into the jar)
        if (body == null) {
            String resource = "web" + rawPath;
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (in != null) {
                    body = in.readAllBytes();
                    mime = mimeFor(rawPath);
                }
            }
        }

        if (body == null) {
            sendError(ex, 404, "not found");
            return;
        }

        String name = Paths.get(rawPath).getFileName().toString();
        String cache = (name.equals("sw.js") || name.equals("index.html") || name.equals("manifest.webmanifest"))
                ? "no-cache" : "max-age=3600";
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", cache);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // -------------------------------------------------------------- helpers

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static String currentScheme(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if ("http".equals(fwd) || "https".equals(fwd)) return fwd;
        return "http";
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self' data: https:; object-src 'none'; "
                        + "base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void purgeStates() {
        long now = System.currentTimeMillis();
        pendingStates.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** Numeric path id, or null when the segment is not a number (-> 404). */
    private static Integer parseIdOrNull(String value) {
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Raised when a request body exceeds MAX_BODY_BYTES (mapped to 413). */
    private static class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException() { super("body too large"); }
    }

    /** Reads a small JSON body, rejecting oversized payloads. */
    private static JsonNode readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
            if (data.length > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
            if (data.length == 0) return null;
            return JSON.readTree(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    private static String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * Permissive read-only CORS for the native app: cross-origin callers may
     * send Authorization (bearer), but cookies are deliberately NOT allowed
     * cross-origin (no Access-Control-Allow-Credentials).
     */
    private static void addCors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept");
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } else if (!pair.isEmpty()) {
                out.put(pair, "");
            }
        }
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] body = JSON.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message == null ? "error" : message);
        sendJson(ex, status, err);
    }
}
