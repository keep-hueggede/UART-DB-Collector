package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PUT /api/devices/{id} {"name": "..."} - rename a device the user has linked.
 * Requires application/json, so CORS preflights it; only the owner may rename
 * (404 otherwise).
 */
public class RenameDeviceHandler implements Handler {

    private static final int MAX_NAME_LENGTH = 40;

    private final Repositories repos;

    public RenameDeviceHandler(Repositories repos) {
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
        JsonNode nameNode = body == null ? null : body.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            call.error(400, "missing field: name");
            return;
        }
        String name = nameNode.asText().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            call.error(400, "name must be 1-" + MAX_NAME_LENGTH + " characters without control characters");
            return;
        }
        switch (repos.devices().rename(call.user().userId, id, name)) {
            case NOT_FOUND -> call.error(404, "device not found");
            case NAME_TAKEN -> call.error(409, "name already in use");
            default -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("name", name);
                call.json(200, out);
            }
        }
    }
}
