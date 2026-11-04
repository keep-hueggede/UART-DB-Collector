import Model.Device;
import Model.Sensor;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** Sensor types added after the table was created must still be storable. */
public class Test_SensorTypeColumn {

    private static ORMapper mapper(String db) {
        ORMapper m = new ORMapper("localhost", 3306, db, "sa", "", "h2", DatabaseSetup.ENTITIES);
        m.connect();
        return m;
    }

    private static void storeWindDirection(ORMapper m) {
        Device d = new Device("vane-" + System.nanoTime(), Device.DeviceType.SENSOR, "");
        m.persist(d);
        Sensor s = new Sensor(d, Sensor.SensorType.WIND_DIRECTION, "Windrichtung");
        m.persist(s);
        Sensor back = m.readInSession(x -> x.get(Sensor.class, s.getId()));
        assertEquals(Sensor.SensorType.WIND_DIRECTION, back.getSensorType());
        assertEquals("°", back.getUnit());
    }

    @Test
    public void freshSchemaAcceptsWindDirection() {
        ORMapper m = mapper("typefresh" + System.nanoTime());
        try {
            storeWindDirection(m);
        } finally {
            m.disconnect();
        }
    }

    @Test
    public void legacyEnumColumnIsMigrated() throws Exception {
        String db = "typelegacy" + System.nanoTime();
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:./data/" + db + ";MODE=MySQL;AUTO_SERVER=TRUE", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE Sensors (id INT AUTO_INCREMENT PRIMARY KEY, DeviceId INT NOT NULL,"
                    + " SensorType ENUM('TEMPERATURE','WIND'), Name VARCHAR(255), Unit VARCHAR(255),"
                    + " CalibrationFactor DOUBLE, CalibrationOffset DOUBLE, CreatedAt TIMESTAMP)");
        }
        ORMapper m = mapper(db);
        try {
            storeWindDirection(m);
        } finally {
            m.disconnect();
        }
    }
}
