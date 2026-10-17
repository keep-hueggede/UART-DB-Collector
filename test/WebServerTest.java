import Model.Actuator;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.ORMapper;
import WebServer.DbReader;
import WebServer.WebServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the embedded web server: seeds the database through the
 * existing ORMapper, boots the server on a random port and exercises the REST
 * API over HTTP.
 */
public class WebServerTest {

    private ORMapper mapper;
    private WebServer server;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        this.mapper = new ORMapper("localhost", 3306, "webtest", "sa", "", "h2",
                List.of(Device.class, Sensor.class, Measurement.class, Actuator.class));
        this.mapper.connect();
        seedDatabase();
        // web root that does not exist -> static files are served from the classpath (/web)
        this.server = new WebServer(new DbReader(this.mapper), "127.0.0.1", 0, "./does-not-exist");
        this.server.start();
        this.base = "http://127.0.0.1:" + this.server.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (this.server != null) this.server.stop();
        if (this.mapper != null) this.mapper.disconnect();
    }

    private void seedDatabase() {
        var session = this.mapper.getSession();
        session.beginTransaction();
        session.createQuery("DELETE FROM Measurement").executeUpdate();
        session.createQuery("DELETE FROM Sensor").executeUpdate();
        session.createQuery("DELETE FROM Device").executeUpdate();
        session.getTransaction().commit();

        Device device = new Device("wetter1", Device.DeviceType.SENSOR, "wald");
        device.setOnline(true);
        device.setLastSeen(new Date());
        this.mapper.persist(device);

        Sensor temp = new Sensor(device, Sensor.SensorType.TEMPERATURE, "TEMPERATURE");
        Sensor pressure = new Sensor(device, Sensor.SensorType.PRESSURE, "PRESSURE");
        Sensor rain = new Sensor(device, Sensor.SensorType.RAIN, "RAIN"); // no measurements on purpose
        this.mapper.persist(temp);
        this.mapper.persist(pressure);
        this.mapper.persist(rain);

        this.mapper.persist(new Measurement(temp, 20.0));
        this.mapper.persist(new Measurement(temp, 22.0));
        this.mapper.persist(new Measurement(temp, 24.0));
        this.mapper.persist(new Measurement(pressure, 981.31));

        // Deterministic timestamps for series/range assertions (@CreationTimestamp
        // would stamp "now"; values are unique in this seed, so update by value)
        setTimestamp(20.0, "2026-01-01 10:00:00");
        setTimestamp(22.0, "2026-01-01 11:00:00");
        setTimestamp(24.0, "2026-01-01 12:00:00");
        setTimestamp(981.31, "2026-01-01 12:30:00");
        this.mapper.clear();
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

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Accept", "application/json")
                .GET().build();
        return this.http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Sensor ids are identity-generated and vary across runs -> look them up. */
    private int sensorIdByType(String type) throws Exception {
        JsonNode body = json.readTree(get("/api/sensors").body());
        for (JsonNode s : body) {
            if (type.equals(s.get("sensorType").asText())) return s.get("id").asInt();
        }
        fail("sensor type not found: " + type);
        return -1;
    }

    @Test
    public void healthEndpoint() throws Exception {
        HttpResponse<String> res = get("/api/health");
        assertEquals(200, res.statusCode());
        JsonNode body = json.readTree(res.body());
        assertEquals("ok", body.get("status").asText());
    }

    @Test
    public void devicesIncludeSensorCountAndLastMeasurement() throws Exception {
        JsonNode body = json.readTree(get("/api/devices").body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        JsonNode d = body.get(0);
        assertEquals("wetter1", d.get("name").asText());
        assertEquals(3, d.get("sensorCount").asInt());
        assertTrue(d.get("online").asBoolean());
        assertFalse(d.get("lastMeasurement").isNull());
    }

    @Test
    public void sensorsAreListedWithDeviceAndUnit() throws Exception {
        JsonNode body = json.readTree(get("/api/sensors").body());
        assertEquals(3, body.size());
        List<String> types = new ArrayList<>();
        for (JsonNode s : body) {
            types.add(s.get("sensorType").asText());
            assertEquals("wetter1", s.get("deviceName").asText());
        }
        assertTrue(types.contains("TEMPERATURE"));
        assertTrue(types.contains("PRESSURE"));
        assertTrue(types.contains("RAIN"));
    }

    @Test
    public void singleSensorById() throws Exception {
        int pressureId = sensorIdByType("PRESSURE");
        JsonNode body = json.readTree(get("/api/sensors/" + pressureId).body());
        assertEquals("PRESSURE", body.get("sensorType").asText());
        assertEquals("hPa", body.get("unit").asText());
        assertEquals(404, get("/api/sensors/999999").statusCode());
    }

    @Test
    public void latestMeasurements() throws Exception {
        JsonNode body = json.readTree(get("/api/measurements/latest").body());
        assertEquals(3, body.size());
        JsonNode temp = findByType(body, "TEMPERATURE");
        assertEquals(24.0, temp.get("value").asDouble(), 0.001);
        // RAIN has no data -> null value
        JsonNode rain = findByType(body, "RAIN");
        assertTrue(rain.get("value").isNull());
    }

    @Test
    public void measurementSeriesAndStats() throws Exception {
        int tempId = sensorIdByType("TEMPERATURE");
        JsonNode body = json.readTree(get("/api/measurements?sensorId=" + tempId).body());
        JsonNode points = body.get("points");
        assertEquals(3, points.size());
        // ascending by time
        assertEquals(20.0, points.get(0).get("v").asDouble(), 0.001);
        assertEquals(24.0, points.get(2).get("v").asDouble(), 0.001);
        JsonNode stats = body.get("stats");
        assertEquals(3, stats.get("count").asInt());
        assertEquals(20.0, stats.get("min").asDouble(), 0.001);
        assertEquals(24.0, stats.get("max").asDouble(), 0.001);
        assertEquals(22.0, stats.get("avg").asDouble(), 0.001);
        assertEquals("TEMPERATURE", body.get("sensor").get("sensorType").asText());
    }

    @Test
    public void measurementRangeFilter() throws Exception {
        int tempId = sensorIdByType("TEMPERATURE");
        long from = Timestamp.valueOf("2026-01-01 11:30:00").getTime();
        JsonNode body = json.readTree(get("/api/measurements?sensorId=" + tempId + "&from=" + from).body());
        assertEquals(1, body.get("points").size());
        assertEquals(24.0, body.get("points").get(0).get("v").asDouble(), 0.001);
    }

    @Test
    public void badRequests() throws Exception {
        assertEquals(400, get("/api/measurements").statusCode());
        assertEquals(404, get("/api/nope").statusCode());
    }

    @Test
    public void servesPwaShellFromClasspath() throws Exception {
        HttpResponse<String> res = get("/");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("UART DB Collector"));
        assertEquals("text/html; charset=utf-8", res.headers().firstValue("Content-Type").orElse(""));

        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/manifest.webmanifest").statusCode());
        assertEquals(200, get("/sw.js").statusCode());
    }

    @Test
    public void pathTraversalIsBlocked() throws Exception {
        assertEquals(404, get("/../../pom.xml").statusCode());
        assertEquals(404, get("/%2e%2e/pom.xml").statusCode());
    }

    @Test
    public void securityHeadersAreSet() throws Exception {
        HttpResponse<String> page = get("/");
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("nosniff", get("/api/health").headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    public void headRequestHasNoBody() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/index.html"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> res = this.http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals("", res.body());
    }

    private static JsonNode findByType(JsonNode array, String type) {
        for (JsonNode n : array) {
            if (type.equals(n.get("sensorType").asText())) return n;
        }
        fail("sensor type not found: " + type);
        return null;
    }
}
