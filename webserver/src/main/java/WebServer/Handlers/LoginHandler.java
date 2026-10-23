package WebServer.Handlers;

import WebServer.GoogleAuth;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.OAuthStateStore;

import java.io.IOException;

/** GET /auth/login - redirect to Google with a one-time, cookie-bound state. */
public class LoginHandler implements Handler {

    static final String STATE_COOKIE = "UARTDB_STATE";
    private static final long STATE_COOKIE_TTL_SEC = OAuthStateStore.STATE_TTL_MS / 1000;

    private final GoogleAuth google;
    private final OAuthStateStore states;
    private final boolean secureCookie;

    public LoginHandler(GoogleAuth google, OAuthStateStore states, boolean secureCookie) {
        this.google = google;
        this.states = states;
        this.secureCookie = secureCookie;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        if (!google.isConfigured()) {
            call.error(503, "Google login is not configured (GOOGLE.CLIENT_ID / GOOGLE.CLIENT_SECRET)");
            return;
        }
        String state = states.issue();
        String redirectUri = google.redirectUri(call.scheme(), call.header("Host"));

        call.addHeader("Set-Cookie", STATE_COOKIE + "=" + state + "; Path=/auth; Max-Age=" + STATE_COOKIE_TTL_SEC
                + "; HttpOnly; SameSite=Lax" + (secureCookie ? "; Secure" : ""));
        call.redirect(google.loginUrl(redirectUri, state));
    }
}
