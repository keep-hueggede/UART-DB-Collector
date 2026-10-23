package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PUT /api/sensors/{id}/calibration {"factor": 1.0, "offset": -1.0} - display
 * correction of a sensor the user has linked (shown = raw * factor + offset).
 * Requires application/json, so CORS preflights it; unowned ids answer 404.
 */
public class SetCalibrationHandler implements Handler {

    private static final double MAX_ABS_OFFSET = 10_000;
    private static final double MIN_ABS_FACTOR = 0.001;
    private static final double MAX_ABS_FACTOR = 1_000;

    private final Repositories repos;

    public SetCalibrationHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer id = call.pathId();
        if (id == null) {
            call.error(404, "not found");
            return;
        }
        String ctype = call.header("Content-Type");
        if (ctype == null || !ctype.toLowerCase().startsWith("application/json")) {
            call.error(415, "content type must be application/json");
            return;
        }
        JsonNode body = call.jsonBody();
        JsonNode f = body == null ? null : body.get("factor");
        JsonNode o = body == null ? null : body.get("offset");
        if (f == null || o == null || !f.isNumber() || !o.isNumber()) {
            call.error(400, "factor and offset must be numbers");
            return;
        }
        double factor = f.asDouble(), offset = o.asDouble();
        if (!Double.isFinite(factor) || !Double.isFinite(offset)
                || Math.abs(factor) < MIN_ABS_FACTOR || Math.abs(factor) > MAX_ABS_FACTOR
                || Math.abs(offset) > MAX_ABS_OFFSET) {
            call.error(400, "factor must be " + MIN_ABS_FACTOR + ".." + MAX_ABS_FACTOR
                    + " (or negative), offset at most " + MAX_ABS_OFFSET);
            return;
        }
        if (!repos.sensors().setCalibration(call.user().userId, id, factor, offset)) {
            call.error(404, "not found");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("factor", factor);
        out.put("offset", offset);
        call.json(200, out);
    }
}
