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

    /** All sensors the viewer may see (admin: all). */
    public List<Map<String, Object>> listVisible(Viewer viewer) {
        return mapper.readInSession(session -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : Ownership.ownedSensors(session, viewer)) {
                out.add(Ownership.sensorMap(s));
            }
            return out;
        });
    }

    /** Stores the display correction; false when the viewer may not see the sensor. */
    public boolean setCalibration(Viewer viewer, int sensorId, double factor, double offset) {
        return mapper.writeInSession(session -> {
            if (!Ownership.ownsSensor(session, viewer, sensorId)) return false;
            Sensor s = session.get(Sensor.class, sensorId);
            if (s == null) return false;
            s.setCalibration(factor, offset);
            return true;
        });
    }

    /** One sensor, or null when it does not exist / the viewer may not see it. */
    public Map<String, Object> findVisible(Viewer viewer, int sensorId) {
        return mapper.readInSession(session -> {
            if (!Ownership.ownsSensor(session, viewer, sensorId)) return null;
            Sensor s = session.get(Sensor.class, sensorId);
            return s == null ? null : Ownership.sensorMap(s);
        });
    }
}
