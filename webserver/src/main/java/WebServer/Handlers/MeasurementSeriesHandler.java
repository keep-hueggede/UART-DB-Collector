package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.Map;

/**
 * GET /api/measurements?sensorId=&from=&to=&window=&limit= - time series with
 * min/max/avg/sum of one sensor of the user's devices.
 */
public class MeasurementSeriesHandler implements Handler {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LIMIT = 20000;

    private final Repositories repos;

    public MeasurementSeriesHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Map<String, String> query = call.query();
        String sensorId = query.get("sensorId");
        if (sensorId == null) {
            call.error(400, "missing query param: sensorId");
            return;
        }
        int sid = Integer.parseInt(sensorId);
        long now = System.currentTimeMillis();
        Long from = query.containsKey("from") ? Long.parseLong(query.get("from")) : null;
        Long to = query.containsKey("to") ? Long.parseLong(query.get("to")) : null;
        if (query.containsKey("window")) {
            from = now - Long.parseLong(query.get("window"));
            to = now;
        }
        int limit = DEFAULT_LIMIT;
        if (query.containsKey("limit")) {
            limit = Integer.parseInt(query.get("limit"));
            if (limit < 1 || limit > MAX_LIMIT) {
                call.error(400, "limit out of range");
                return;
            }
        }
        int userId = call.user().userId;
        Map<String, Object> out = repos.measurements().series(userId, sid, from, to, limit);
        if (out == null) {
            call.error(404, "not found");
            return;
        }
        out.put("sensor", repos.sensors().findLinked(userId, sid));
        call.json(200, out);
    }
}
