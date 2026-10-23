package ORMUtil;

import Model.Actuator;
import Model.Device;
import Model.Measurement;
import Model.Sensor;
import Model.UserAccount;
import Model.UserDevice;
import SSHTunnel.SSHTunnel;
import SSHTunnel.SSHTunnelConfig;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Shared database setup for collector and web server: reads the DB.* and SSH.*
 * keys of app.config, starts the optional SSH tunnel and creates the ORMapper.
 */
public final class DatabaseSetup {

    // Every module registers all entities so both sides keep the same schema
    public static final List<Class> ENTITIES = List.of(Device.class, Sensor.class, Measurement.class,
            Actuator.class, UserAccount.class, UserDevice.class);

    private final String driver;
    private final String host;
    private final int port;
    private final String name;
    private final String user;
    private final String password;
    private final String params;
    private final SSHTunnelConfig sshConfig;
    private SSHTunnel tunnel;
    private ORMapper mapper;

    public DatabaseSetup(Properties p) {
        this.driver = p.getProperty("DB.DRIVER", "h2").trim();
        this.host = p.getProperty("DB.HOST", "localhost").trim();
        this.port = Integer.parseInt(p.getProperty("DB.PORT", "3306").trim());
        this.name = p.getProperty("DB.NAME", "wetter").trim();
        this.user = p.getProperty("DB.USER", "sa");
        this.password = p.getProperty("DB.PASSWORD", "");
        this.params = p.getProperty("DB.PARAMS", "");
        this.sshConfig = SSHTunnelConfig.from(p);
        if (sshConfig.enabled && !"mysql".equalsIgnoreCase(driver)) {
            throw new IllegalArgumentException("SSH.ENABLED=true erfordert DB.DRIVER=mysql (aktuell: " + driver + ")");
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

    /**
     * Creates the ORMapper without connecting. With SSH the local tunnel port is
     * fixed here, so the JDBC URL stays valid even if the tunnel comes up later.
     */
    public ORMapper createMapper() throws IOException {
        String dbHost = host;
        int dbPort = port;
        if (sshConfig.enabled) {
            if (sshConfig.localPort == 0) sshConfig.localPort = freeLocalPort();
            this.tunnel = new SSHTunnel(sshConfig);
            dbHost = "127.0.0.1";
            dbPort = sshConfig.localPort;
        }
        this.mapper = new ORMapper(dbHost, dbPort, name, user, password, driver, params, ENTITIES);
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

    public boolean tunnelLost() {
        return tunnel != null && !tunnel.isRunning();
    }

    private static int freeLocalPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    public String describe() {
        return driver + " -> " + (sshConfig.enabled ? "SSH-Tunnel " + sshConfig.host : host + ":" + port) + "/" + name;
    }

    public void close() {
        if (mapper != null) mapper.disconnect();
        if (tunnel != null) tunnel.stop();
    }
}
