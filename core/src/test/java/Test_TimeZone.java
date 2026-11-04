import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/** Timestamps are stored as UTC, independent of the JVM time zone of the writer and reader. */
public class Test_TimeZone {

    private static final Instant AT = Instant.parse("2026-11-04T08:18:24Z");   // 09:18:24 in Berlin
    private TimeZone original;

    @BeforeEach
    public void rememberZone() {
        original = TimeZone.getDefault();
    }

    @AfterEach
    public void restoreZone() {
        TimeZone.setDefault(original);
    }

    private static ORMapper mapper() {
        ORMapper m = new ORMapper("localhost", 3306, "tztest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        m.connect();
        return m;
    }

    @Test
    public void berlinWriterStoresUtcAndUtcReaderSeesSameInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));   // like the Pi
        ORMapper pi = mapper();
        int id;
        try {
            Device d = new Device("tz-" + System.nanoTime(), Device.DeviceType.SENSOR, "");
            pi.persist(d);
            Sensor s = new Sensor(d, Sensor.SensorType.TEMPERATURE, "t");
            pi.persist(s);
            Measurement m = new Measurement(s, 20.0, Date.from(AT));
            pi.persist(m);
            id = m.getId();
            String raw = pi.readInSession(x -> x.createNativeQuery(
                    "SELECT CAST(Timestamp AS VARCHAR) FROM Measurements WHERE id = " + id, String.class)
                    .getSingleResult());
            assertTrue(raw.startsWith("2026-11-04 08:18:24"), "stored as UTC wall time, was " + raw);
        } finally {
            pi.disconnect();
        }

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));   // like the web server
        ORMapper server = mapper();
        try {
            Date read = server.readInSession(x -> x.get(Measurement.class, id).getTimestamp());
            assertEquals(AT, read.toInstant());
        } finally {
            server.disconnect();
        }
    }
}
