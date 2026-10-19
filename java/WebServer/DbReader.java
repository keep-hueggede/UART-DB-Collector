package WebServer;

import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for the web API, backed by the existing Hibernate ORMapper.
 *
 * All reads/writes run in short-lived sessions (readInSession/writeInSession)
 * so concurrent HTTP threads never touch the collector's long-lived session.
 *
 * Every query is scoped to the requesting user: data of a field device is
 * only ever returned when a UserDevice relation links the device to that
 * user. The collector itself keeps storing data for ALL reachable devices -
 * filtering happens exclusively here, on the read path.
 */
public class DbReader {

    private static final String OWNS_SENSOR_EXISTS =
            "SELECT COUNT(ud) FROM UserDevice ud WHERE ud.user.id = :uid "
                    + "AND ud.device.id = (SELECT s.device.id FROM Sensor s WHERE s.id = :sid)";

    private static final int MAX_NAME_LENGTH = 100;

    private final ORMapper mapper;

    public DbReader(ORMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------- health

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

    // ------------------------------------------------------------- users

    /** Creates the account on first login / refreshes profile data on later ones. */
    public UserAccount loginOrCreateUser(String sub, String email, String name, String pictureUrl) {
        return mapper.writeInSession(session -> {
            UserAccount user = session.createQuery(
                            "FROM UserAccount u WHERE u.googleSub = :sub", UserAccount.class)
                    .setParameter("sub", sub)
                    .uniqueResult();
            if (user == null) {
                user = new UserAccount(sub, email, name, pictureUrl);
                session.persist(user);
            } else {
                user.setEmail(email);
                user.setName(name);
                user.setPictureUrl(pictureUrl);
            }
            return user;
        });
    }

    // ------------------------------------------------------------ devices

    /** Devices linked to this user (with sensor count + latest measurement). */
    public List<Map<String, Object>> devices(int userId) {
        return mapper.readInSession(session -> {
            List<Object[]> rows = session.createQuery(
                            "SELECT d, COUNT(DISTINCT s), MAX(m.timestamp) FROM UserDevice ud "
                                    + "JOIN ud.device d LEFT JOIN d.sensors s LEFT JOIN s.measurements m "
                                    + "WHERE ud.user.id = :uid GROUP BY d",
                            Object[].class)
                    .setParameter("uid", userId)
                    .getResultList();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object[] row : rows) {
                Device d = (Device) row[0];
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getName());
                m.put("hardwareId", d.getHardwareId());
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
    }

    /**
     * Devices NOT yet linked to this user, so they can be added.
     * Exposes identity only (name/type/online) - never measurement values.
     */
    public List<Map<String, Object>> availableDevices(int userId) {
        return mapper.readInSession(session -> {
            List<Device> devices = session.createQuery(
                            "SELECT d FROM Device d WHERE NOT EXISTS ("
                                    + "SELECT ud.id FROM UserDevice ud "
                                    + "WHERE ud.user.id = :uid AND ud.device.id = d.id) "
                                    + "ORDER BY d.name",
                            Device.class)
                    .setParameter("uid", userId)
                    .getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Device d : devices) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getName());
                m.put("deviceType", d.getDeviceType() == null ? null : d.getDeviceType().name());
                m.put("online", d.isOnline());
                out.add(m);
            }
            return out;
        });
    }

    /**
     * Links a device to the user. Either an existing deviceId or a device
     * name is required; an unknown name creates the device (the collector
     * will fill it once the edge device reports that src name).
     * Returns the device map, or null if nothing matched.
     */
    public Map<String, Object> linkDevice(int userId, Integer deviceId, String name) {
        String cleanName = (name == null || name.isBlank()) ? null : name.trim();
        if (deviceId == null && cleanName == null) return null;
        if (cleanName != null && !isValidName(cleanName)) {
            throw new IllegalArgumentException("invalid device name");
        }

        return mapper.writeInSession(session -> {
            Device device;
            boolean created = false;
            if (deviceId != null) {
                device = session.get(Device.class, deviceId);
                if (device == null) return null;
            } else {
                device = session.createQuery("FROM Device d WHERE d.name = :name", Device.class)
                        .setParameter("name", cleanName)
                        .uniqueResult();
                if (device == null) {
                    device = new Device(cleanName, Device.DeviceType.SENSOR, "unknown");
                    session.persist(device);
                    created = true;
                }
            }

            Long already = session.createQuery(
                            "SELECT COUNT(ud) FROM UserDevice ud "
                                    + "WHERE ud.user.id = :uid AND ud.device.id = :did", Long.class)
                    .setParameter("uid", userId)
                    .setParameter("did", device.getId())
                    .getSingleResult();
            if (already == 0L) {
                UserAccount user = session.get(UserAccount.class, userId);
                session.persist(new UserDevice(user, device));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", device.getId());
            out.put("name", device.getName());
            out.put("deviceType", device.getDeviceType() == null ? null : device.getDeviceType().name());
            out.put("online", device.isOnline());
            out.put("created", created);
            return out;
        });
    }

    /** Unlinks a device from this user. true if a relation was removed. */
    public boolean unlinkDevice(int userId, int deviceId) {
        Integer removed = mapper.writeInSession(session -> {
            List<UserDevice> links = session.createQuery(
                            "FROM UserDevice ud WHERE ud.user.id = :uid AND ud.device.id = :did",
                            UserDevice.class)
                    .setParameter("uid", userId)
                    .setParameter("did", deviceId)
                    .getResultList();
            for (UserDevice link : links) {
                session.remove(link);
            }
            return links.size();
        });
        return removed > 0;
    }

    public boolean ownsDevice(int userId, int deviceId) {
        return mapper.readInSession(session -> {
            Long c = session.createQuery(
                            "SELECT COUNT(ud) FROM UserDevice ud "
                                    + "WHERE ud.user.id = :uid AND ud.device.id = :did", Long.class)
                    .setParameter("uid", userId)
                    .setParameter("did", deviceId)
                    .getSingleResult();
            return c > 0;
        });
    }

    public enum RenameResult { OK, NOT_FOUND, NAME_TAKEN }

    /**
     * Set the display name of a device linked to this user (the only write
     * the web API offers besides link/unlink). Unlinked ids answer like
     * missing ones so devices cannot be enumerated.
     */
    public RenameResult renameDevice(int userId, int deviceId, String name) {
        return mapper.writeInSession(session -> {
            Long owned = session.createQuery(
                            "SELECT COUNT(ud) FROM UserDevice ud "
                                    + "WHERE ud.user.id = :uid AND ud.device.id = :did", Long.class)
                    .setParameter("uid", userId)
                    .setParameter("did", deviceId)
                    .getSingleResult();
            if (owned == 0L) return RenameResult.NOT_FOUND;

            Device d = session.get(Device.class, deviceId);
            if (d == null) return RenameResult.NOT_FOUND;
            Long taken = session.createQuery(
                            "SELECT COUNT(d) FROM Device d WHERE d.name = :n AND d.id <> :id", Long.class)
                    .setParameter("n", name)
                    .setParameter("id", deviceId)
                    .uniqueResult();
            if (taken > 0L) return RenameResult.NAME_TAKEN;
            d.setName(name);
            return RenameResult.OK;
        });
    }

    // ------------------------------------------------------------ sensors

    /** All sensors of devices linked to this user. */
    public List<Map<String, Object>> sensors(int userId) {
        return mapper.readInSession(session -> {
            List<Sensor> sensors = ownedSensorsQuery(session, userId);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Sensor s : sensors) {
                out.add(sensorMap(s));
            }
            return out;
        });
    }

    /** One sensor, or null when it does not exist / is not linked to the user. */
    public Map<String, Object> sensor(int userId, int sensorId) {
        return mapper.readInSession(session -> {
            Long owned = session.createQuery(OWNS_SENSOR_EXISTS, Long.class)
                    .setParameter("uid", userId)
                    .setParameter("sid", sensorId)
                    .getSingleResult();
            if (owned == 0L) return null;
            Sensor s = session.get(Sensor.class, sensorId);
            return s == null ? null : sensorMap(s);
        });
    }

    /** Latest measurement per owned sensor, including sensor + device metadata. */
    public List<Map<String, Object>> latestMeasurements(int userId) {
        return mapper.readInSession(session -> {
            List<Sensor> sensors = ownedSensorsQuery(session, userId);
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

    // ------------------------------------------------------ measurements

    /**
     * Time series + aggregate stats for one sensor of the user's device.
     * Returns null when the sensor is not linked to the user (caller maps
     * that to 404 so unowned ids cannot be enumerated).
     * from/to are epoch millis (nullable), limit caps the returned points.
     */
    public Map<String, Object> measurements(int userId, int sensorId, Long from, Long to, int limit) {
        return mapper.readInSession(session -> {
            Long owned = session.createQuery(OWNS_SENSOR_EXISTS, Long.class)
                    .setParameter("uid", userId)
                    .setParameter("sid", sensorId)
                    .getSingleResult();
            if (owned == 0L) return null;

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

    // ----------------------------------------------------------- helpers

    private static List<Sensor> ownedSensorsQuery(org.hibernate.Session session, int userId) {
        return session.createQuery(
                        "SELECT s FROM Sensor s JOIN FETCH s.device d WHERE EXISTS ("
                                + "SELECT ud.id FROM UserDevice ud "
                                + "WHERE ud.user.id = :uid AND ud.device = d) "
                                + "ORDER BY d.name, s.sensorType",
                        Sensor.class)
                .setParameter("uid", userId)
                .getResultList();
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
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
