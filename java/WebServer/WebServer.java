package WebServer;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server (JDK built-in, no extra dependencies).
 * Serves the REST read API and the mobile PWA (static files).
 * Static files are taken from ./web on disk if present, otherwise from
 * the classpath (/web, packaged into the jar by maven).
 */
public class WebServer {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    private final String host;
    private final int port;
    private final Path webRoot;
    private HttpServer server;

    public WebServer(DbReader db, String host, int port, String webRoot) {
        this.db = db;
        this.host = host;
        this.port = port;
        this.webRoot = Paths.get(webRoot == null ? "./web" : webRoot);
    }

    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(this.host, this.port), 0);
        this.server.createContext("/api/", this::handleApi);
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

    // ------------------------------------------------------------------ API

    private void handleApi(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

            if (path.equals("/api/health")) {
                sendJson(ex, 200, db.health());
            } else if (path.equals("/api/devices")) {
                sendJson(ex, 200, db.devices());
            } else if (path.equals("/api/sensors")) {
                sendJson(ex, 200, db.sensors());
            } else if (path.startsWith("/api/sensors/")) {
                int id = Integer.parseInt(path.substring("/api/sensors/".length()));
                Map<String, Object> sensor = db.sensor(id);
                if (sensor == null) sendError(ex, 404, "sensor not found");
                else sendJson(ex, 200, sensor);
            } else if (path.equals("/api/measurements/latest")) {
                sendJson(ex, 200, db.latestMeasurements());
            } else if (path.equals("/api/measurements")) {
                String sensorId = query.get("sensorId");
                if (sensorId == null) {
                    sendError(ex, 400, "missing query param: sensorId");
                    return;
                }
                long now = System.currentTimeMillis();
                Long from = query.containsKey("from") ? Long.parseLong(query.get("from")) : null;
                Long to = query.containsKey("to") ? Long.parseLong(query.get("to")) : null;
                if (query.containsKey("window")) {
                    from = now - Long.parseLong(query.get("window"));
                    to = now;
                }
                int limit = query.containsKey("limit")
                        ? Math.min(Integer.parseInt(query.get("limit")), 20000) : 2000;
                Map<String, Object> out = db.measurements(Integer.parseInt(sensorId), from, to, limit);
                out.put("sensor", db.sensor(Integer.parseInt(sensorId)));
                sendJson(ex, 200, out);
            } else {
                sendError(ex, 404, "unknown api endpoint");
            }
        } catch (IllegalArgumentException ex2) {
            sendError(ex, 400, ex2.getMessage());
        } catch (Exception ex2) {
            ex2.printStackTrace();
            sendError(ex, 500, "internal error: " + ex2.getMessage());
        }
    }

    // --------------------------------------------------------------- static

    private void handleStatic(HttpExchange ex) throws IOException {
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
        addCors(ex);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // -------------------------------------------------------------- helpers

    private static String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static void addCors(HttpExchange ex) {
        // Permissive CORS so a Capacitor-wrapped native app on the phone can call the API
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
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
