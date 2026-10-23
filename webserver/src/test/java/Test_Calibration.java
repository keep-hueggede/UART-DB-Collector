import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.Repositories;
import org.junit.jupiter.api.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Per-sensor display correction (raw * factor + offset), without a running HTTP server. */
public class Test_Calibration {

    private ORMapper mapper;
    private Repositories repos;
    private int alice;
    private int bob;
    private int sensorId;

    @BeforeEach
    public void setUp() {
        mapper = new ORMapper("localhost", 3306, "caltest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        for (String e : List.of("UserDevice", "Measurement", "Sensor", "Device", "UserAccount")) {
            mapper.executeHQL("DELETE FROM " + e);
        }
        UserAccount a = new UserAccount("sub-a", "a@example.org", "Alice", null);
        UserAccount b = new UserAccount("sub-b", "b@example.org", "Bob", null);
        mapper.persist(a);
        mapper.persist(b);
        Device device = new Device("garten", Device.DeviceType.SENSOR, "test");
        mapper.persist(device);
        mapper.persist(new UserDevice(a, device));
        Sensor temp = new Sensor(device, Sensor.SensorType.TEMPERATURE, "TEMPERATURE");
        mapper.persist(temp);
        mapper.persist(new Measurement(temp, 20.0, new Date(1_000_000L)));
        mapper.persist(new Measurement(temp, 22.0, new Date(2_000_000L)));
        mapper.clear();

        alice = a.getId();
        bob = b.getId();
        sensorId = temp.getId();
        repos = Repositories.of(mapper);
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(Map<String, Object> series) {
        return (Map<String, Object>) series.get("stats");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(Map<String, Object> series) {
        return (List<Map<String, Object>>) series.get("points");
    }

    @Test
    public void withoutCalibrationValuesAreRaw() {
        Map<String, Object> latest = repos.measurements().latest(alice).get(0);
        assertEquals(22.0, (Double) latest.get("value"), 1e-9);
        assertEquals(22.0, (Double) latest.get("rawValue"), 1e-9);
        @SuppressWarnings("unchecked")
        Map<String, Object> cal = (Map<String, Object>) repos.sensors().findLinked(alice, sensorId).get("calibration");
        assertEquals(1.0, cal.get("factor"));
        assertEquals(0.0, cal.get("offset"));
    }

    @Test
    public void offsetCorrectsLatestSeriesAndStats() {
        // reference sensor: ours reads 1 degree too high
        assertTrue(repos.sensors().setCalibration(alice, sensorId, 1.0, -1.0));

        Map<String, Object> latest = repos.measurements().latest(alice).get(0);
        assertEquals(21.0, (Double) latest.get("value"), 1e-9);
        assertEquals(22.0, (Double) latest.get("rawValue"), 1e-9);

        Map<String, Object> series = repos.measurements().series(alice, sensorId, null, null, 100);
        assertEquals(19.0, (Double) points(series).get(0).get("v"), 1e-9);
        assertEquals(21.0, (Double) points(series).get(1).get("v"), 1e-9);
        assertEquals(19.0, (Double) stats(series).get("min"), 1e-9);
        assertEquals(21.0, (Double) stats(series).get("max"), 1e-9);
        assertEquals(20.0, (Double) stats(series).get("avg"), 1e-9);
        assertEquals(40.0, (Double) stats(series).get("sum"), 1e-9);
    }

    @Test
    public void negativeFactorKeepsMinAndMaxRight() {
        repos.sensors().setCalibration(alice, sensorId, -1.0, 0.0);
        Map<String, Object> series = repos.measurements().series(alice, sensorId, null, null, 100);
        assertEquals(-22.0, (Double) stats(series).get("min"), 1e-9);
        assertEquals(-20.0, (Double) stats(series).get("max"), 1e-9);
    }

    @Test
    public void rawValuesInDatabaseStayUntouched() {
        repos.sensors().setCalibration(alice, sensorId, 2.0, 5.0);
        List<Double> raw = mapper.readInSession(s -> s.createQuery(
                "SELECT m.value FROM Measurement m ORDER BY m.timestamp", Double.class).getResultList());
        assertEquals(List.of(20.0, 22.0), raw);
    }

    @Test
    public void onlyOwnerCanCalibrate() {
        assertFalse(repos.sensors().setCalibration(bob, sensorId, 1.0, -1.0));
        assertFalse(repos.sensors().setCalibration(alice, 999_999, 1.0, -1.0));
        Map<String, Object> latest = repos.measurements().latest(alice).get(0);
        assertEquals(22.0, (Double) latest.get("value"), 1e-9);
    }
}
