package WebServer.Handlers;

import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.SessionManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/auth/me - profile of the logged-in user. */
public class MeHandler implements Handler {

    @Override
    public void handle(HttpCall call) throws IOException {
        SessionManager.Session user = call.user();
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", user.userId);
        me.put("email", user.email);
        me.put("name", user.name);
        me.put("picture", user.pictureUrl);
        me.put("expiresAt", user.expiresAt);
        call.json(200, me);
    }
}
