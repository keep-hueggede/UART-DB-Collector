import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.Repositories;
import WebServer.AppSettings;
import WebServer.GoogleAuth;
import WebServer.SessionManager;
import WebServer.SetupService;
import WebServer.WebServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** First-run setup over HTTP: empty database, no admin yet. */
public class SetupFlowTest {

    private ORMapper mapper;
    private WebServer server;
    private SetupService setup;
    private String base;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        mapper = new ORMapper("localhost", 3306, "setupflowtest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        mapper.executeHQL("DELETE FROM UserDevice");
        mapper.executeHQL("DELETE FROM UserAccount");
        mapper.executeHQL("DELETE FROM Setting");

        SessionManager sessions = new SessionManager();
        GoogleAuth google = new GoogleAuth("", "", "");
        Repositories repos = Repositories.of(mapper);
        AppSettings settings = new AppSettings(repos.settings(), google, sessions);
        settings.apply();
        setup = new SetupService(repos.users(), Files.createTempDirectory("setupflow"));
        setup.init();
        server = new WebServer(repos, sessions, google, settings, setup, "127.0.0.1", 0, "./does-not-exist", false);
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) server.stop();
        if (mapper != null) mapper.disconnect();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postSetup(String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/api/setup"))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String code() throws Exception {
        return Files.readString(setup.codeFile()).strip();
    }

    @Test
    public void statusTellsWhereTheCodeIs() throws Exception {
        JsonNode status = json.readTree(get("/api/setup").body());
        assertTrue(status.get("setupRequired").asBoolean());
        assertEquals(setup.codeFile().toString(), status.get("codeFile").asText());
        assertTrue(status.get("redirectUri").asText().endsWith("/auth/callback"));
        assertFalse(status.get("googleClientSecretSet").asBoolean());
        assertFalse(get("/api/setup").body().contains(code()), "the code itself is never served");
    }

    @Test
    public void wrongCodeIsRejected() throws Exception {
        String body = "{\"code\":\"AAAA-AAAA-AAAA-AAAA\",\"googleClientId\":\"id\",\"googleClientSecret\":\"s\"}";
        assertEquals(403, postSetup("application/json", body).statusCode());
        assertEquals(503, get("/auth/login").statusCode(), "nothing was stored");
        assertEquals(415, postSetup("text/plain", body).statusCode());
    }

    @Test
    public void correctCodeStoresGoogleClientAndSetsClaimCookie() throws Exception {
        HttpResponse<String> missing = postSetup("application/json", "{\"code\":\"" + code() + "\"}");
        assertEquals(400, missing.statusCode(), "Google client is required");

        HttpResponse<String> res = postSetup("application/json", "{\"code\":\"" + code()
                + "\",\"googleClientId\":\"id\",\"googleClientSecret\":\"secret\"}");
        assertEquals(200, res.statusCode());
        assertEquals("/auth/login", json.readTree(res.body()).get("next").asText());
        String cookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith(SetupService.CLAIM_COOKIE + "="), cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Path=/auth"));
        assertEquals(302, get("/auth/login").statusCode(), "login is possible now");
        // setup stays open until the Google login completes
        assertTrue(json.readTree(get("/api/setup").body()).get("setupRequired").asBoolean());
    }

    @Test
    public void dataStaysProtectedDuringSetup() throws Exception {
        assertEquals(401, get("/api/devices").statusCode());
        assertEquals(401, get("/api/admin/settings").statusCode());
    }
}
