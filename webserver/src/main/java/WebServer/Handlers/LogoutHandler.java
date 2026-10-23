package WebServer.Handlers;

import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.SessionManager;

import java.io.IOException;
import java.util.Map;

/** POST /auth/logout - invalidate the session server-side and clear the cookie. */
public class LogoutHandler implements Handler {

    private final SessionManager sessions;
    private final boolean secureCookie;

    public LogoutHandler(SessionManager sessions, boolean secureCookie) {
        this.sessions = sessions;
        this.secureCookie = secureCookie;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        sessions.invalidate(call.header("Cookie"), call.header("Authorization"));
        call.addHeader("Set-Cookie", SessionManager.clearCookie(secureCookie));
        call.json(200, Map.of("status", "logged-out"));
    }
}
