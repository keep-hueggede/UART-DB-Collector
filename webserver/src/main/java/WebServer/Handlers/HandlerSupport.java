package WebServer.Handlers;

import WebServer.Http.HttpCall;

import java.io.IOException;

/** Checks shared by handlers that change data. */
final class HandlerSupport {

    private HandlerSupport() {}

    /**
     * Writes need application/json: browsers then always send a CORS preflight,
     * so foreign pages cannot trigger them with a plain form post (CSRF).
     * Answers 415 and returns false otherwise.
     */
    static boolean requireJson(HttpCall call) throws IOException {
        String ctype = call.header("Content-Type");
        if (ctype == null || !ctype.toLowerCase().startsWith("application/json")) {
            call.error(415, "content type must be application/json");
            return false;
        }
        return true;
    }
}
