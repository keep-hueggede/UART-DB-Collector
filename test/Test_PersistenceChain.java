import Model.Actuator;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Test_PersistenceChain {

    private ORMapper mapper;

    @BeforeEach
    public void setUp() {
        this.mapper = new ORMapper("localhost", 3306, "test",
                "sa", "", "h2",
                Arrays.asList(Device.class, Sensor.class, Measurement.class, Actuator.class));
        this.mapper.connect();
        this.mapper.executeHQL("DELETE FROM Measurement");
        this.mapper.executeHQL("DELETE FROM Sensor");
        this.mapper.executeHQL("DELETE FROM Device");
    }

    @AfterEach
    public void tearDown() {
        this.mapper.disconnect();
    }

    @Test
    public void weatherDataToDatabase() {
        // Device wetter1 anlegen
        Device device = mapper.findByName(Device.class, "wetter1");
        if (device == null) {
            device = new Device("wetter1", Device.DeviceType.SENSOR, "wald");
            mapper.persist(device);
        }

        // Sensoren anlegen
        Sensor temp = mapper.findByNameAndDevice(Sensor.class, "TEMPERATURE", device);
        if (temp == null) {
            temp = new Sensor(device, Sensor.SensorType.TEMPERATURE, "TEMPERATURE");
            mapper.persist(temp);
        }
        Sensor pressure = mapper.findByNameAndDevice(Sensor.class, "PRESSURE", device);
        if (pressure == null) {
            pressure = new Sensor(device, Sensor.SensorType.PRESSURE, "PRESSURE");
            mapper.persist(pressure);
        }
        Sensor rain = mapper.findByNameAndDevice(Sensor.class, "RAIN", device);
        if (rain == null) {
            rain = new Sensor(device, Sensor.SensorType.RAIN, "RAIN");
            mapper.persist(rain);
        }

        // Messwerte (simuliert aus UART-JSON)
        mapper.persist(new Measurement(temp, 22.15));
        mapper.persist(new Measurement(pressure, 981.31));
        mapper.persist(new Measurement(rain, 0.0));

        // Verifizieren
        List<Measurement> ms = mapper.getAll(Measurement.class);
        assertEquals(3, ms.size());
        assertTrue(ms.stream().anyMatch(m -> m.getValue().equals(22.15)));
        assertTrue(ms.stream().anyMatch(m -> m.getValue().equals(981.31)));
        assertTrue(ms.stream().anyMatch(m -> m.getValue().equals(0.0)));

        // Messwerte sind an Sensoren gebunden
        List<Sensor> sensors = mapper.getAll(Sensor.class);
        assertEquals(3, sensors.size());
        for (Sensor s : sensors) {
            assertEquals("wetter1", s.getDevice().getName());
        }
    }
}
