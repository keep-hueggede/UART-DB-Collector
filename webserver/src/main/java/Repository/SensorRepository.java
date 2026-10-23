package Repository;

import Model.Sensor;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sensors of the devices a user has linked. */
public class SensorRepository {

    private final ORMapper mapper;

    public SensorRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** All sensors of devices linked to this user. */
    public List<Map<String, Object>> listLinked(int userId) {
        return mapper.readInSession(session -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : Ownership.ownedSensors(session, userId)) {
                out.add(Ownership.sensorMap(s));
            }
            return out;
        });
    }

    /** One sensor, or null when it does not exist / is not linked to the user. */
    public Map<String, Object> findLinked(int userId, int sensorId) {
        return mapper.readInSession(session -> {
            if (!Ownership.ownsSensor(session, userId, sensorId)) return null;
            Sensor s = session.get(Sensor.class, sensorId);
            return s == null ? null : Ownership.sensorMap(s);
        });
    }
}
