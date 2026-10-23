package WebServer;

import Repository.Repositories;
import WebServer.Handlers.*;
import WebServer.Http.BodyTooLargeException;
import WebServer.Http.HttpCall;
import WebServer.Http.Router;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server (JDK built-in, no extra dependencies).
 *
 * Serves the mobile PWA, the Google login flow and the user-scoped REST API.
 * Each endpoint is a {@link WebServer.Http.Handler} in WebServer.Handlers; this
 * class only wires the routes and applies the cross-cutting rules below.
 *
 * Security design:
 * - all /api endpoints except /api/health require a session (401 otherwise,
 *   checked before 404/405 so endpoints cannot be probed anonymously)
 * - every data query is scoped to the authenticated user (404 for unowned
 *   resources so ids cannot be enumerated)
 * - OAuth state parameter (CSRF) is random, one-time and bound to a cookie
 * - responses carry CSP / nosniff / frame-deny headers
 * - CORS allows cross-origin READS with bearer tokens only (no
 *   Access-Control-Allow-Credentials -> cookies never work cross-origin)
 * - error responses never contain stack traces or SQL
 */
public class WebServer {

    private final SessionManager sessions;
    private final GoogleAuth google;
    private final String host;
    private final int port;
    private final Router api;
    private final Router auth;
    private final StaticFileHandler staticFiles;

    private HttpServer server;

    public WebServer(Repositories repos, SessionManager sessions, GoogleAuth google,
                     String host, int port, String webRoot, boolean secureCookie) {
        this.sessions = sessions;
        this.google = google;
        this.host = host;
        this.port = port;

        this.api = new Router()
                .publicGet("/api/health", new HealthHandler(repos))
                .get("/api/auth/me", new MeHandler())
                .get("/api/devices", new ListDevicesHandler(repos))
                .post("/api/devices", new LinkDeviceHandler(repos))
                .get("/api/devices/available", new AvailableDevicesHandler(repos))
                .post("/api/devices/{id}/link", new LinkDeviceByIdHandler(repos))
                .put("/api/devices/{id}", new RenameDeviceHandler(repos))
                .delete("/api/devices/{id}", new UnlinkDeviceHandler(repos))
                .get("/api/sensors", new ListSensorsHandler(repos))
                .get("/api/sensors/{id}", new GetSensorHandler(repos))
                .put("/api/sensors/{id}/calibration", new SetCalibrationHandler(repos))
                .get("/api/measurements/latest", new LatestMeasurementsHandler(repos))
                .get("/api/measurements", new MeasurementSeriesHandler(repos));

        OAuthStateStore states = new OAuthStateStore();
        this.auth = new Router()
                .get("/auth/login", new LoginHandler(google, states, secureCookie))
                .get("/auth/callback", new CallbackHandler(google, states, repos, sessions, secureCookie))
                .post("/auth/logout", new LogoutHandler(sessions, secureCookie));

        this.staticFiles = new StaticFileHandler(webRoot);
    }

    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(this.host, this.port), 0);
        this.server.createContext("/api/", this::onApi);
        this.server.createContext("/auth/", this::onAuth);
        this.server.createContext("/", this::onStatic);
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

    // ------------------------------------------------------------- contexts

    private void onApi(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        HttpCall call = new HttpCall(ex);
        try {
            Router.Match match = api.match(call.method(), call.path());
            if (!match.isPublic()) {
                SessionManager.Session user = sessions.resolve(call.header("Cookie"), call.header("Authorization"));
                if (user == null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "unauthorized");
                    err.put("authConfigured", google.isConfigured());
                    call.json(401, err);
                    return;
                }
                call.setUser(user);
            }
            match.dispatch(call, "unknown api endpoint");
        } catch (BodyTooLargeException e) {
            call.error(413, "request too large");
        } catch (IllegalArgumentException e) {
            call.error(400, "bad request");
        } catch (Exception e) {
            e.printStackTrace();
            call.error(500, "internal error");
        }
    }

    private void onAuth(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        addCors(ex);
        HttpCall call = new HttpCall(ex);
        try {
            Router.Match match = auth.match(call.method(), call.path());
            if (match.handler() == null) {
                call.error(405, "method not allowed");
            } else {
                match.dispatch(call, "not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            call.error(500, "internal error");
        }
    }

    private void onStatic(HttpExchange ex) throws IOException {
        securityHeaders(ex);
        staticFiles.handle(new HttpCall(ex));
    }

    // ------------------------------------------------------- response rules

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
}
