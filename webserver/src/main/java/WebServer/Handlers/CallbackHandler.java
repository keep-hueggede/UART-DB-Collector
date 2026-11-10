package WebServer.Handlers;

import Model.UserAccount;
import Repository.Repositories;
import WebServer.AdminNotifier;
import WebServer.GoogleAuth;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.OAuthStateStore;
import WebServer.SessionManager;
import WebServer.SetupService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** GET /auth/callback - Google redirects here; checks the state and starts a session. */
public class CallbackHandler implements Handler {

    private static final String FAILED = "/#auth-error";

    private final GoogleAuth google;
    private final OAuthStateStore states;
    private final Repositories repos;
    private final SessionManager sessions;
    private final SetupService setup;
    private final AdminNotifier notifier;
    private final boolean secureCookie;

    public CallbackHandler(GoogleAuth google, OAuthStateStore states, Repositories repos,
                           SessionManager sessions, SetupService setup, AdminNotifier notifier, boolean secureCookie) {
        this.google = google;
        this.states = states;
        this.repos = repos;
        this.sessions = sessions;
        this.setup = setup;
        this.notifier = notifier;
        this.secureCookie = secureCookie;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Map<String, String> query = call.query();
        String error = query.get("error");
        if (error != null && !error.isEmpty()) {
            call.redirect(FAILED);
            return;
        }

        String stateParam = query.get("state");
        String cookies = call.header("Cookie");
        String stateCookie = SessionManager.extractCookie(cookies == null ? "" : cookies, LoginHandler.STATE_COOKIE);

        // one-time, server-side known and cookie-bound state (CSRF protection)
        boolean stateOk = stateParam != null && stateCookie != null
                && MessageDigest.isEqual(stateParam.getBytes(StandardCharsets.UTF_8),
                                         stateCookie.getBytes(StandardCharsets.UTF_8))
                && states.consume(stateParam);
        String code = query.get("code");
        if (!stateOk || code == null || code.isEmpty()) {
            call.redirect(FAILED);
            return;
        }

        try {
            String redirectUri = google.redirectUri(call.scheme(), call.header("Host"));
            GoogleAuth.GoogleUser gu = google.exchangeCode(code, redirectUri);
            UserAccount user = repos.users().loginOrCreate(gu.sub, gu.email, gu.name, gu.pictureUrl);
            // first-run setup: the account that proved the setup code becomes the first admin
            boolean firstRun = setup.isRequired();
            if (user.isNewAccount() && !firstRun) {
                notifier.newAccount(user, redirectUri.substring(0, redirectUri.length() - "/auth/callback".length()));
            }
            if (firstRun) {
                String claim = SessionManager.extractCookie(cookies == null ? "" : cookies, SetupService.CLAIM_COOKIE);
                if (setup.completeIfClaimed(claim, user.getId())) {
                    System.out.println("Einrichtung abgeschlossen: erster Admin ist " + gu.email);
                    call.addHeader("Set-Cookie", SetupService.CLAIM_COOKIE + "=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Lax"
                            + (secureCookie ? "; Secure" : ""));
                }
            }
            String token = sessions.create(user);

            call.addHeader("Set-Cookie",
                    SessionManager.buildCookie(token, secureCookie, sessions.getTtlMs() / 1000));
            call.addHeader("Set-Cookie", LoginHandler.STATE_COOKIE + "=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Lax"
                    + (secureCookie ? "; Secure" : ""));
            // token in the fragment: never sent to the server, picked up by the
            // PWA (bearer mode) and removed from the URL right away
            call.redirect("/#token=" + token);
        } catch (Exception e) {
            // log details server-side only, never to the client
            System.err.println("Google login failed: " + e.getMessage());
            call.redirect(FAILED);
        }
    }
}
