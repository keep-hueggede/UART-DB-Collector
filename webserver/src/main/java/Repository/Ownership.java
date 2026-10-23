package Repository;

import Model.Sensor;
import org.hibernate.Session;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries and mappings shared by the repositories. Data of a field device is
 * only visible to a user who has linked that device (UserDevice relation).
 */
final class Ownership {

    private Ownership() {}

    static final String OWNS_SENSOR =
            "SELECT COUNT(ud) FROM UserDevice ud WHERE ud.user.id = :uid "
                    + "AND ud.device.id = (SELECT s.device.id FROM Sensor s WHERE s.id = :sid)";

    static final String OWNS_DEVICE =
            "SELECT COUNT(ud) FROM UserDevice ud WHERE ud.user.id = :uid AND ud.device.id = :did";

    static boolean ownsSensor(Session session, int userId, int sensorId) {
        return session.createQuery(OWNS_SENSOR, Long.class)
                .setParameter("uid", userId)
                .setParameter("sid", sensorId)
                .getSingleResult() > 0L;
    }

    static boolean ownsDevice(Session session, int userId, int deviceId) {
        return session.createQuery(OWNS_DEVICE, Long.class)
                .setParameter("uid", userId)
                .setParameter("did", deviceId)
                .getSingleResult() > 0L;
    }

    static List<Sensor> ownedSensors(Session session, int userId) {
        return session.createQuery(
                        "SELECT s FROM Sensor s JOIN FETCH s.device d WHERE EXISTS ("
                                + "SELECT ud.id FROM UserDevice ud "
                                + "WHERE ud.user.id = :uid AND ud.device = d) "
                                + "ORDER BY d.name, s.sensorType",
                        Sensor.class)
                .setParameter("uid", userId)
                .getResultList();
    }

    static Map<String, Object> sensorMap(Sensor s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("sensorType", s.getSensorType() == null ? null : s.getSensorType().name());
        m.put("name", s.getName());
        m.put("unit", s.getUnit());
        m.put("deviceId", s.getDevice().getId());
        m.put("deviceName", s.getDevice().getName());
        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put("factor", s.getCalibrationFactor());
        cal.put("offset", s.getCalibrationOffset());
        m.put("calibration", cal);
        return m;
    }

    /** Value as displayed: raw * factor + offset (raw stays untouched in the database). */
    static Double corrected(Sensor s, Double raw) {
        return raw == null ? null : raw * s.getCalibrationFactor() + s.getCalibrationOffset();
    }

    static Long toMillis(Date date) {
        return date == null ? null : date.getTime();
    }
}
