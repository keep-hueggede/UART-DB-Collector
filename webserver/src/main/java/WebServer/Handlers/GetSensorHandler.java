package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.Map;

/** GET /api/sensors/{id} - one sensor; unowned ids answer like missing ones (no enumeration). */
public class GetSensorHandler implements Handler {

    private final Repositories repos;

    public GetSensorHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer id = call.pathId();
        Map<String, Object> sensor = id == null ? null : repos.sensors().findLinked(call.user().userId, id);
        if (sensor == null) {
            call.error(404, "not found");
            return;
        }
        call.json(200, sensor);
    }
}
