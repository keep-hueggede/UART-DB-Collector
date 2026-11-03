package WebServer.Handlers;

import Model.UserAccount;
import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * PUT /api/admin/users/{id}/role {"role": "ADMIN" | "USER"} (admin) - promote or
 * demote an account. The last admin cannot be demoted (409), so nobody locks out.
 */
public class SetRoleHandler implements Handler {

    private final Repositories repos;

    public SetRoleHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Integer id = call.pathId();
        if (id == null) {
            call.error(404, "not found");
            return;
        }
        if (!HandlerSupport.requireJson(call)) return;
        JsonNode body = call.jsonBody();
        JsonNode roleNode = body == null ? null : body.get("role");
        UserAccount.Role role;
        try {
            role = UserAccount.Role.valueOf(roleNode == null ? "" : roleNode.asText());
        } catch (IllegalArgumentException e) {
            call.error(400, "role must be ADMIN or USER");
            return;
        }
        switch (repos.users().setRole(id, role)) {
            case NOT_FOUND -> call.error(404, "user not found");
            case LAST_ADMIN -> call.error(409, "the last admin cannot be demoted");
            default -> call.json(200, Map.of("id", id, "role", role.name()));
        }
    }
}
