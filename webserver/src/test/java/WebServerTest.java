import Model.Actuator;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
import ORMUtil.ORMapper;
import Repository.Repositories;
import WebServer.GoogleAuth;
import WebServer.SessionManager;
import WebServer.WebServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the web server: seeds users/devices/ownership through
 * the ORMapper, boots the server on a random port and verifies auth,
 * per-user data scoping, device linking and the static PWA over HTTP.
 */
public class WebServerTest {

    private ORMapper mapper;
    private WebServer server;
    private SessionManager sessions;
    private String base;
    private String aliceToken;
    private String bobToken;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        this.mapper = new ORMapper("localhost", 3306, "webtest", "sa", "", "h2",
                List.of(Device.class, Sensor.class, Measurement.class, Actuator.class,
                        UserAccount.class, UserDevice.class));
        this.mapper.connect();
        seedDatabase();

        this.sessions = new SessionManager();
        // Google not configured in tests -> flow endpoints must degrade safely
        GoogleAuth google = new GoogleAuth("", "", "");
        this.server = new WebServer(Repositories.of(this.mapper), sessions, google,
                "127.0.0.1", 0, "./does-not-exist", false);
        this.server.start();
        this.base = "http://127.0.0.1:" + this.server.getPort();

        this.aliceToken = sessions.create(findUser("alice@test.example"));
        this.bobToken = sessions.create(findUser("bob@test.example"));
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
        session.getTransaction().commit();

        UserAccount alice = new UserAccount("sub-alice", "alice@test.example", "Alice", "https://example.com/a.png");
        UserAccount bob = new UserAccount("sub-bob", "bob@test.example", "Bob", "");
        this.mapper.persist(alice);
        this.mapper.persist(bob);

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

    // ------------------------------------------------- device management

    @Test
    public void availableListsExcludesOwnDevices() throws Exception {
        JsonNode avail = json.readTree(get("/api/devices/available", aliceToken).body());
        // alice owns wetter1 -> sees wetter2 + wetter3, never her own
        assertEquals(2, avail.size());
        for (JsonNode d : avail) {
            assertNotEquals("wetter1", d.get("name").asText());
            // identity only - available devices must not leak values
            assertFalse(d.has("lastMeasurement"));
            assertFalse(d.has("location"));
        }
    }

    @Test
    public void linkExistingDeviceById() throws Exception {
        JsonNode avail = json.readTree(get("/api/devices/available", aliceToken).body());
        int wetter3 = -1;
        for (JsonNode d : avail) {
            if (d.get("name").asText().equals("wetter3")) wetter3 = d.get("id").asInt();
        }
        assertTrue(wetter3 > 0);

        JsonNode linked = json.readTree(
                post("/api/devices", aliceToken, "{\"deviceId\":" + wetter3 + "}").body());
        assertEquals("wetter3", linked.get("name").asText());
        assertFalse(linked.get("created").asBoolean());

        JsonNode devices = json.readTree(get("/api/devices", aliceToken).body());
        assertEquals(2, devices.size());

        // idempotent double link
        HttpResponse<String> again = post("/api/devices", aliceToken, "{\"deviceId\":" + wetter3 + "}");
        assertEquals(200, again.statusCode());

        // unlink again
        HttpResponse<String> un = delete("/api/devices/" + wetter3, aliceToken);
        assertEquals(200, un.statusCode());
        assertTrue(json.readTree(un.body()).get("unlinked").asBoolean());
        assertEquals(1, json.readTree(get("/api/devices", aliceToken).body()).size());
    }

    @Test
    public void linkUnknownDeviceIdIsNotFound() throws Exception {
        HttpResponse<String> res = post("/api/devices", aliceToken, "{\"deviceId\":999999}");
        assertEquals(404, res.statusCode());
        assertEquals(400, post("/api/devices", aliceToken, "{}").statusCode());
    }

    @Test
    public void addDeviceByNameCreatesAndLinks() throws Exception {
        JsonNode created = json.readTree(
                post("/api/devices", aliceToken, "{\"name\":\"neu1\"}").body());
        assertTrue(created.get("created").asBoolean());
        assertEquals("neu1", created.get("name").asText());

        JsonNode devices = json.readTree(get("/api/devices", aliceToken).body());
        assertEquals(2, devices.size());

        // control chars in names are rejected
        assertEquals(400, post("/api/devices", aliceToken, "{\"name\":\"bad\\u0000name\"}").statusCode());
    }

    @Test
    public void unlinkOfNeverLinkedDeviceIsSafe() throws Exception {
        HttpResponse<String> res = delete("/api/devices/999999", aliceToken);
        assertEquals(200, res.statusCode());
        assertFalse(json.readTree(res.body()).get("unlinked").asBoolean());
    }

    // --------------------------------------------------------------- rename

    @Test
    public void renameOwnDevice() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        HttpResponse<String> res = put("/api/devices/" + id, aliceToken,
                "application/json", "{\"name\":\"  Garten <Nord>  \"}");
        assertEquals(200, res.statusCode());
        JsonNode devices = json.readTree(get("/api/devices", aliceToken).body());
        assertEquals("Garten <Nord>", devices.get(0).get("name").asText());
        assertTrue(devices.get(0).has("hardwareId"));
    }

    @Test
    public void renameRequiresOwnership() throws Exception {
        int aliceDevice = deviceId("wetter1", aliceToken);
        // bob does not own it -> indistinguishable from a missing device
        assertEquals(404, put("/api/devices/" + aliceDevice, bobToken,
                "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(404, put("/api/devices/999999", aliceToken,
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
        String ct = "application/json";
        assertEquals(415, put("/api/devices/" + id, aliceToken, "text/plain", "{\"name\":\"x\"}").statusCode());
        assertEquals(415, put("/api/devices/" + id, aliceToken, null, "{\"name\":\"x\"}").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "{}").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "{\"name\":\"\"}").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "{\"name\":\"a\\nb\"}").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct,
                "{\"name\":\"" + "x".repeat(41) + "\"}").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "not json").statusCode());
        assertEquals(400, put("/api/devices/" + id, aliceToken, ct, "{\"name\":5}").statusCode());
        assertEquals(413, put("/api/devices/" + id, aliceToken, ct, "x".repeat(9000)).statusCode());
        // input was rejected, name unchanged
        assertEquals("wetter1",
                json.readTree(get("/api/devices", aliceToken).body()).get(0).get("name").asText());
    }

    @Test
    public void renameRejectsDuplicateName() throws Exception {
        this.mapper.persist(new Device("garten2", Device.DeviceType.SENSOR, "test"));
        int id = deviceId("wetter1", aliceToken);
        assertEquals(409, put("/api/devices/" + id, aliceToken,
                "application/json", "{\"name\":\"garten2\"}").statusCode());
    }

    @Test
    public void deviceSubresourcesRejectWrongMethods() throws Exception {
        int id = deviceId("wetter1", aliceToken);
        assertEquals(405, get("/api/devices/" + id, aliceToken).statusCode());
        assertEquals(405, post("/api/devices/" + id, aliceToken, "{\"name\":\"x\"}").statusCode());
        assertEquals(405, put("/api/devices/" + id + "/link", aliceToken,
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
    public void pathTraversalIsBlocked() throws Exception {
        assertEquals(404, get("/../../pom.xml", null).statusCode());
        assertEquals(404, get("/%2e%2e/pom.xml", null).statusCode());
    }
}
