import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
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
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the web server: seeds users/devices/assignments through
 * the ORMapper, boots the server on a random port and verifies auth, roles,
 * per-user data scoping, admin functions and the static PWA over HTTP.
 */
public class WebServerTest {

    private ORMapper mapper;
    private WebServer server;
    private SessionManager sessions;
    private String base;
    private String aliceToken;
    private String bobToken;
    private String adminToken;   // carol
    private String daveToken;    // registered, nothing assigned yet
    private SetupService setup;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        this.mapper = new ORMapper("localhost", 3306, "webtest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        this.mapper.connect();
        seedDatabase();

        this.sessions = new SessionManager();
        // Google not configured in tests -> flow endpoints must degrade safely
        GoogleAuth google = new GoogleAuth("", "", "");
        Repositories repos = Repositories.of(this.mapper);
        AppSettings settings = new AppSettings(repos.settings(), google, sessions);
        settings.apply();
        this.setup = new SetupService(repos.users(), Files.createTempDirectory("setup"));
        this.setup.init();   // an admin exists -> setup stays closed
        this.server = new WebServer(repos, sessions, google, settings, setup,
                "127.0.0.1", 0, "./does-not-exist", false);
        this.server.start();
        this.base = "http://127.0.0.1:" + this.server.getPort();

        this.aliceToken = sessions.create(findUser("alice@test.example"));
        this.bobToken = sessions.create(findUser("bob@test.example"));
        this.adminToken = sessions.create(findUser("carol@test.example"));
        this.daveToken = sessions.create(findUser("dave@test.example"));
    }

    @AfterEach
    public void tearDown() {
        if (this.server != null) this.server.stop();
        if (this.mapper != null) this.mapper.disconnect();
    }

    // ------------------------------------------------------------- seeding

    private void seedDatabase() {
        var session = this.mapper.getSession();
        session.beginTransaction();
        session.createQuery("DELETE FROM UserDevice").executeUpdate();
        session.createQuery("DELETE FROM Measurement").executeUpdate();
        session.createQuery("DELETE FROM Sensor").executeUpdate();
        session.createQuery("DELETE FROM Device").executeUpdate();
        session.createQuery("DELETE FROM UserAccount").executeUpdate();
        session.createQuery("DELETE FROM Setting").executeUpdate();
        session.getTransaction().commit();

        UserAccount alice = user("sub-alice", "alice@test.example", "Alice", UserAccount.Role.USER);
        UserAccount bob = user("sub-bob", "bob@test.example", "Bob", UserAccount.Role.USER);
        user("sub-carol", "carol@test.example", "Carol", UserAccount.Role.ADMIN);
        user("sub-dave", "dave@test.example", "Dave", UserAccount.Role.USER);

        Device wetter1 = seedDevice("wetter1", alice);   // owned by alice
        Device wetter2 = seedDevice("wetter2", bob);     // owned by bob
        Device wetter3 = new Device("wetter3", Device.DeviceType.SENSOR, "unowned");
        this.mapper.persist(wetter3);                    // owned by nobody

        seedSensor(wetter1, Sensor.SensorType.TEMPERATURE, "C", 20.0, 22.0, 24.0);
        seedSensor(wetter1, Sensor.SensorType.RAIN, "mm", 1.5);
        seedSensor(wetter2, Sensor.SensorType.TEMPERATURE, "C", 5.0, 7.0);

        // deterministic timestamps for range assertions
        setTimestamp(20.0, "2026-01-01 10:00:00");
        setTimestamp(22.0, "2026-01-01 11:00:00");
        setTimestamp(24.0, "2026-01-01 12:00:00");
        this.mapper.clear();
    }

    private UserAccount user(String sub, String email, String name, UserAccount.Role role) {
        UserAccount u = new UserAccount(sub, email, name, "");
        u.setRole(role);
        this.mapper.persist(u);
        return u;
    }

    private Device seedDevice(String name, UserAccount owner) {
        Device d = new Device(name, Device.DeviceType.SENSOR, "wald");
        d.setOnline(true);
        d.setLastSeen(new Date());
        this.mapper.persist(d);
        this.mapper.persist(new UserDevice(owner, d));
        return d;
    }

    private void seedSensor(Device device, Sensor.SensorType type, String unit, double... values) {
        Sensor s = new Sensor(device, type, type.name());
        this.mapper.persist(s);
        for (double v : values) {
            this.mapper.persist(new Measurement(s, v));
        }
    }

    private void setTimestamp(double value, String timestamp) {
        var session = this.mapper.getSession();
        session.beginTransaction();
        session.createQuery("UPDATE Measurement SET timestamp = :ts WHERE value = :v")
                .setParameter("ts", Timestamp.valueOf(timestamp))
                .setParameter("v", value)
                .executeUpdate();
        session.getTransaction().commit();
    }

    private UserAccount findUser(String email) {
        return this.mapper.readInSession(s -> s.createQuery(
                        "FROM UserAccount u WHERE u.email = :email", UserAccount.class)
                .setParameter("email", email)
                .uniqueResult());
    }

    private int sensorIdByType(String deviceName, String type) throws Exception {
        JsonNode body = json.readTree(get("/api/sensors", aliceToken).body());
        for (JsonNode s : body) {
            if (type.equals(s.get("sensorType").asText())
                    && deviceName.equals(s.get("deviceName").asText())) {
                return s.get("id").asInt();
            }
        }
        fail("sensor not found: " + deviceName + "/" + type);
        return -1;
    }

    // -------------------------------------------------------------- http

    private HttpResponse<String> get(String path, String token) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(this.base + path)).GET();
        if (token != null) rb.header("Authorization", "Bearer " + token);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Content-Type", "application/json");
        if (token != null) rb.header("Authorization", "Bearer " + token);
        return http.send(rb.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(this.base + path));
        if (token != null) rb.header("Authorization", "Bearer " + token);
        return http.send(rb.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String token, String contentType, String body)
            throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(this.base + path))
                .method("PUT", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) rb.header("Authorization", "Bearer " + token);
        if (contentType != null) rb.header("Content-Type", contentType);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int userId(String email) {
        return findUser(email).getId();
    }

    private int deviceId(String name, String token) throws Exception {
        JsonNode body = json.readTree(get("/api/devices", token).body());
        for (JsonNode d : body) {
            if (name.equals(d.get("name").asText())) return d.get("id").asInt();
        }
        fail("device not found: " + name);
        return -1;
    }

    // ------------------------------------------------------------ security

    @Test
    public void dataEndpointsRequireAuthentication() throws Exception {
        for (String path : List.of("/api/devices", "/api/sensors",
                "/api/measurements/latest", "/api/measurements?sensorId=1")) {
            HttpResponse<String> res = get(path, null);
            assertEquals(401, res.statusCode(), path);
            JsonNode body = json.readTree(res.body());
            assertEquals("unauthorized", body.get("error").asText());
            assertFalse(body.get("authConfigured").asBoolean(), "test google is not configured");
        }
        assertEquals(401, get("/api/auth/me", null).statusCode());
    }

    @Test
    public void healthIsPublicButLeaksNoData() throws Exception {
        HttpResponse<String> res = get("/api/health", null);
        assertEquals(200, res.statusCode());
        JsonNode body = json.readTree(res.body());
        assertEquals("ok", body.get("status").asText());
        assertFalse(body.has("name"));
    }

    @Test
    public void noCredentialsHeaderForCrossOriginCookies() throws Exception {
        HttpResponse<String> res = get("/api/devices", aliceToken);
        assertTrue(res.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertTrue(res.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(),
                "cookies must never work cross-origin");
    }

    @Test
    public void securityHeadersPresent() throws Exception {
        HttpResponse<String> res = get("/", null);
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(""));
        assertTrue(res.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
    }

    @Test
    public void googleLoginEndpointsFailSafeWhenUnconfigured() throws Exception {
        assertEquals(503, get("/auth/login", null).statusCode());
        HttpResponse<String> cb = get("/auth/callback?code=x&state=whatever", null);
        // no valid state -> redirected to error, never a session
        assertEquals(302, cb.statusCode());
        assertEquals("/#auth-error", cb.headers().firstValue("Location").orElse(""));
    }

    @Test
    public void logoutInvalidatesSession() throws Exception {
        assertEquals(200, get("/api/auth/me", aliceToken).statusCode());
        HttpResponse<String> out = post("/auth/logout", aliceToken, null);
        assertEquals(200, out.statusCode());
        assertEquals(401, get("/api/auth/me", aliceToken).statusCode());
    }

    @Test
    public void sessionWorksViaCookieToo() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/api/devices"))
                .header("Cookie", SessionManager.COOKIE_NAME + "=" + aliceToken)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
    }

    @Test
    public void authMeReturnsProfile() throws Exception {
        JsonNode body = json.readTree(get("/api/auth/me", aliceToken).body());
        assertEquals("alice@test.example", body.get("email").asText());
        assertEquals("Alice", body.get("name").asText());
    }

    // -------------------------------------------------------- scoping

    @Test
    public void devicesAreScopedToOwner() throws Exception {
        JsonNode alice = json.readTree(get("/api/devices", aliceToken).body());
        assertEquals(1, alice.size());
        assertEquals("wetter1", alice.get(0).get("name").asText());
        assertEquals(2, alice.get(0).get("sensorCount").asInt());

        JsonNode bob = json.readTree(get("/api/devices", bobToken).body());
        assertEquals(1, bob.size());
        assertEquals("wetter2", bob.get(0).get("name").asText());
    }

    @Test
    public void sensorsAreScopedToOwner() throws Exception {
        JsonNode alice = json.readTree(get("/api/sensors", aliceToken).body());
        assertEquals(2, alice.size());
        for (JsonNode s : alice) {
            assertEquals("wetter1", s.get("deviceName").asText());
        }
        JsonNode bob = json.readTree(get("/api/sensors", bobToken).body());
        assertEquals(1, bob.size());
        assertEquals("wetter2", bob.get(0).get("deviceName").asText());
    }

    @Test
    public void latestMeasurementsAreScopedToOwner() throws Exception {
        JsonNode alice = json.readTree(get("/api/measurements/latest", aliceToken).body());
        assertEquals(2, alice.size());
        for (JsonNode m : alice) {
            assertEquals("wetter1", m.get("deviceName").asText());
        }
    }

    @Test
    public void calibrationCorrectsDisplayedValues() throws Exception {
        int temp = sensorIdByType("wetter1", "TEMPERATURE");
        String ct = "application/json";
        assertEquals(200, put("/api/sensors/" + temp + "/calibration", adminToken, ct,
                "{\"factor\":1.0,\"offset\":-1.0}").statusCode());

        JsonNode body = json.readTree(get("/api/measurements?sensorId=" + temp, aliceToken).body());
        assertEquals(19.0, body.get("points").get(0).get("v").asDouble(), 0.001);
        assertEquals(23.0, body.get("stats").get("max").asDouble(), 0.001);
        assertEquals(-1.0, body.get("sensor").get("calibration").get("offset").asDouble(), 0.001);
    }

    @Test
    public void calibrationRejectsBadInputAndNonAdmins() throws Exception {
        int temp = sensorIdByType("wetter1", "TEMPERATURE");
        String path = "/api/sensors/" + temp + "/calibration";
        String ct = "application/json";
        assertEquals(415, put(path, adminToken, "text/plain", "{\"factor\":1,\"offset\":0}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"factor\":1}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"factor\":\"1\",\"offset\":0}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"factor\":0,\"offset\":0}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"factor\":1,\"offset\":99999}").statusCode());
        assertEquals(404, put("/api/sensors/999999/calibration", adminToken, ct,
                "{\"factor\":1,\"offset\":-1}").statusCode());
        // users can read the sensor but must not change it
        assertEquals(403, put(path, aliceToken, ct, "{\"factor\":1,\"offset\":-1}").statusCode());
        assertEquals(403, put(path, bobToken, ct, "{\"factor\":1,\"offset\":-1}").statusCode());
        assertEquals(401, put(path, null, ct, "{\"factor\":1,\"offset\":-1}").statusCode());
        assertEquals(405, get(path, aliceToken).statusCode());
    }

    @Test
    public void foreignSensorIdsAnswerNotFound() throws Exception {
        // alice's temp sensor must not be visible to bob
        int aliceTemp = sensorIdByType("wetter1", "TEMPERATURE");
        assertEquals(404, get("/api/measurements?sensorId=" + aliceTemp, bobToken).statusCode());
        assertEquals(404, get("/api/sensors/" + aliceTemp, bobToken).statusCode());
        // unknown ids look the same as unowned ids
        assertEquals(404, get("/api/sensors/999999", aliceToken).statusCode());
    }

    @Test
    public void ownedSeriesAndStats() throws Exception {
        int tempId = sensorIdByType("wetter1", "TEMPERATURE");
        JsonNode body = json.readTree(get("/api/measurements?sensorId=" + tempId, aliceToken).body());
        assertEquals(3, body.get("points").size());
        assertEquals(20.0, body.get("points").get(0).get("v").asDouble(), 0.001);
        JsonNode stats = body.get("stats");
        assertEquals(3, stats.get("count").asInt());
        assertEquals(20.0, stats.get("min").asDouble(), 0.001);
        assertEquals(24.0, stats.get("max").asDouble(), 0.001);
        assertEquals(22.0, stats.get("avg").asDouble(), 0.001);
        assertEquals(66.0, stats.get("sum").asDouble(), 0.001);
    }

    @Test
    public void rangeFilterOnOwnSensor() throws Exception {
        int tempId = sensorIdByType("wetter1", "TEMPERATURE");
        long from = Timestamp.valueOf("2026-01-01 11:30:00").getTime();
        JsonNode body = json.readTree(
                get("/api/measurements?sensorId=" + tempId + "&from=" + from, aliceToken).body());
        assertEquals(1, body.get("points").size());
        assertEquals(24.0, body.get("points").get(0).get("v").asDouble(), 0.001);
    }

    @Test
    public void badRequests() throws Exception {
        assertEquals(400, get("/api/measurements", aliceToken).statusCode());
        assertEquals(400, get("/api/measurements?sensorId=abc", aliceToken).statusCode());
        assertEquals(404, get("/api/nope", aliceToken).statusCode());
        assertEquals(404, get("/api/sensors/abc", aliceToken).statusCode());
    }

    // --------------------------------------------------------------- roles

    @Test
    public void adminSeesAllDevicesUsersOnlyAssignedOnes() throws Exception {
        assertEquals(3, json.readTree(get("/api/devices", adminToken).body()).size(),
                "admin sees assigned and unassigned devices");
        assertEquals(3, json.readTree(get("/api/sensors", adminToken).body()).size());
        assertEquals(0, json.readTree(get("/api/devices", daveToken).body()).size());
        assertEquals(0, json.readTree(get("/api/sensors", daveToken).body()).size());
        assertEquals(0, json.readTree(get("/api/measurements/latest", daveToken).body()).size());
    }

    @Test
    public void authMeReportsRoleAndApproval() throws Exception {
        JsonNode admin = json.readTree(get("/api/auth/me", adminToken).body());
        assertEquals("ADMIN", admin.get("role").asText());
        assertTrue(admin.get("approved").asBoolean());
        JsonNode alice = json.readTree(get("/api/auth/me", aliceToken).body());
        assertEquals("USER", alice.get("role").asText());
        assertTrue(alice.get("approved").asBoolean());
        JsonNode dave = json.readTree(get("/api/auth/me", daveToken).body());
        assertFalse(dave.get("approved").asBoolean(), "nothing assigned yet -> waiting for approval");
    }

    @Test
    public void adminEndpointsAreForbiddenForUsers() throws Exception {
        String ct = "application/json";
        int dave = userId("dave@test.example");
        for (String path : List.of("/api/admin/users", "/api/admin/settings")) {
            assertEquals(403, get(path, aliceToken).statusCode(), path);
            assertEquals(401, get(path, null).statusCode(), path);
        }
        assertEquals(403, put("/api/admin/users/" + dave + "/role", aliceToken, ct,
                "{\"role\":\"ADMIN\"}").statusCode());
        assertEquals(403, put("/api/admin/users/" + dave + "/devices/1", aliceToken, ct, "").statusCode());
        assertEquals(403, put("/api/admin/settings", aliceToken, ct, "{}").statusCode());
    }

    @Test
    public void selfServiceDeviceEndpointsAreGone() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        assertEquals(405, post("/api/devices", aliceToken, "{\"name\":\"x\"}").statusCode());
        assertEquals(405, delete("/api/devices/" + id, aliceToken).statusCode());
        assertEquals(404, get("/api/devices/" + id + "/link", aliceToken).statusCode());
    }

    @Test
    public void adminListsUsersWithRolesAndDevices() throws Exception {
        JsonNode users = json.readTree(get("/api/admin/users", adminToken).body());
        assertEquals(4, users.size());
        for (JsonNode u : users) {
            String email = u.get("email").asText();
            if (email.equals("carol@test.example")) assertEquals("ADMIN", u.get("role").asText());
            if (email.equals("alice@test.example")) assertEquals(1, u.get("deviceIds").size());
            if (email.equals("dave@test.example")) assertEquals(0, u.get("deviceIds").size());
            assertFalse(u.has("googleSub"), "google subject id stays internal");
        }
    }

    @Test
    public void adminAssignsAndRemovesDevices() throws Exception {
        int dave = userId("dave@test.example");
        int wetter3 = deviceId("wetter3", adminToken);
        String path = "/api/admin/users/" + dave + "/devices/" + wetter3;
        String ct = "application/json";

        assertEquals(200, put(path, adminToken, ct, "").statusCode());
        assertEquals(200, put(path, adminToken, ct, "").statusCode(), "assigning twice is harmless");
        JsonNode devices = json.readTree(get("/api/devices", daveToken).body());
        assertEquals(1, devices.size());
        assertEquals("wetter3", devices.get(0).get("name").asText());
        assertTrue(json.readTree(get("/api/auth/me", daveToken).body()).get("approved").asBoolean());

        assertTrue(json.readTree(delete(path, adminToken).body()).get("removed").asBoolean());
        assertEquals(0, json.readTree(get("/api/devices", daveToken).body()).size());
        assertFalse(json.readTree(delete(path, adminToken).body()).get("removed").asBoolean());

        assertEquals(404, put("/api/admin/users/999999/devices/" + wetter3, adminToken, ct, "").statusCode());
        assertEquals(404, put("/api/admin/users/" + dave + "/devices/999999", adminToken, ct, "").statusCode());
        assertEquals(404, put("/api/admin/users/x/devices/" + wetter3, adminToken, ct, "").statusCode());
    }

    @Test
    public void promotionTakesEffectImmediately() throws Exception {
        int dave = userId("dave@test.example");
        String path = "/api/admin/users/" + dave + "/role";
        String ct = "application/json";
        assertEquals(403, get("/api/admin/users", daveToken).statusCode());
        assertEquals(200, put(path, adminToken, ct, "{\"role\":\"ADMIN\"}").statusCode());
        // same session, no new login needed
        assertEquals(200, get("/api/admin/users", daveToken).statusCode());
        assertEquals(3, json.readTree(get("/api/devices", daveToken).body()).size());

        assertEquals(200, put(path, adminToken, ct, "{\"role\":\"USER\"}").statusCode());
        assertEquals(403, get("/api/admin/users", daveToken).statusCode());
    }

    @Test
    public void lastAdminCannotBeDemoted() throws Exception {
        String path = "/api/admin/users/" + userId("carol@test.example") + "/role";
        String ct = "application/json";
        assertEquals(409, put(path, adminToken, ct, "{\"role\":\"USER\"}").statusCode());
        assertEquals(200, get("/api/admin/users", adminToken).statusCode(), "still admin");

        assertEquals(400, put(path, adminToken, ct, "{\"role\":\"ROOT\"}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{}").statusCode());
        assertEquals(415, put(path, adminToken, "text/plain", "{\"role\":\"USER\"}").statusCode());
        assertEquals(404, put("/api/admin/users/999999/role", adminToken, ct,
                "{\"role\":\"USER\"}").statusCode());
    }

    @Test
    public void adminEditsSettingsSecretStaysHidden() throws Exception {
        String ct = "application/json";
        HttpResponse<String> res = put("/api/admin/settings", adminToken, ct,
                "{\"googleClientId\":\"cid\",\"googleClientSecret\":\"very-secret\",\"sessionTtlDays\":14}");
        assertEquals(200, res.statusCode());
        assertFalse(res.body().contains("very-secret"));
        JsonNode view = json.readTree(get("/api/admin/settings", adminToken).body());
        assertEquals("cid", view.get("googleClientId").asText());
        assertTrue(view.get("googleClientSecretSet").asBoolean());
        assertEquals(14, view.get("sessionTtlDays").asInt());
        // Google is configured now -> login redirects instead of 503
        assertEquals(302, get("/auth/login", null).statusCode());

        assertEquals(400, put("/api/admin/settings", adminToken, ct, "{\"sessionTtlDays\":0}").statusCode());
        assertEquals(400, put("/api/admin/settings", adminToken, ct, "{\"sessionTtlDays\":\"x\"}").statusCode());
        assertEquals(400, put("/api/admin/settings", adminToken, ct, "[1]").statusCode());
        assertEquals(415, put("/api/admin/settings", adminToken, "text/plain", "{}").statusCode());
    }

    @Test
    public void setupIsClosedOnceAnAdminExists() throws Exception {
        JsonNode status = json.readTree(get("/api/setup", null).body());
        assertFalse(status.get("setupRequired").asBoolean());
        assertFalse(status.has("codeFile"), "code location only while setup is open");
        assertEquals(409, post("/api/setup", null, "{\"code\":\"whatever\"}").statusCode());
    }

    // --------------------------------------------------------------- rename

    @Test
    public void adminRenamesDevice() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        HttpResponse<String> res = put("/api/devices/" + id, adminToken,
                "application/json", "{\"name\":\"  Garten <Nord>  \"}");
        assertEquals(200, res.statusCode());
        JsonNode devices = json.readTree(get("/api/devices", aliceToken).body());
        assertEquals("Garten <Nord>", devices.get(0).get("name").asText());
        assertTrue(devices.get(0).has("hardwareId"));
    }

    @Test
    public void renameIsAdminOnly() throws Exception {
        int aliceDevice = deviceId("wetter1", aliceToken);
        assertEquals(403, put("/api/devices/" + aliceDevice, aliceToken,
                "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(403, put("/api/devices/" + aliceDevice, bobToken,
                "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(404, put("/api/devices/999999", adminToken,
                "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(401, put("/api/devices/" + aliceDevice, null,
                "application/json", "{\"name\":\"x\"}").statusCode());
        // nothing changed
        assertEquals("wetter1",
                json.readTree(get("/api/devices", aliceToken).body()).get(0).get("name").asText());
    }

    @Test
    public void renameRejectsBadInput() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        String path = "/api/devices/" + id;
        String ct = "application/json";
        assertEquals(415, put(path, adminToken, "text/plain", "{\"name\":\"x\"}").statusCode());
        assertEquals(415, put(path, adminToken, null, "{\"name\":\"x\"}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"name\":\"\"}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"name\":\"a\\nb\"}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"name\":\"" + "x".repeat(41) + "\"}").statusCode());
        assertEquals(400, put(path, adminToken, ct, "not json").statusCode());
        assertEquals(400, put(path, adminToken, ct, "{\"name\":5}").statusCode());
        assertEquals(413, put(path, adminToken, ct, "x".repeat(9000)).statusCode());
        // input was rejected, name unchanged
        assertEquals("wetter1",
                json.readTree(get("/api/devices", aliceToken).body()).get(0).get("name").asText());
    }

    @Test
    public void renameRejectsDuplicateName() throws Exception {
        this.mapper.persist(new Device("garten2", Device.DeviceType.SENSOR, "test"));
        int id = deviceId("wetter1", aliceToken);
        assertEquals(409, put("/api/devices/" + id, adminToken,
                "application/json", "{\"name\":\"garten2\"}").statusCode());
    }

    @Test
    public void deviceSubresourcesRejectWrongMethods() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        assertEquals(405, get("/api/devices/" + id, aliceToken).statusCode());
        assertEquals(405, post("/api/devices/" + id, aliceToken, "{\"name\":\"x\"}").statusCode());
        assertEquals(404, put("/api/devices/" + id + "/link", adminToken,
                "application/json", "{\"name\":\"x\"}").statusCode());
    }

    // -------------------------------------------------------------- static

    @Test
    public void servesPwaShellFromClasspath() throws Exception {
        HttpResponse<String> res = get("/", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("UART DB Collector"));
        assertTrue(res.body().contains("Mit Google anmelden"));
        assertEquals("text/html; charset=utf-8", res.headers().firstValue("Content-Type").orElse(""));

        assertEquals(200, get("/app.js", null).statusCode());
        assertEquals(200, get("/manifest.webmanifest", null).statusCode());
        assertEquals(200, get("/sw.js", null).statusCode());
    }

    @Test
    public void shellFilesAreRevalidatedAfterUpdates() throws Exception {
        // a cached app.js next to a new index.html breaks the app
        for (String path : List.of("/", "/index.html", "/app.js", "/style.css", "/sw.js")) {
            assertEquals("no-cache", get(path, null).headers().firstValue("Cache-Control").orElse(""), path);
        }
        assertEquals("max-age=3600", get("/icon-192.png", null).headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    public void pathTraversalIsBlocked() throws Exception {
        assertEquals(404, get("/../../pom.xml", null).statusCode());
        assertEquals(404, get("/%2e%2e/pom.xml", null).statusCode());
    }
}
