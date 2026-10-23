package Controller;

import Model.Actuator;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
import Listener.IObserverListener;
import ORMUtil.ORMapper;
import SSHTunnel.SSHTunnel;
import SSHTunnel.SSHTunnelConfig;
import UARTCom.UARTCom;
import WebServer.DbReader;
import WebServer.GoogleAuth;
import WebServer.SessionManager;
import WebServer.WebServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Controller implements IObserverListener {

    private UARTCom com;
    private ORMapper mapper;
    private SSHTunnel tunnel;
    private WebServer webServer;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    Properties prop;
    String configFile = "./app.config";

    private Map<Sensor.SensorType, Sensor> sensorCache = new HashMap<>();

    public Controller() {
        try {
            this.prop = new Properties();

            Path configPath = Paths.get(this.configFile);
            if (!Files.exists(configPath)) {
                configPath = Paths.get("./java/Controller/app.config");
            }
            this.prop.load(new FileInputStream(configPath.toFile()));

            String dbDriver = prop.getProperty("DB.DRIVER", "h2").trim();
            String dbHost = prop.getProperty("DB.HOST", "localhost").trim();
            int dbPort = Integer.parseInt(prop.getProperty("DB.PORT", "3306").trim());
            String dbName = prop.getProperty("DB.NAME", "wetter").trim();
            String dbUser = prop.getProperty("DB.USER", "sa");
            String dbPass = prop.getProperty("DB.PASSWORD", "");
            String dbParams = prop.getProperty("DB.PARAMS", "");

            SSHTunnelConfig sshConfig = SSHTunnelConfig.from(this.prop);
            if (sshConfig.enabled) {
                if (!"mysql".equalsIgnoreCase(dbDriver)) {
                    throw new IllegalArgumentException("SSH.ENABLED=true erfordert DB.DRIVER=mysql"
                            + " (aktuell: " + dbDriver + ")");
                }
                this.tunnel = new SSHTunnel(sshConfig);
                dbPort = this.tunnel.start();
                dbHost = "127.0.0.1";
            }

            this.mapper = new ORMapper(dbHost, dbPort, dbName, dbUser, dbPass, dbDriver, dbParams,
                    List.of(Device.class, Sensor.class, Measurement.class, Actuator.class,
                            UserAccount.class, UserDevice.class));
            this.mapper.connect();

            String uartPort = prop.getProperty("UART.PORTNAME", "/dev/serial0");
            int uartBaud = Integer.parseInt(prop.getProperty("UART.BAUD", "115200"));
            this.com = new UARTCom(uartPort, uartBaud);
            this.com.open();
            this.com.addListener(this);

            boolean serverEnabled = Boolean.parseBoolean(prop.getProperty("SERVER.ENABLED", "true"));
            if (serverEnabled) {
                String serverHost = prop.getProperty("SERVER.HOST", "0.0.0.0");
                int serverPort = Integer.parseInt(prop.getProperty("SERVER.PORT", "8080"));
                String webRoot = prop.getProperty("SERVER.WEBROOT", "./web");
                boolean secureCookie = Boolean.parseBoolean(prop.getProperty("SERVER.SECURE_COOKIE", "false"));
                GoogleAuth google = new GoogleAuth(
                        prop.getProperty("GOOGLE.CLIENT_ID", ""),
                        prop.getProperty("GOOGLE.CLIENT_SECRET", ""),
                        prop.getProperty("GOOGLE.REDIRECT_URI", ""));
                SessionManager sessions = new SessionManager();
                this.webServer = new WebServer(new DbReader(this.mapper), sessions, google,
                        serverHost, serverPort, webRoot, secureCookie);
                this.webServer.start();
                if (!google.isConfigured()) {
                    System.out.println("Web:  Google-Login nicht konfiguriert "
                            + "(GOOGLE.CLIENT_ID / GOOGLE.CLIENT_SECRET fehlen)");
                }
            }

            System.out.println("UART-DB-Collector gestartet.");
            System.out.println("UART: " + uartPort + " @ " + uartBaud + " baud");
            System.out.println("DB:   " + dbDriver + " -> " + dbHost + ":" + dbPort + "/" + dbName);
            if (this.webServer != null) {
                System.out.println("Web:  http://localhost:" + this.webServer.getPort() + "/");
            }

        } catch (Exception ex) {
            // Fail fast instead of running half-initialised (NPE later on)
            if (this.tunnel != null) this.tunnel.stop();
            throw new IllegalStateException("UART-DB-Collector startup failed: " + ex.getMessage(), ex);
        }
    }

    public void startListening() {
        this.com.Listen();
        System.out.println("Warte auf UART-Daten...");
    }

    public void shutdown() {
        if (this.webServer != null) this.webServer.stop();
        this.com.close();
        this.mapper.disconnect();
        if (this.tunnel != null) this.tunnel.stop();
        System.out.println("UART-DB-Collector beendet.");
    }

    private Device getOrCreateDevice(String hardwareId) {
        Device device = this.mapper.findByHardwareId(Device.class, hardwareId);
        if (device == null) {
            // Name defaults to the hardware id until the user renames it in the web app
            device = new Device(hardwareId, Device.DeviceType.SENSOR, "unknown");
            device.setHardwareId(hardwareId);
            this.mapper.persist(device);
            System.out.println("  Neues Device: " + hardwareId);
        }
        device.setOnline(true);
        device.setLastSeen(new Date());
        this.mapper.merge(device);
        return device;
    }

    private Sensor getOrCreateSensor(Device device, Sensor.SensorType type) {
        Sensor sensor = this.mapper.findByNameAndDevice(Sensor.class, type.name(), device);
        if (sensor == null) {
            sensor = new Sensor(device, type, type.name());
            this.mapper.persist(sensor);
            System.out.println("  Neuer Sensor: " + type.name() + " auf " + device.getName());
        }
        return sensor;
    }

    private void persistMeasurement(Sensor sensor, Double value) {
        if (value == null) return;
        Measurement m = new Measurement(sensor, value);
        this.mapper.persist(m);
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

    private void processParsed(String src, java.util.Map<Sensor.SensorType, Double> values) {
        if (values.isEmpty()) return;
        try {
            Device device = getOrCreateDevice(src);
            for (var e : values.entrySet()) {
                Sensor sensor = getOrCreateSensor(device, e.getKey());
                persistMeasurement(sensor, e.getValue());
            }
            System.out.printf("OK  %-10s  %d Messwerte gespeichert%n", src, values.size());
        } finally {
            this.mapper.clear();
        }
    }

    @Override
    public void onSignaled(String answer) {
        try {
            JsonNode root = jsonMapper.readTree(answer);

            if (root.has("sys")) {
                System.out.println("System: " + root.get("sys").asText());
                return;
            }

            if (root.has("temp") || root.has("pressure") || root.has("data")) {
                ParsedData parsed = parseLine(root);
                processParsed(parsed.src, parsed.values);
            }

        } catch (Exception ex) {
            System.err.println("Parse-Fehler: " + ex.getMessage());
        }
    }
}
