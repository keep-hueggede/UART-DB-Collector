import WebServer.Http.Handler;
import WebServer.Http.Router;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Route matching without a running HTTP server. */
public class Test_Router {

    private final Handler list = call -> {};
    private final Handler available = call -> {};
    private final Handler rename = call -> {};
    private final Handler link = call -> {};
    private final Handler health = call -> {};

    private Router router() {
        return new Router()
                .publicGet("/api/health", health)
                .get("/api/devices", list)
                .get("/api/devices/available", available)
                .put("/api/devices/{id}", rename)
                .post("/api/devices/{id}/link", link);
    }

    @Test
    public void exactAndPlaceholderRoutes() {
        Router r = router();
        assertSame(list, r.match("GET", "/api/devices").handler());
        Router.Match m = r.match("PUT", "/api/devices/42");
        assertSame(rename, m.handler());
        assertEquals("42", m.params().get("id"));
        assertSame(link, r.match("POST", "/api/devices/7/link").handler());
    }

    @Test
    public void literalSegmentWinsOverPlaceholder() {
        Router r = router();
        assertSame(available, r.match("GET", "/api/devices/available").handler());
        // known literal path with the wrong method stays 405, not "device id available"
        assertEquals(405, r.match("PUT", "/api/devices/available").status());
    }

    @Test
    public void wrongMethodIs405UnknownPathIs404() {
        Router r = router();
        assertEquals(405, r.match("GET", "/api/devices/5").status());
        assertEquals(405, r.match("PUT", "/api/devices/5/link").status());
        assertEquals(404, r.match("GET", "/api/nope").status());
        assertEquals(404, r.match("GET", "/api/devices/5/x/y").status());
        assertEquals(404, r.match("PUT", "/api/devices/").status());
    }

    @Test
    public void onlyHealthIsPublic() {
        Router r = router();
        assertTrue(r.match("GET", "/api/health").isPublic());
        assertFalse(r.match("GET", "/api/devices").isPublic());
        assertFalse(r.match("GET", "/api/nope").isPublic());
        assertFalse(r.match("POST", "/api/health").isPublic());
    }

    @Test
    public void placeholderKeepsRawSegment() {
        // validation (numeric ids) is the handler's job
        assertEquals("abc", router().match("PUT", "/api/devices/abc").params().get("id"));
    }
}
