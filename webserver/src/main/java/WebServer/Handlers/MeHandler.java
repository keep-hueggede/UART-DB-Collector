package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.SessionManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/auth/me - profile of the logged-in user, its role and whether an
 * admin has approved it yet (at least one assigned device).
 */
public class MeHandler implements Handler {

    private final Repositories repos;

    public MeHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        SessionManager.Session user = call.user();
        boolean admin = call.isAdmin();
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", user.userId);
        me.put("email", user.email);
        me.put("name", user.name);
        me.put("picture", user.pictureUrl);
        me.put("expiresAt", user.expiresAt);
        me.put("role", admin ? "ADMIN" : "USER");
        me.put("approved", admin || repos.devices().countAssigned(user.userId) > 0);
        call.json(200, me);
    }
}
