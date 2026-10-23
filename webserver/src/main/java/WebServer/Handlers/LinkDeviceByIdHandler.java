package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.Map;

/** POST /api/devices/{id}/link - link an existing device by id. */
public class LinkDeviceByIdHandler implements Handler {

    private final Repositories repos;

    public LinkDeviceByIdHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer id = call.pathId();
        if (id == null) {
            call.error(404, "not found");
            return;
        }
        Map<String, Object> device = repos.devices().link(call.user().userId, id, null);
        if (device == null) {
            call.error(404, "device not found");
            return;
        }
        call.json(200, device);
    }
}
