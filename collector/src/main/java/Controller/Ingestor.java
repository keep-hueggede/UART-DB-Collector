package Controller;

import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.ORMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Stores UART lines in the database. While the database is unreachable, lines go
 * to the local {@link Spool} and are replayed later with their original receive time.
 * Not thread-safe by design: the collector calls it from a single worker thread.
 */
public final class Ingestor {

    private static final int REPLAY_BATCH = 100;

    private final ORMapper mapper;
    private final Spool spool;
    private final BooleanSupplier reconnect;
    private final ObjectMapper json = new ObjectMapper();
    private boolean healthy;

    /**
     * @param reconnect restores the database connection (tunnel + pool), true on success
     * @param healthy   whether the database was reachable at start-up
     */
    public Ingestor(ORMapper mapper, Spool spool, BooleanSupplier reconnect, boolean healthy) {
        this.mapper = mapper;
        this.spool = spool;
        this.reconnect = reconnect;
        this.healthy = healthy;
    }

    public boolean isHealthy() {
        return healthy;
    }

    /** E.g. the SSH tunnel dropped while idle: buffer until the next reconnect. */
    public void markUnhealthy() {
        if (healthy) System.err.println("DB:   Verbindung verloren - puffere lokal");
        healthy = false;
    }

    /** Handles one UART line received at the given time (epoch ms). */
    public void ingest(String line, long receivedAt) {
        Controller.ParsedData parsed = parse(line);
        if (parsed == null) return;

        // Keep arrival order: nothing overtakes buffered data
        if (!healthy || !spool.isEmpty()) {
            buffer(line, receivedAt);
            return;
        }
        try {
            store(parsed, receivedAt);
        } catch (RuntimeException first) {
            // Most often a stale connection: reconnect and retry once
            if (reconnect.getAsBoolean()) {
                try {
                    store(parsed, receivedAt);
                    return;
                } catch (RuntimeException second) {
                    if (mapper.ping()) {
                        drop(line, second);
                        return;
                    }
                }
            }
            markUnhealthy();
            buffer(line, receivedAt);
        }
    }

    /** Reconnects if needed and replays buffered lines in order. */
    public void drain() {
        if (healthy && spool.isEmpty()) return;
        if (!healthy) {
            if (!reconnect.getAsBoolean()) return;
            healthy = true;
            System.out.println("DB:   wieder erreichbar - " + spool.size() + " gepufferte Zeile(n) werden nachgetragen");
        }

        List<Spool.Entry> entries = spool.readAll();
        int done = 0, replayed = 0;
        for (Spool.Entry e : entries) {
            Controller.ParsedData parsed = e.receivedAt() < 0 ? null : parse(e.line());
            if (parsed != null) {
                try {
                    store(parsed, e.receivedAt());
                    replayed++;
                } catch (RuntimeException ex) {
                    if (!mapper.ping()) {
                        markUnhealthy();
                        break;
                    }
                    drop(e.line(), ex);
                }
            }
            done++;
            if (done % REPLAY_BATCH == 0) {
                spool.removeFirst(REPLAY_BATCH);
                done = 0;
            }
        }
        spool.removeFirst(done);
        if (replayed > 0) {
            System.out.println("DB:   " + replayed + " gepufferte Zeile(n) nachgetragen, " + spool.size() + " offen");
        }
    }

    private Controller.ParsedData parse(String line) {
        JsonNode root;
        try {
            root = json.readTree(line);
        } catch (Exception ex) {
            System.err.println("Parse-Fehler: " + ex.getMessage());
            return null;
        }
        if (root == null || !root.isObject()) return null;
        if (root.has("sys")) {
            System.out.println("System: " + root.get("sys").asText());
            return null;
        }
        Controller.ParsedData parsed = Controller.parseLine(root);
        return parsed.values.isEmpty() ? null : parsed;
    }

    private void buffer(String line, long receivedAt) {
        if (!spool.append(receivedAt, line)) {
            System.err.println("Puffer voll (" + Spool.MAX_ENTRIES + " Zeilen) - Zeile verworfen: " + line);
        }
    }

    private static void drop(String line, RuntimeException cause) {
        System.err.println("Datensatz verworfen (" + cause.getMessage() + "): " + line);
    }

    private void store(Controller.ParsedData parsed, long receivedAt) {
        Date at = new Date(receivedAt);
        try {
            Device device = getOrCreateDevice(parsed.src, at);
            for (Map.Entry<Sensor.SensorType, Double> e : parsed.values.entrySet()) {
                Sensor sensor = getOrCreateSensor(device, e.getKey());
                mapper.persist(new Measurement(sensor, e.getValue(), at));
            }
            System.out.printf("OK  %-10s  %d Messwerte gespeichert%n", parsed.src, parsed.values.size());
        } finally {
            try {
                mapper.clear();
            } catch (RuntimeException ignored) {
                // no session while disconnected
            }
        }
    }

    private Device getOrCreateDevice(String hardwareId, Date at) {
        Device device = mapper.findByHardwareId(Device.class, hardwareId);
        if (device == null) {
            // Name defaults to the hardware id until the user renames it in the web app
            device = new Device(hardwareId, Device.DeviceType.SENSOR, "unknown");
            device.setHardwareId(hardwareId);
            mapper.persist(device);
            System.out.println("  Neues Device: " + hardwareId);
        }
        device.setOnline(true);
        if (device.getLastSeen() == null || at.after(device.getLastSeen())) device.setLastSeen(at);
        mapper.merge(device);
        return device;
    }

    private Sensor getOrCreateSensor(Device device, Sensor.SensorType type) {
        Sensor sensor = mapper.findByNameAndDevice(Sensor.class, type.name(), device);
        if (sensor == null) {
            sensor = new Sensor(device, type, type.name());
            mapper.persist(sensor);
            System.out.println("  Neuer Sensor: " + type.name() + " auf " + device.getName());
        }
        return sensor;
    }
}
