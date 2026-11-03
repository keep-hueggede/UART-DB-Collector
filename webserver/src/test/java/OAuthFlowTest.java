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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full Google login against a local stub of Google's token and userinfo
 * endpoints: state check, identity checks and claiming the first admin.
 */
public class OAuthFlowTest {

    private static final String CLIENT_ID = "test-client";

    private ORMapper mapper;
    private HttpServer googleStub;
    private WebServer server;
    private SetupService setup;
    private String base;

    // what the stub answers
    private int tokenStatus;
    private String idTokenSub;
    private String idTokenAud;
    private String userSub;
    private String userEmail;
    private boolean emailVerified;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        mapper = new ORMapper("localhost", 3306, "oauthtest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        for (String e : new String[]{"UserDevice", "UserAccount", "Setting"}) mapper.executeHQL("DELETE FROM " + e);

        stubAnswers("sub-1", "first@test.example");
        googleStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        googleStub.createContext("/token", this::token);
        googleStub.createContext("/userinfo", this::userinfo);
        googleStub.start();
        String stub = "http://127.0.0.1:" + googleStub.getAddress().getPort();

        SessionManager sessions = new SessionManager();
        GoogleAuth google = new GoogleAuth("", "", "", stub + "/token", stub + "/userinfo");
        Repositories repos = Repositories.of(mapper);
        AppSettings settings = new AppSettings(repos.settings(), google, sessions);
        settings.apply();
        setup = new SetupService(repos.users(), Files.createTempDirectory("oauth"));
        setup.init();
        server = new WebServer(repos, sessions, google, settings, setup, "127.0.0.1", 0, "./does-not-exist", false);
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) server.stop();
        if (googleStub != null) googleStub.stop(0);
        if (mapper != null) mapper.disconnect();
    }

    private void stubAnswers(String sub, String email) {
        tokenStatus = 200;
        idTokenSub = sub;
        idTokenAud = CLIENT_ID;
        userSub = sub;
        userEmail = email;
        emailVerified = true;
    }

    // ----------------------------------------------------------- google stub

    private void token(HttpExchange ex) throws java.io.IOException {
        String claims = "{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + idTokenAud + "\",\"sub\":\""
                + idTokenSub + "\",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}";
        String jwt = "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
        reply(ex, tokenStatus, "{\"access_token\":\"at\",\"id_token\":\"" + jwt + "\"}");
    }

    private void userinfo(HttpExchange ex) throws java.io.IOException {
        boolean authorized = "Bearer at".equals(ex.getRequestHeaders().getFirst("Authorization"));
        reply(ex, authorized ? 200 : 401, "{\"sub\":\"" + userSub + "\",\"email\":\"" + userEmail
                + "\",\"email_verified\":" + emailVerified + ",\"name\":\"Test\"}");
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ---------------------------------------------------------------- client

    private HttpResponse<String> send(String method, String path, String cookie, String body) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) rb.header("Cookie", cookie);
        if (body != null) rb.header("Content-Type", "application/json");
        rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieValue(HttpResponse<?> res, String name) {
        for (String c : res.headers().allValues("Set-Cookie")) {
            if (c.startsWith(name + "=")) return c.substring(name.length() + 1, c.indexOf(';'));
        }
        return null;
    }

    /** Claims setup with the code from the file; returns the claim cookie value. */
    private String claimSetup() throws Exception {
        String code = Files.readString(setup.codeFile()).strip();
        HttpResponse<String> res = send("POST", "/api/setup", null, "{\"code\":\"" + code
                + "\",\"googleClientId\":\"" + CLIENT_ID + "\",\"googleClientSecret\":\"s\"}");
        assertEquals(200, res.statusCode(), res.body());
        return cookieValue(res, SetupService.CLAIM_COOKIE);
    }

    private void configureGoogle() throws Exception {
        claimSetup();   // stores the client; the claim itself is simply not used
    }

    /** /auth/login then /auth/callback; returns the callback response. */
    private HttpResponse<String> login(String extraCookie) throws Exception {
        HttpResponse<String> login = send("GET", "/auth/login", null, null);
        assertEquals(302, login.statusCode());
        Matcher m = Pattern.compile("[?&]state=([^&]+)").matcher(login.headers().firstValue("Location").orElse(""));
        assertTrue(m.find());
        String state = java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        String cookie = "UARTDB_STATE=" + cookieValue(login, "UARTDB_STATE") + (extraCookie == null ? "" : "; " + extraCookie);
        return send("GET", "/auth/callback?code=c&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8), cookie, null);
    }

    private JsonNode me(HttpResponse<String> callback) throws Exception {
        String location = callback.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("/#token="), location);
        var req = HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .header("Authorization", "Bearer " + location.substring("/#token=".length())).build();
        return json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private static void assertFailed(HttpResponse<String> callback) {
        assertEquals(302, callback.statusCode());
        assertEquals("/#auth-error", callback.headers().firstValue("Location").orElse(""));
        assertTrue(callback.headers().allValues("Set-Cookie").stream().noneMatch(c -> c.startsWith(SessionManager.COOKIE_NAME + "=")),
                "no session on failure");
    }

    // ----------------------------------------------------------------- tests

    @Test
    public void setupClaimMakesFirstLoginAdmin() throws Exception {
        String claim = claimSetup();
        HttpResponse<String> cb = login(SetupService.CLAIM_COOKIE + "=" + claim);
        JsonNode me = me(cb);
        assertEquals("ADMIN", me.get("role").asText());
        assertEquals("first@test.example", me.get("email").asText());
        assertTrue(cb.headers().allValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith(SetupService.CLAIM_COOKIE + "=;")), "claim cookie is cleared");

        assertFalse(setup.isRequired());
        assertFalse(Files.exists(setup.codeFile()));
        assertFalse(json.readTree(send("GET", "/api/setup", null, null).body()).get("setupRequired").asBoolean());

        // everybody after that is a normal user
        stubAnswers("sub-2", "second@test.example");
        assertEquals("USER", me(login(SetupService.CLAIM_COOKIE + "=" + claim)).get("role").asText());
    }

    @Test
    public void loginWithoutClaimDoesNotFinishSetup() throws Exception {
        configureGoogle();
        JsonNode me = me(login(null));
        assertEquals("USER", me.get("role").asText());
        assertFalse(me.get("approved").asBoolean());
        assertTrue(setup.isRequired(), "setup stays open for the code holder");
    }

    @Test
    public void unverifiedEmailGetsNoSession() throws Exception {
        configureGoogle();
        emailVerified = false;
        assertFailed(login(null));
        assertEquals(0L, (long) mapper.readInSession(s -> s.createQuery("SELECT COUNT(u) FROM UserAccount u", Long.class).getSingleResult()));
    }

    @Test
    public void mismatchingIdentitiesAreRejected() throws Exception {
        configureGoogle();
        idTokenSub = "someone-else";
        assertFailed(login(null));

        stubAnswers("sub-1", "first@test.example");
        idTokenAud = "other-client";
        assertFailed(login(null));
    }

    @Test
    public void failedTokenExchangeIsRejected() throws Exception {
        configureGoogle();
        tokenStatus = 400;
        assertFailed(login(null));
    }

    @Test
    public void stateIsBoundToCookieAndSingleUse() throws Exception {
        configureGoogle();
        HttpResponse<String> login = send("GET", "/auth/login", null, null);
        Matcher m = Pattern.compile("[?&]state=([^&]+)").matcher(login.headers().firstValue("Location").orElse(""));
        assertTrue(m.find());
        String state = m.group(1);
        String cookie = "UARTDB_STATE=" + cookieValue(login, "UARTDB_STATE");

        assertFailed(send("GET", "/auth/callback?code=c&state=" + state, null, null));        // no cookie
        assertFailed(send("GET", "/auth/callback?code=c&state=forged", cookie, null));        // wrong state
        assertEquals("first@test.example",
                me(send("GET", "/auth/callback?code=c&state=" + state, cookie, null)).get("email").asText());
        assertFailed(send("GET", "/auth/callback?code=c&state=" + state, cookie, null));      // replay
    }

    @Test
    public void googleErrorRedirectsToLoginError() throws Exception {
        configureGoogle();
        assertFailed(send("GET", "/auth/callback?error=access_denied", null, null));
    }
}
