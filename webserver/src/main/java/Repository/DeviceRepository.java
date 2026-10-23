package Repository;

import Model.Device;
import Model.UserAccount;
import Model.UserDevice;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field devices and the user-device links. */
public class DeviceRepository {

    public enum RenameResult { OK, NOT_FOUND, NAME_TAKEN }

    private static final int MAX_NAME_LENGTH = 100;

    private final ORMapper mapper;

    public DeviceRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** Number of known devices (liveness information only). */
    public long count() {
        return mapper.readInSession(session ->
                session.createQuery("SELECT COUNT(d) FROM Device d", Long.class).getSingleResult());
    }

    /** Devices linked to this user (with sensor count + latest measurement). */
    public List<Map<String, Object>> listLinked(int userId) {
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
                m.put("lastSeen", Ownership.toMillis(d.getLastSeen()));
                m.put("sensorCount", ((Number) row[1]).intValue());
                m.put("lastMeasurement", Ownership.toMillis((Date) row[2]));
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
    public List<Map<String, Object>> listAvailable(int userId) {
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
    public Map<String, Object> link(int userId, Integer deviceId, String name) {
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

            if (!Ownership.ownsDevice(session, userId, device.getId())) {
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
    public boolean unlink(int userId, int deviceId) {
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

    public boolean isLinked(int userId, int deviceId) {
        return mapper.readInSession(session -> Ownership.ownsDevice(session, userId, deviceId));
    }

    /**
     * Set the display name of a device linked to this user. Unlinked ids
     * answer like missing ones so devices cannot be enumerated.
     */
    public RenameResult rename(int userId, int deviceId, String name) {
        return mapper.writeInSession(session -> {
            if (!Ownership.ownsDevice(session, userId, deviceId)) return RenameResult.NOT_FOUND;

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

    private static boolean isValidName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }
}
