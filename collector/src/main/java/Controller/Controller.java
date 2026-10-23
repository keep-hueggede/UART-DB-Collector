package Controller;

import Model.Sensor;
import Listener.IObserverListener;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import UARTCom.UARTCom;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Controller implements IObserverListener {

    private static final int RETRY_S = 30;
    private static final int LOG_EVERY_N_FAILURES = 20;   // ~10 min at RETRY_S

    private UARTCom com;
    private DatabaseSetup db;
    private Ingestor ingestor;
    // All database work runs on this single thread (Hibernate session is not thread-safe)
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "db-writer");
        t.setDaemon(false);
        return t;
    });
    private int reconnectFailures = 0;

    public Controller(Properties prop) {
        try {
            this.db = new DatabaseSetup(prop);
            ORMapper mapper = this.db.createMapper();
            Spool spool = new Spool(Path.of(prop.getProperty("BUFFER.FILE", "./spool/pending.log")));

            // An unreachable database must not stop UART reception: start anyway and buffer
            boolean dbOk = tryConnect();
            this.ingestor = new Ingestor(mapper, spool, this::reconnect, dbOk);

            String uartPort = prop.getProperty("UART.PORTNAME", "/dev/serial0");
            int uartBaud = Integer.parseInt(prop.getProperty("UART.BAUD", "115200"));
            this.com = new UARTCom(uartPort, uartBaud);
            this.com.open();
            this.com.addListener(this);

            System.out.println("UART-DB-Collector gestartet.");
            System.out.println("UART: " + uartPort + " @ " + uartBaud + " baud");
            System.out.println("DB:   " + this.db.describe() + (dbOk ? "" : " (nicht erreichbar - puffere lokal)"));
            System.out.println("Puffer: " + spool.getFile() + " (" + spool.size() + " Zeile(n) offen)");

            this.worker.scheduleWithFixedDelay(this::maintain, 5, RETRY_S, TimeUnit.SECONDS);

        } catch (Exception ex) {
            // Fail fast instead of running half-initialised (NPE later on)
            this.worker.shutdownNow();
            if (this.db != null) this.db.close();
            throw new IllegalStateException("UART-DB-Collector startup failed: " + ex.getMessage(), ex);
        }
    }

    public void startListening() {
        this.com.Listen();
        System.out.println("Warte auf UART-Daten...");
    }

    public void shutdown() {
        this.com.close();
        this.worker.shutdown();
        try {
            this.worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.db.close();
        System.out.println("UART-DB-Collector beendet.");
    }

    private boolean tryConnect() {
        try {
            this.db.connect();
            return true;
        } catch (RuntimeException ex) {
            System.err.println("DB:   Verbindung fehlgeschlagen: " + ex.getMessage());
            return false;
        }
    }

    private boolean reconnect() {
        try {
            this.db.reconnect();
            this.reconnectFailures = 0;
            return true;
        } catch (RuntimeException ex) {
            if (this.reconnectFailures++ % LOG_EVERY_N_FAILURES == 0) {
                System.err.println("DB:   Wiederverbindung fehlgeschlagen (" + this.reconnectFailures
                        + ". Versuch): " + ex.getMessage());
            }
            return false;
        }
    }

    // Periodic: notice a dropped tunnel while idle, reconnect and replay the buffer
    private void maintain() {
        try {
            if (this.db.tunnelLost()) this.ingestor.markUnhealthy();
            this.ingestor.drain();
        } catch (RuntimeException ex) {
            System.err.println("Puffer: Fehler beim Nachtragen: " + ex.getMessage());
        }
    }

    /**
     * Zerlegt eine eingegangene UART-Zeile (JSON) in src + Messwert-Map.
     * Unterstützt:
     *   {"src":"wetter1","data":{"temp":..}}
     *   {"src":"wetter1","temp":..}
     *   {"temp":..}  (ohne src -> "unknown")
     */
    public static ParsedData parseLine(JsonNode root) {
        String src = root.has("src") ? root.get("src").asText() : "unknown";
        JsonNode data = root.has("data") ? root.get("data") : root;

        java.util.Map<Sensor.SensorType, Double> values = new java.util.HashMap<>();
        // "rain" is the amount since the node's previous report (mm), not a running total
        String[] fields = {"temp", "pressure", "humidity", "wind", "rain", "altitude", "battery"};
        Sensor.SensorType[] types = {
            Sensor.SensorType.TEMPERATURE,
            Sensor.SensorType.PRESSURE,
            Sensor.SensorType.HUMIDITY,
            Sensor.SensorType.WIND,
            Sensor.SensorType.RAIN,
            Sensor.SensorType.ALTITUDE,
            Sensor.SensorType.VOLTAGE
        };
        for (int i = 0; i < fields.length; i++) {
            JsonNode val = data.get(fields[i]);
            if (val != null && !val.isNull()) {
                values.put(types[i], val.asDouble());
            }
        }
        return new ParsedData(src, values);
    }

    public static class ParsedData {
        public final String src;
        public final java.util.Map<Sensor.SensorType, Double> values;
        public ParsedData(String src, java.util.Map<Sensor.SensorType, Double> values) {
            this.src = src;
            this.values = values;
        }
    }

    @Override
    public void onSignaled(String answer) {
        long receivedAt = System.currentTimeMillis();
        this.worker.execute(() -> this.ingestor.ingest(answer, receivedAt));
    }
}
