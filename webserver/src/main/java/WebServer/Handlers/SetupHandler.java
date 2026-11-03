package WebServer.Handlers;

import WebServer.AppSettings;
import WebServer.GoogleAuth;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.SetupService;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * POST /api/setup {"code", "googleClientId", "googleClientSecret", "googleRedirectUri"}
 * (public, only while setup is open). A correct one-time code stores the Google
 * client and hands the browser a short-lived claim cookie; the Google account
 * that logs in with it becomes the first admin.
 */
public class SetupHandler implements Handler {

    private final SetupService setup;
    private final AppSettings settings;
    private final GoogleAuth google;
    private final boolean secureCookie;

    public SetupHandler(SetupService setup, AppSettings settings, GoogleAuth google, boolean secureCookie) {
        this.setup = setup;
        this.settings = settings;
        this.google = google;
        this.secureCookie = secureCookie;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        if (!setup.isRequired()) {
            call.error(409, "setup already completed");
            return;
        }
        if (!HandlerSupport.requireJson(call)) return;
        JsonNode body = call.jsonBody();
        String claim = setup.verify(text(body, "code"));
        if (claim == null) {
            call.error(403, "invalid setup code");
            return;
        }

        // empty fields keep what is stored (e.g. values taken over from app.config)
        settings.update(blankToNull(text(body, "googleClientId")), blankToNull(text(body, "googleClientSecret")),
                text(body, "googleRedirectUri"), null);
        if (!google.isConfigured()) {
            call.error(400, "Google client ID and secret are required");
            return;
        }

        call.addHeader("Set-Cookie", SetupService.CLAIM_COOKIE + "=" + claim + "; Path=/auth; Max-Age="
                + SetupService.CLAIM_TTL_MS / 1000 + "; HttpOnly; SameSite=Lax" + (secureCookie ? "; Secure" : ""));
        call.json(200, Map.of("next", "/auth/login"));
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body == null ? null : body.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
