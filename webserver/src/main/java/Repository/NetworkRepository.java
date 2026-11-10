package Repository;

import Model.Collector;
import Model.Device;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collectors and the devices that last reported through them (admin network map). */
public class NetworkRepository {

    static final long COLLECTOR_ONLINE_MS = 6 * 60 * 1000;    // heartbeat every 2 min
    static final long DEVICE_ONLINE_MS = 16 * 60 * 1000;      // sensors report every 5 min

    private final ORMapper mapper;

    public NetworkRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> map(long now) {
        return mapper.readInSession(session -> {
            List<Collector> collectors = session.createQuery("FROM Collector c ORDER BY c.name", Collector.class)
                    .getResultList();
            List<Device> devices = session.createQuery("FROM Device d ORDER BY d.name", Device.class).getResultList();

            List<Map<String, Object>> outCollectors = new ArrayList<>();
            List<Map<String, Object>> unassigned = new ArrayList<>();
            Map<Integer, List<Map<String, Object>>> byCollector = new LinkedHashMap<>();
            for (Collector c : collectors) byCollector.put(c.getId(), new ArrayList<>());
            for (Device d : devices) {
                List<Map<String, Object>> target = d.getCollectorId() == null ? null : byCollector.get(d.getCollectorId());
                (target != null ? target : unassigned).add(device(d, now));
            }
            for (Collector c : collectors) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("version", c.getVersion());
                m.put("sensorFirmware", c.getSensorFirmware());
                m.put("uartPort", c.getUartPort());
                m.put("startedAt", Ownership.toMillis(c.getStartedAt()));
                m.put("lastSeen", Ownership.toMillis(c.getLastSeen()));
                m.put("online", recent(c.getLastSeen(), now, COLLECTOR_ONLINE_MS));
                m.put("devices", byCollector.get(c.getId()));
                outCollectors.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("collectors", outCollectors);
            out.put("unassigned", unassigned);
            return out;
        });
    }

    private static Map<String, Object> device(Device d, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("hardwareId", d.getHardwareId());
        m.put("firmware", d.getFirmware());
        m.put("lastSeen", Ownership.toMillis(d.getLastSeen()));
        m.put("online", recent(d.getLastSeen(), now, DEVICE_ONLINE_MS));
        return m;
    }

    private static boolean recent(Date at, long now, long windowMs) {
        return at != null && now - at.getTime() <= windowMs;
    }
}
