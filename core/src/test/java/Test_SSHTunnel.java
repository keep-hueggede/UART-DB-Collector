import SSHTunnel.SSHTunnel;
import SSHTunnel.SSHTunnelConfig;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Test_SSHTunnel {

    private static final String USER = "tunnel";
    private static final String PASSWORD = "geheim";

    private Path tmp;
    private SshServer sshd;
    private EchoServer echo;
    private SSHTunnel tunnel;

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("ssh-tunnel-test");
        echo = new EchoServer();

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.setPasswordAuthenticator((u, p, s) -> USER.equals(u) && PASSWORD.equals(p));
        sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshd.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) tunnel.stop();
        if (sshd != null) sshd.stop(true);
        if (echo != null) echo.close();
    }

    private SSHTunnelConfig config() {
        SSHTunnelConfig c = new SSHTunnelConfig();
        c.host = "127.0.0.1";
        c.port = sshd.getPort();
        c.user = USER;
        c.password = PASSWORD;
        c.authType = SSHTunnelConfig.AuthType.PASSWORD;
        c.strictHostKeyChecking = false;
        c.remoteHost = "127.0.0.1";
        c.remotePort = echo.port();
        c.localPort = 0;
        c.connectTimeoutMs = 5_000;
        return c;
    }

    private static void assertEcho(int localPort) throws Exception {
        try (Socket s = new Socket("127.0.0.1", localPort)) {
            s.setSoTimeout(5_000);
            s.getOutputStream().write("hallo\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String line = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertEquals("hallo", line);
        }
    }

    @Test
    public void passwordAuthForwardsTcpTraffic() throws Exception {
        tunnel = new SSHTunnel(config());
        int localPort = tunnel.start();
        assertTrue(localPort > 0);
        assertTrue(tunnel.isRunning());
        assertEcho(localPort);
    }

    @Test
    public void startIsIdempotent() throws Exception {
        tunnel = new SSHTunnel(config());
        int first = tunnel.start();
        assertEquals(first, tunnel.start());
        assertEcho(first);
    }

    @Test
    public void restartAfterStopWorks() throws Exception {
        tunnel = new SSHTunnel(config());
        tunnel.start();
        tunnel.stop();
        int second = tunnel.start();
        assertTrue(tunnel.isRunning());
        assertEcho(second);
    }

    @Test
    public void stopClosesLocalListener() throws Exception {
        tunnel = new SSHTunnel(config());
        int localPort = tunnel.start();
        tunnel.stop();
        assertFalse(tunnel.isRunning());
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", localPort), 2_000);
            }
        });
    }

    @Test
    public void wrongPasswordFails() {
        SSHTunnelConfig c = config();
        c.password = "falsch";
        tunnel = new SSHTunnel(c);
        IllegalStateException ex = assertThrows(IllegalStateException.class, tunnel::start);
        assertTrue(ex.getMessage().contains("SSH-Verbindung"));
        assertFalse(tunnel.isRunning());
    }

    @Test
    public void publicKeyAuthForwardsTcpTraffic() throws Exception {
        Path key = tmp.resolve("id_rsa");
        KeyPair keyPair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        keyPair.writePrivateKey(key.toString());

        SSHTunnelConfig c = config();
        c.authType = SSHTunnelConfig.AuthType.PUBLICKEY;
        c.privateKey = key.toString();
        c.password = null;
        tunnel = new SSHTunnel(c);

        assertEcho(tunnel.start());
    }

    @Test
    public void strictHostKeyCheckingRejectsUnknownHost() throws Exception {
        Path knownHosts = tmp.resolve("known_hosts");
        Files.createFile(knownHosts);
        SSHTunnelConfig c = config();
        c.strictHostKeyChecking = true;
        c.knownHosts = knownHosts.toString();
        tunnel = new SSHTunnel(c);
        IllegalStateException ex = assertThrows(IllegalStateException.class, tunnel::start);
        assertTrue(ex.getMessage().contains("Hostkey unbekannt"));
        assertTrue(ex.getMessage().contains("ssh-keyscan"));
        assertFalse(tunnel.isRunning());
    }

    @Test
    public void missingKnownHostsFileIsReported() {
        SSHTunnelConfig c = config();
        c.strictHostKeyChecking = true;
        c.knownHosts = tmp.resolve("nicht-vorhanden").toString();
        tunnel = new SSHTunnel(c);
        IllegalStateException ex = assertThrows(IllegalStateException.class, tunnel::start);
        assertTrue(ex.getMessage().contains("SSH.KNOWN_HOSTS"));
    }

    @Test
    public void configParsingUsesDefaultsAndAuthInference() {
        Properties p = new Properties();
        p.setProperty("SSH.HOST", "example.org");
        p.setProperty("SSH.USER", "u");
        p.setProperty("SSH.PASSWORD", "pw");

        SSHTunnelConfig c = SSHTunnelConfig.from(p);
        assertFalse(c.enabled);
        assertEquals("example.org", c.host);
        assertEquals(22, c.port);
        assertEquals(3306, c.remotePort);
        assertEquals(0, c.localPort);
        assertEquals(15_000, c.connectTimeoutMs);
        assertTrue(c.strictHostKeyChecking);
        assertEquals(SSHTunnelConfig.AuthType.PASSWORD, c.authType);

        p.setProperty("SSH.PRIVATE_KEY", "/tmp/key");
        assertEquals(SSHTunnelConfig.AuthType.PUBLICKEY, SSHTunnelConfig.from(p).authType);

        p.setProperty("SSH.AUTH", "password");
        assertEquals(SSHTunnelConfig.AuthType.PASSWORD, SSHTunnelConfig.from(p).authType);

        p.setProperty("SSH.PASSWORD", " pw ");
        assertEquals(" pw ", SSHTunnelConfig.from(p).password);
    }

    @Test
    public void validateRejectsIncompleteConfig() {
        SSHTunnelConfig c = new SSHTunnelConfig();
        assertThrows(IllegalArgumentException.class, c::validate);

        c.host = "h";
        c.user = "u";
        c.authType = SSHTunnelConfig.AuthType.PUBLICKEY;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("SSH.PRIVATE_KEY"));
    }

    private static final class EchoServer implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> clients = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        EchoServer() throws IOException {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "echo-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket s = server.accept();
                    clients.add(s);
                    Thread t = new Thread(() -> echo(s), "echo-client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void echo(Socket s) {
            try (s) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = s.getInputStream().read(buffer)) != -1) {
                    s.getOutputStream().write(buffer, 0, read);
                    s.getOutputStream().flush();
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                server.close();
            } catch (IOException ignored) {
            }
            for (Socket s : clients) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
