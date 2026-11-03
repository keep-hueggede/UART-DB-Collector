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

/** Field devices and their assignment to users (by an admin). */
public class DeviceRepository {

    public enum RenameResult { OK, NOT_FOUND, NAME_TAKEN }

    public enum AssignResult { OK, USER_NOT_FOUND, DEVICE_NOT_FOUND }

    private final ORMapper mapper;

    public DeviceRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** Number of known devices (liveness information only). */
    public long count() {
        return mapper.readInSession(session ->
                session.createQuery("SELECT COUNT(d) FROM Device d", Long.class).getSingleResult());
    }

    /** Number of devices assigned to a user (0 = waiting for approval). */
    public long countAssigned(int userId) {
        return mapper.readInSession(session -> session.createQuery(
                        "SELECT COUNT(ud) FROM UserDevice ud WHERE ud.user.id = :uid", Long.class)
                .setParameter("uid", userId).getSingleResult());
    }

    /** Devices the viewer may see (admin: all) with sensor count + latest measurement. */
    public List<Map<String, Object>> listVisible(Viewer viewer) {
        return mapper.readInSession(session -> {
            String select = "SELECT d, COUNT(DISTINCT s), MAX(m.timestamp) ";
            String joins = "LEFT JOIN d.sensors s LEFT JOIN s.measurements m ";
            List<Object[]> rows = viewer.admin()
                    ? session.createQuery(select + "FROM Device d " + joins + "GROUP BY d", Object[].class)
                        .getResultList()
                    : session.createQuery(select + "FROM UserDevice ud JOIN ud.device d " + joins
                                    + "WHERE ud.user.id = :uid GROUP BY d", Object[].class)
                        .setParameter("uid", viewer.userId()).getResultList();
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
                m.put("firmware", d.getFirmware());
                m.put("lastSeen", Ownership.toMillis(d.getLastSeen()));
                m.put("sensorCount", ((Number) row[1]).intValue());
                m.put("lastMeasurement", Ownership.toMillis((Date) row[2]));
                list.add(m);
            }
            list.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
            return list;
        });
    }

    /** Gives a user access to a device (idempotent). */
    public AssignResult assign(int userId, int deviceId) {
        return mapper.writeInSession(session -> {
            UserAccount user = session.get(UserAccount.class, userId);
            if (user == null) return AssignResult.USER_NOT_FOUND;
            Device device = session.get(Device.class, deviceId);
            if (device == null) return AssignResult.DEVICE_NOT_FOUND;
            if (!Ownership.isAssigned(session, userId, deviceId)) {
                session.persist(new UserDevice(user, device));
            }
            return AssignResult.OK;
        });
    }

    /** Removes a user's access to a device; true if a relation was removed. */
    public boolean unassign(int userId, int deviceId) {
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

    /** Sets the display name; devices the viewer may not see answer like missing ones. */
    public RenameResult rename(Viewer viewer, int deviceId, String name) {
        return mapper.writeInSession(session -> {
            if (!Ownership.ownsDevice(session, viewer, deviceId)) return RenameResult.NOT_FOUND;

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
}
