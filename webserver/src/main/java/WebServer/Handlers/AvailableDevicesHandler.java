package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;

/** GET /api/devices/available - devices the user can still link (identity only). */
public class AvailableDevicesHandler implements Handler {

    private final Repositories repos;

    public AvailableDevicesHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        call.json(200, repos.devices().listAvailable(call.user().userId));
    }
}
