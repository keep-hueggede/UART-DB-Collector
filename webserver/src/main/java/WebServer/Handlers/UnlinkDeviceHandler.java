package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.Map;

/** DELETE /api/devices/{id} - remove the link between the user and a device. */
public class UnlinkDeviceHandler implements Handler {

    private final Repositories repos;

    public UnlinkDeviceHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer id = call.pathId();
        if (id == null) {
            call.error(404, "not found");
            return;
        }
        call.json(200, Map.of("unlinked", repos.devices().unlink(call.user().userId, id)));
    }
}
