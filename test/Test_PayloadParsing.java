import Controller.Controller;
import Model.Sensor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class Test_PayloadParsing {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    public void receiverFormatWithDataWrapper() throws Exception {
        String line = "{\"src\":\"wetter1\",\"data\":{\"temp\":22.15,\"pressure\":981.31,\"humidity\":49.53,\"wind\":0.04,\"rain\":0.00}}";
        Controller.ParsedData p = Controller.parseLine(om.readTree(line));

        assertEquals("wetter1", p.src);
        assertEquals(22.15, p.values.get(Sensor.SensorType.TEMPERATURE));
        assertEquals(981.31, p.values.get(Sensor.SensorType.PRESSURE));
        assertEquals(49.53, p.values.get(Sensor.SensorType.HUMIDITY));
        assertEquals(0.04, p.values.get(Sensor.SensorType.WIND));
        assertEquals(0.00, p.values.get(Sensor.SensorType.RAIN));
    }

    @Test
    public void flatFormatWithoutData() throws Exception {
        String line = "{\"src\":\"wetter1\",\"temp\":20.5,\"pressure\":990.0}";
        Controller.ParsedData p = Controller.parseLine(om.readTree(line));

        assertEquals("wetter1", p.src);
        assertEquals(2, p.values.size());
        assertEquals(20.5, p.values.get(Sensor.SensorType.TEMPERATURE));
        assertEquals(990.0, p.values.get(Sensor.SensorType.PRESSURE));
    }

    @Test
    public void missingFieldsAreSkipped() throws Exception {
        String line = "{\"src\":\"wald2\",\"rain\":1.35}";
        Controller.ParsedData p = Controller.parseLine(om.readTree(line));

        assertEquals("wald2", p.src);
        assertEquals(1, p.values.size());
        assertEquals(1.35, p.values.get(Sensor.SensorType.RAIN));
        assertNull(p.values.get(Sensor.SensorType.TEMPERATURE));
    }

    @Test
    public void nullFieldsSkipped() throws Exception {
        String line = "{\"src\":\"x\",\"temp\":null,\"pressure\":1013.0}";
        Controller.ParsedData p = Controller.parseLine(om.readTree(line));

        assertEquals(1, p.values.size());
        assertEquals(1013.0, p.values.get(Sensor.SensorType.PRESSURE));
        assertFalse(p.values.containsKey(Sensor.SensorType.TEMPERATURE));
    }
}
