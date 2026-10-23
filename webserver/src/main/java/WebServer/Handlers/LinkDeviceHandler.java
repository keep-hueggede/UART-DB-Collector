package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/** POST /api/devices {"deviceId": n} or {"name": "..."} - link a device to the user. */
public class LinkDeviceHandler implements Handler {

    private final Repositories repos;

    public LinkDeviceHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        JsonNode body = call.jsonBody();
        Integer deviceId = body != null && body.hasNonNull("deviceId") ? body.get("deviceId").asInt() : null;
        String name = body != null && body.hasNonNull("name") ? body.get("name").asText() : null;
        if (deviceId == null && (name == null || name.isBlank())) {
            call.error(400, "deviceId or name required");
            return;
        }
        Map<String, Object> device = repos.devices().link(call.user().userId, deviceId, name);
        if (device == null) {
            call.error(404, "device not found");
            return;
        }
        call.json(200, device);
    }
}
