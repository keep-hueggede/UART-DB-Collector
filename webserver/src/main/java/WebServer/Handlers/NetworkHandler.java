package WebServer.Handlers;

import ORMUtil.AppVersion;
import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/admin/network (admin) - web server, collectors and their sensors with versions. */
public class NetworkHandler implements Handler {

    private final Repositories repos;

    public NetworkHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        long now = System.currentTimeMillis();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("version", AppVersion.get());
        server.put("time", now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", server);
        out.putAll(repos.network().map(now));
        call.json(200, out);
    }
}
