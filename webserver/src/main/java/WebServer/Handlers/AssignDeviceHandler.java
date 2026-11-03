package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.Map;

/**
 * PUT /api/admin/users/{id}/devices/{deviceId} (admin) - give a user access to a
 * device and all its sensors/actors. DELETE on the same path removes it.
 */
public class AssignDeviceHandler implements Handler {

    private final Repositories repos;

    public AssignDeviceHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer userId = call.pathId();
        Integer deviceId = call.pathInt("deviceId");
        if (userId == null || deviceId == null) {
            call.error(404, "not found");
            return;
        }
        if ("DELETE".equals(call.method())) {
            call.json(200, Map.of("removed", repos.devices().unassign(userId, deviceId)));
            return;
        }
        switch (repos.devices().assign(userId, deviceId)) {
            case USER_NOT_FOUND -> call.error(404, "user not found");
            case DEVICE_NOT_FOUND -> call.error(404, "device not found");
            default -> call.json(200, Map.of("userId", userId, "deviceId", deviceId));
        }
    }
}
