package WebServer.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps method + path pattern to a {@link Handler}. Patterns use {name} for a
 * single path segment, e.g. "/api/devices/{id}/link". Among matching patterns
 * the most specific one (fewest placeholders) wins, so "/api/devices/available"
 * is never taken for a device id.
 */
public final class Router {

    /** Who may call a route; checked centrally by the web server before dispatch. */
    public enum Access { PUBLIC, USER, ADMIN }

    private record Route(String method, String[] segments, int wildcards, Handler handler, Access access) {}

    /** Result of a lookup: a handler, or the status to answer with (404/405). */
    public record Match(Handler handler, Map<String, String> params, int status, Access access) {

        public boolean isPublic() {
            return access == Access.PUBLIC;
        }

        public boolean isAdminOnly() {
            return access == Access.ADMIN;
        }

        /** Runs the handler or answers 404 (with the given message) / 405. */
        public void dispatch(HttpCall call, String notFoundMessage) throws IOException {
            if (status == 404) {
                call.error(404, notFoundMessage);
            } else if (status == 405) {
                call.error(405, "method not allowed");
            } else {
                call.setParams(params);
                handler.handle(call);
            }
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String pattern, Handler handler) {
        return add("GET", pattern, handler, Access.USER);
    }

    public Router post(String pattern, Handler handler) {
        return add("POST", pattern, handler, Access.USER);
    }

    public Router put(String pattern, Handler handler) {
        return add("PUT", pattern, handler, Access.USER);
    }

    public Router delete(String pattern, Handler handler) {
        return add("DELETE", pattern, handler, Access.USER);
    }

    /** Route that needs no login (e.g. liveness check, first-run setup). */
    public Router publicRoute(String method, String pattern, Handler handler) {
        return add(method, pattern, handler, Access.PUBLIC);
    }

    public Router publicGet(String pattern, Handler handler) {
        return publicRoute("GET", pattern, handler);
    }

    /** Route only for admins (403 for other users). */
    public Router admin(String method, String pattern, Handler handler) {
        return add(method, pattern, handler, Access.ADMIN);
    }

    private Router add(String method, String pattern, Handler handler, Access access) {
        String[] segments = pattern.split("/", -1);
        int wildcards = 0;
        for (String s : segments) if (isPlaceholder(s)) wildcards++;
        routes.add(new Route(method, segments, wildcards, handler, access));
        return this;
    }

    public Match match(String method, String path) {
        String[] parts = path.split("/", -1);
        int best = Integer.MAX_VALUE;
        Match found = null;
        boolean pathKnown = false;

        for (Route r : routes) {
            Map<String, String> params = bind(r.segments, parts);
            if (params == null || r.wildcards > best) continue;
            if (r.wildcards < best) {
                // a more specific pattern resets what less specific ones found
                best = r.wildcards;
                found = null;
            }
            pathKnown = true;
            if (found == null && r.method.equals(method)) {
                found = new Match(r.handler, params, 200, r.access);
            }
        }
        if (found != null) return found;
        return new Match(null, Map.of(), pathKnown ? 405 : 404, Access.USER);
    }

    private static Map<String, String> bind(String[] pattern, String[] parts) {
        if (pattern.length != parts.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            if (isPlaceholder(pattern[i])) {
                if (parts[i].isEmpty()) return null;
                params.put(pattern[i].substring(1, pattern[i].length() - 1), parts[i]);
            } else if (!pattern[i].equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    private static boolean isPlaceholder(String segment) {
        return segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}");
    }
}
