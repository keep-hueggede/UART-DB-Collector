package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;

/** GET /api/measurements/latest - latest value per sensor of the user's devices. */
public class LatestMeasurementsHandler implements Handler {

    private final Repositories repos;

    public LatestMeasurementsHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        call.json(200, repos.measurements().latest(call.user().userId));
    }
}
