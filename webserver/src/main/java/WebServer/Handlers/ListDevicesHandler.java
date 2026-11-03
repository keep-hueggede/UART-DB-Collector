package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;

/** GET /api/devices - devices the user may see (admin: all). */
public class ListDevicesHandler implements Handler {

    private final Repositories repos;

    public ListDevicesHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        call.json(200, repos.devices().listVisible(call.viewer()));
    }
}
