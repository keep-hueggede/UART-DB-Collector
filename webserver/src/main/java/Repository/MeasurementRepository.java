package Repository;

import Model.Measurement;
import Model.Sensor;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Measurement values of the sensors a user has linked. */
public class MeasurementRepository {

    private final ORMapper mapper;

    public MeasurementRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** Latest measurement per owned sensor, including sensor + device metadata. */
    public List<Map<String, Object>> latest(int userId) {
        return mapper.readInSession(session -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : Ownership.ownedSensors(session, userId)) {
                Measurement latest = session.createQuery(
                                "FROM Measurement m WHERE m.sensor.id = :sid "
                                        + "ORDER BY m.timestamp DESC, m.id DESC",
                                Measurement.class)
                        .setParameter("sid", s.getId())
                        .setMaxResults(1)
                        .uniqueResult();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sensorId", s.getId());
                m.put("sensorType", s.getSensorType() == null ? null : s.getSensorType().name());
                m.put("name", s.getName());
                m.put("unit", s.getUnit());
                m.put("deviceName", s.getDevice().getName());
                m.put("value", latest == null ? null : latest.getValue());
                m.put("timestamp", latest == null ? null : Ownership.toMillis(latest.getTimestamp()));
                out.add(m);
            }
            return out;
        });
    }

    /**
     * Time series + aggregate stats for one sensor of the user's device.
     * Returns null when the sensor is not linked to the user (caller maps
     * that to 404 so unowned ids cannot be enumerated).
     * from/to are epoch millis (nullable), limit caps the returned points.
     */
    public Map<String, Object> series(int userId, int sensorId, Long from, Long to, int limit) {
        return mapper.readInSession(session -> {
            if (!Ownership.ownsSensor(session, userId, sensorId)) return null;

            String range = "";
            if (from != null) range += " AND m.timestamp >= :from";
            if (to != null)   range += " AND m.timestamp <= :to";

            var statsQuery = session.createQuery(
                            "SELECT MIN(m.value), MAX(m.value), AVG(m.value), COUNT(m), SUM(m.value) "
                                    + "FROM Measurement m WHERE m.sensor.id = :sid" + range,
                            Object[].class)
                    .setParameter("sid", sensorId);
            if (from != null) statsQuery.setParameter("from", new Date(from));
            if (to != null) statsQuery.setParameter("to", new Date(to));
            Object[] stats = statsQuery.getSingleResult();

            Map<String, Object> statsMap = new LinkedHashMap<>();
            statsMap.put("count", stats[3] == null ? 0L : ((Number) stats[3]).longValue());
            statsMap.put("min", stats[0]);
            statsMap.put("max", stats[1]);
            statsMap.put("avg", stats[2] == null ? null
                    : Math.round(((Number) stats[2]).doubleValue() * 1000.0) / 1000.0);
            // Meaningful for per-interval values such as rain
            statsMap.put("sum", stats[4] == null ? 0.0
                    : Math.round(((Number) stats[4]).doubleValue() * 1000.0) / 1000.0);

            var pointQuery = session.createQuery(
                            "FROM Measurement m WHERE m.sensor.id = :sid" + range
                                    + " ORDER BY m.timestamp ASC, m.id ASC",
                            Measurement.class)
                    .setParameter("sid", sensorId);
            if (from != null) pointQuery.setParameter("from", new Date(from));
            if (to != null) pointQuery.setParameter("to", new Date(to));
            List<Measurement> measurements = pointQuery.setMaxResults(limit).getResultList();

            List<Map<String, Object>> points = new ArrayList<>();
            for (Measurement m : measurements) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("t", Ownership.toMillis(m.getTimestamp()));
                p.put("v", m.getValue());
                points.add(p);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stats", statsMap);
            out.put("points", points);
            return out;
        });
    }
}
