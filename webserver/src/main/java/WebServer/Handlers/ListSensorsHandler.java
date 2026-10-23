package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;

/** GET /api/sensors - sensors of the user's devices. */
public class ListSensorsHandler implements Handler {

    private final Repositories repos;

    public ListSensorsHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        call.json(200, repos.sensors().listLinked(call.user().userId));
    }
}
