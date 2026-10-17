package WebServer;

import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access for the web API, backed by the existing Hibernate ORMapper.
 * Every query runs in a short-lived session (ORMapper.readInSession) so
 * concurrent HTTP request threads never touch the collector's long-lived
 * session. JSON-friendly maps are built here; entities stay in the ORM layer.
 */
public class DbReader {

    private final ORMapper mapper;

    public DbReader(ORMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> health() {
        return mapper.readInSession(session -> {
            Long devices = session.createQuery("SELECT COUNT(d) FROM Device d", Long.class)
                    .getSingleResult();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("devices", devices);
            out.put("time", System.currentTimeMillis());
            return out;
        });
    }

    public List<Map<String, Object>> devices() {
        List<Map<String, Object>> out = mapper.readInSession(session -> {
            List<Object[]> rows = session.createQuery(
                            "SELECT d, COUNT(DISTINCT s), MAX(m.timestamp) FROM Device d "
                                    + "LEFT JOIN d.sensors s LEFT JOIN s.measurements m GROUP BY d",
                            Object[].class)
                    .getResultList();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object[] row : rows) {
                Device d = (Device) row[0];
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getName());
                m.put("deviceType", d.getDeviceType() == null ? null : d.getDeviceType().name());
                m.put("location", d.getLocation());
                m.put("online", d.isOnline());
                m.put("lastSeen", toMillis(d.getLastSeen()));
                m.put("sensorCount", ((Number) row[1]).intValue());
                m.put("lastMeasurement", toMillis((Date) row[2]));
                list.add(m);
            }
            list.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
            return list;
        });
        return out;
    }

    public List<Map<String, Object>> sensors() {
        return mapper.readInSession(session -> {
            List<Sensor> sensors = session.createQuery(
                            "FROM Sensor s JOIN FETCH s.device ORDER BY s.device.name, s.sensorType",
                            Sensor.class)
                    .getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : sensors) {
                out.add(sensorMap(s));
            }
            return out;
        });
    }

    public Map<String, Object> sensor(int id) {
        return mapper.readInSession(session -> {
            Sensor s = session.get(Sensor.class, id);
            return s == null ? null : sensorMap(s);
        });
    }

    /** Latest measurement per sensor, including sensor + device metadata. */
    public List<Map<String, Object>> latestMeasurements() {
        return mapper.readInSession(session -> {
            List<Sensor> sensors = session.createQuery(
                            "FROM Sensor s JOIN FETCH s.device ORDER BY s.device.name, s.sensorType",
                            Sensor.class)
                    .getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : sensors) {
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
                m.put("timestamp", latest == null ? null : toMillis(latest.getTimestamp()));
                out.add(m);
            }
            return out;
        });
    }

    /**
     * Time series for one sensor: points + aggregate stats over the full range.
     * from/to are epoch millis (nullable), limit caps the returned points.
     */
    public Map<String, Object> measurements(int sensorId, Long from, Long to, int limit) {
        return mapper.readInSession(session -> {
            String range = "";
            if (from != null) range += " AND m.timestamp >= :from";
            if (to != null)   range += " AND m.timestamp <= :to";

            var statsQuery = session.createQuery(
                            "SELECT MIN(m.value), MAX(m.value), AVG(m.value), COUNT(m) "
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
                p.put("t", toMillis(m.getTimestamp()));
                p.put("v", m.getValue());
                points.add(p);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stats", statsMap);
            out.put("points", points);
            return out;
        });
    }

    private static Map<String, Object> sensorMap(Sensor s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("sensorType", s.getSensorType() == null ? null : s.getSensorType().name());
        m.put("name", s.getName());
        m.put("unit", s.getUnit());
        m.put("deviceId", s.getDevice().getId());
        m.put("deviceName", s.getDevice().getName());
        return m;
    }

    private static Long toMillis(Date date) {
        return date == null ? null : date.getTime();
    }
}
