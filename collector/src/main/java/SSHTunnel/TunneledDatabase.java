package SSHTunnel;

import ORMUtil.DatabaseSetup;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;

/** Builds the collector's DatabaseSetup, routed through the SSH tunnel when SSH.ENABLED=true. */
public final class TunneledDatabase {

    private TunneledDatabase() {}

    public static DatabaseSetup from(Properties p) throws IOException {
        SSHTunnelConfig ssh = SSHTunnelConfig.from(p);
        if (!ssh.enabled) return new DatabaseSetup(p);

        String driver = p.getProperty("DB.DRIVER", "h2").trim();
        if (!"mysql".equalsIgnoreCase(driver)) {
            throw new IllegalArgumentException("SSH.ENABLED=true erfordert DB.DRIVER=mysql (aktuell: " + driver + ")");
        }
        // Fix the local port now: the JDBC URL must stay valid across tunnel reconnects
        if (ssh.localPort == 0) ssh.localPort = freeLocalPort();
        return new DatabaseSetup(p, new SSHTunnel(ssh), ssh.localPort, ssh.host);
    }

    private static int freeLocalPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
