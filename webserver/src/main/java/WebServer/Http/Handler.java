package WebServer.Http;

import java.io.IOException;

/** One endpoint (Command pattern): handles a request that the router matched. */
@FunctionalInterface
public interface Handler {
    void handle(HttpCall call) throws IOException;
}
