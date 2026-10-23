package WebServer.Handlers;

import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Serves the PWA: first from the filesystem web root (no rebuild needed during
 * development), then from the classpath (web/ packaged into the jar).
 */
public class StaticFileHandler implements Handler {

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "webmanifest", "application/manifest+json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2");

    private final Path webRoot;

    public StaticFileHandler(String webRoot) {
        this.webRoot = Paths.get(webRoot == null ? "./web" : webRoot);
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        if (!"GET".equals(call.method()) && !"HEAD".equals(call.method())) {
            call.error(405, "method not allowed");
            return;
        }
        String rawPath = call.path();
        if (rawPath.equals("/") || rawPath.isEmpty()) rawPath = "/index.html";
        if (rawPath.contains("..")) {
            call.error(404, "not found");
            return;
        }

        byte[] body = null;
        String mime = null;

        Path file = webRoot.resolve(rawPath.substring(1)).normalize();
        if (file.startsWith(webRoot.normalize()) && Files.isRegularFile(file)) {
            body = Files.readAllBytes(file);
            mime = mimeFor(file.getFileName().toString());
        }
        if (body == null) {
            try (InputStream in = StaticFileHandler.class.getClassLoader().getResourceAsStream("web" + rawPath)) {
                if (in != null) {
                    body = in.readAllBytes();
                    mime = mimeFor(rawPath);
                }
            }
        }
        if (body == null) {
            call.error(404, "not found");
            return;
        }

        String name = Paths.get(rawPath).getFileName().toString();
        String cache = (name.equals("sw.js") || name.equals("index.html") || name.equals("manifest.webmanifest"))
                ? "no-cache" : "max-age=3600";
        var headers = call.exchange().getResponseHeaders();
        headers.set("Content-Type", mime);
        headers.set("Cache-Control", cache);
        call.exchange().sendResponseHeaders(200, body.length);
        try (OutputStream os = call.exchange().getResponseBody()) {
            os.write(body);
        }
    }

    private static String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
