package ORMUtil;

import Model.Actuator;
import Model.Collector;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.Setting;
import Model.UserAccount;
import Model.UserDevice;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Shared database setup for collector and web server: reads the DB.* keys of
 * app.config and creates the ORMapper. An optional {@link Tunnel} (the
 * collector's SSH tunnel) is started before connecting; core itself has no
 * SSH dependency, so the web server jar stays free of it.
 */
public final class DatabaseSetup {

    // Every module registers all entities so both sides keep the same schema
    public static final List<Class> ENTITIES = List.of(Device.class, Sensor.class, Measurement.class,
            Actuator.class, UserAccount.class, UserDevice.class, Setting.class, Collector.class);

    /** Local port forwarding to the database (implemented by the collector's SSH tunnel). */
    public interface Tunnel {
        int start();
        void stop();
        boolean isRunning();
    }

    private final String driver;
    private final String host;
    private final int port;
    private final String name;
    private final String user;
    private final String password;
    private final String params;
    private final Tunnel tunnel;
    private final String tunnelDescription;
    private ORMapper mapper;

    public DatabaseSetup(Properties p) {
        this(p, null, 0, null);
    }

    /**
     * Database reached through a tunnel listening on 127.0.0.1:localPort.
     * The port is fixed up front, so the JDBC URL stays valid across reconnects.
     */
    public DatabaseSetup(Properties p, Tunnel tunnel, int localPort, String tunnelDescription) {
        this.driver = p.getProperty("DB.DRIVER", "h2").trim();
        this.name = p.getProperty("DB.NAME", "wetter").trim();
        this.user = p.getProperty("DB.USER", "sa");
        this.password = p.getProperty("DB.PASSWORD", "");
        this.params = p.getProperty("DB.PARAMS", "");
        this.tunnel = tunnel;
        this.tunnelDescription = tunnelDescription;
        if (tunnel != null) {
            this.host = "127.0.0.1";
            this.port = localPort;
        } else {
            this.host = p.getProperty("DB.HOST", "localhost").trim();
            this.port = Integer.parseInt(p.getProperty("DB.PORT", "3306").trim());
        }
    }

    /** Loads ./app.config (path overridable with the first program argument). */
    public static Properties loadConfig(String[] args) throws IOException {
        Path path = Path.of(args != null && args.length > 0 ? args[0] : "./app.config");
        if (!Files.isRegularFile(path)) {
            throw new IOException("Konfiguration nicht gefunden: " + path.toAbsolutePath()
                    + " (Vorlage: app.config.example)");
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            p.load(in);
        }
        return p;
    }

    /** Creates the ORMapper without connecting. */
    public ORMapper createMapper() {
        if ("h2".equalsIgnoreCase(driver) && !driverAvailable("org.h2.Driver")) {
            // the web server jar ships without H2 on purpose
            throw new IllegalStateException("DB.DRIVER=h2 wird von diesem Programm nicht unterstuetzt"
                    + " - DB.DRIVER=mysql verwenden");
        }
        this.mapper = new ORMapper(host, port, name, user, password, driver, params, ENTITIES);
        return this.mapper;
    }

    /** Starts the tunnel (if any) and connects the mapper; throws if unreachable. */
    public void connect() {
        if (tunnel != null) tunnel.start();
        mapper.connect();
    }

    /** Restores a lost tunnel and replaces the mapper's connection pool. */
    public void reconnect() {
        if (tunnel != null && !tunnel.isRunning()) tunnel.start();
        mapper.reconnect();
    }

    /** True if the database answers on a fresh connection. */
    public boolean ping() {
        return mapper != null && mapper.ping();
    }

    public boolean tunnelLost() {
        return tunnel != null && !tunnel.isRunning();
    }

    public String describe() {
        return driver + " -> " + (tunnel != null ? "SSH-Tunnel " + tunnelDescription : host + ":" + port) + "/" + name;
    }

    public void close() {
        if (mapper != null) mapper.disconnect();
        if (tunnel != null) tunnel.stop();
    }

    private static boolean driverAvailable(String className) {
        try {
            Class.forName(className, false, DatabaseSetup.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
