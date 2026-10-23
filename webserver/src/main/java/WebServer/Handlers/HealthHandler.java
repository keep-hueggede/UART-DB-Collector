package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/health - public liveness check, no data. */
public class HealthHandler implements Handler {

    private final Repositories repos;

    public HealthHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("devices", repos.devices().count());
        out.put("time", System.currentTimeMillis());
        call.json(200, out);
    }
}
