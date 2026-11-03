package WebServer.Handlers;

import Repository.Repositories;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;

/** GET /api/admin/users (admin) - all accounts with role and assigned device ids. */
public class AdminUsersHandler implements Handler {

    private final Repositories repos;

    public AdminUsersHandler(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        call.json(200, repos.users().listAll());
    }
}
