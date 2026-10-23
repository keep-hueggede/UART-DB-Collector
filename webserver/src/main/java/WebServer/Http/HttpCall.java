package WebServer.Http;

import WebServer.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One HTTP request/response pair: request accessors (path parameters, query,
 * JSON body, authenticated user) and response helpers. Error responses never
 * contain stack traces or SQL.
 */
public final class HttpCall {

    public static final int MAX_BODY_BYTES = 8 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpExchange ex;
    private Map<String, String> params = Map.of();
    private Map<String, String> query;
    private SessionManager.Session user;

    public HttpCall(HttpExchange ex) {
        this.ex = ex;
    }

    // -------------------------------------------------------------- request

    public HttpExchange exchange() {
        return ex;
    }

    public String method() {
        return ex.getRequestMethod();
    }

    public String path() {
        return ex.getRequestURI().getPath();
    }

    public String header(String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    /** Value of a {placeholder} in the matched route pattern. */
    public String param(String name) {
        return params.get(name);
    }

    void setParams(Map<String, String> params) {
        this.params = params;
    }

    /** Numeric {id} path parameter, or null when it is not a number (caller answers 404). */
    public Integer pathId() {
        String value = param("id");
        if (value == null || !value.matches("\\d+")) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, String> query() {
        if (query == null) query = parseQuery(ex.getRequestURI().getRawQuery());
        return query;
    }

    /** Authenticated user; null only on public routes. */
    public SessionManager.Session user() {
        return user;
    }

    public void setUser(SessionManager.Session user) {
        this.user = user;
    }

    /** Scheme as seen by the client (reverse proxy sets X-Forwarded-Proto). */
    public String scheme() {
        String fwd = header("X-Forwarded-Proto");
        return ("http".equals(fwd) || "https".equals(fwd)) ? fwd : "http";
    }

    /** Reads a small JSON body; null if empty. Oversized -> 413, invalid -> 400. */
    public JsonNode jsonBody() throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
            if (data.length > MAX_BODY_BYTES) throw new BodyTooLargeException();
            if (data.length == 0) return null;
            return JSON.readTree(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json");
        }
    }

    // ------------------------------------------------------------- response

    public void addHeader(String name, String value) {
        ex.getResponseHeaders().add(name, value);
    }

    public void json(int status, Object payload) throws IOException {
        byte[] body = JSON.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public void error(int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message == null ? "error" : message);
        json(status, err);
    }

    public void redirect(String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
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
}
