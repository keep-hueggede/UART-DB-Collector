package SSHTunnel;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SSHTunnel {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int KEEPALIVE_INTERVAL_MS = 30_000;
    private static final int KEEPALIVE_COUNT_MAX = 3;

    private final SSHTunnelConfig config;
    private Session session;
    private int boundPort = -1;

    public SSHTunnel(SSHTunnelConfig config) {
        if (config == null) throw new IllegalArgumentException("SSHTunnelConfig fehlt");
        this.config = config;
    }

    public synchronized int start() {
        if (isRunning()) return boundPort;
        if (this.session != null) {
            disconnectQuietly(this.session);
            this.session = null;
            this.boundPort = -1;
        }
        config.validate();

        JSch jsch = new JSch();
        configureKnownHosts(jsch);
        configureIdentity(jsch);

        Session s;
        try {
            s = jsch.getSession(config.user, config.host, config.port);
        } catch (JSchException e) {
            throw new IllegalStateException("SSH-Session konnte nicht erstellt werden: "
                    + e.getMessage(), e);
        }
        s.setConfig("StrictHostKeyChecking", config.strictHostKeyChecking ? "yes" : "no");
        if (config.usesPasswordAuth()) {
            s.setPassword(config.password);
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        } else {
            s.setConfig("PreferredAuthentications", "publickey");
        }
        s.setUserInfo(new TunnelUserInfo(config));

        try {
            s.setServerAliveInterval(KEEPALIVE_INTERVAL_MS);
            s.setServerAliveCountMax(KEEPALIVE_COUNT_MAX);
            s.connect(config.connectTimeoutMs);
        } catch (JSchException e) {
            disconnectQuietly(s);
            throw new IllegalStateException(connectErrorMessage(e), e);
        }

        try {
            this.boundPort = s.setPortForwardingL(LOOPBACK, config.localPort,
                    config.remoteHost, config.remotePort);
            this.session = s;
        } catch (JSchException e) {
            disconnectQuietly(s);
            throw new IllegalStateException("SSH-Portweiterleitung " + config.localPort + " -> "
                    + config.remoteHost + ":" + config.remotePort
                    + " fehlgeschlagen: " + e.getMessage(), e);
        }

        System.out.println("SSH:  " + LOOPBACK + ":" + boundPort + " -> " + config.remoteHost + ":"
                + config.remotePort + " via " + config.user + "@" + config.host + ":" + config.port);
        return boundPort;
    }

    public synchronized void stop() {
        Session s = this.session;
        this.session = null;
        this.boundPort = -1;
        if (s == null) return;
        try {
            s.disconnect();
            System.out.println("SSH:  Tunnel geschlossen");
        } catch (Exception e) {
            System.err.println("SSH:  Fehler beim Schliessen des Tunnels: " + e.getMessage());
        }
    }

    public synchronized boolean isRunning() {
        return session != null && session.isConnected();
    }

    public synchronized int getLocalPort() {
        return boundPort;
    }

    private void configureKnownHosts(JSch jsch) {
        if (config.knownHosts == null) {
            System.out.println("SSH:  keine known_hosts-Datei - Hostkey-Pruefung deaktiviert");
            return;
        }
        Path path = Path.of(config.knownHosts);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("SSH.KNOWN_HOSTS nicht gefunden: " + path
                    + " (erzeugen mit: ssh-keyscan -p " + config.port + " "
                    + config.host + " > " + path + ")");
        }
        try {
            jsch.setKnownHosts(path.toString());
        } catch (JSchException e) {
            throw new IllegalStateException("SSH.KNOWN_HOSTS konnte nicht gelesen werden: "
                    + path + " (" + e.getMessage() + ")", e);
        }
    }

    private void configureIdentity(JSch jsch) {
        if (config.usesPasswordAuth()) return;
        Path key = Path.of(config.privateKey);
        if (!Files.isRegularFile(key)) {
            throw new IllegalStateException("SSH.PRIVATE_KEY nicht gefunden: " + key);
        }
        try {
            if (config.passphrase == null) {
                jsch.addIdentity(key.toString());
            } else {
                jsch.addIdentity(key.toString(), config.passphrase);
            }
        } catch (JSchException e) {
            throw new IllegalStateException("SSH.PRIVATE_KEY konnte nicht geladen werden: "
                    + key + " (" + e.getMessage() + ")", e);
        }
    }

    private String connectErrorMessage(JSchException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String type = e.getClass().getSimpleName();
        String hint = "";
        if (message.contains("Auth fail") || message.contains("auth cancel")
                || type.contains("AuthCancel") || type.contains("PartialAuth")) {
            hint = " (Zugangsdaten bzw. Key pruefen)";
        } else if (type.contains("UnknownHostKey") || message.contains("UnknownHostKey")) {
            hint = " (Hostkey unbekannt: ssh-keyscan -p " + config.port + " "
                    + config.host + " >> " + config.knownHosts + ")";
        } else if (type.contains("ChangedHostKey") || message.contains("HostKey has been changed")) {
            hint = " (Hostkey des Servers hat sich geaendert)";
        }
        return "SSH-Verbindung zu " + config.user + "@" + config.host + ":" + config.port
                + " fehlgeschlagen: " + message + hint;
    }

    private static void disconnectQuietly(Session s) {
        try {
            s.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static final class TunnelUserInfo implements UserInfo {
        private final SSHTunnelConfig config;

        TunnelUserInfo(SSHTunnelConfig config) {
            this.config = config;
        }

        @Override
        public String getPassphrase() {
            return config.passphrase;
        }

        @Override
        public String getPassword() {
            return config.password;
        }

        @Override
        public boolean promptPassword(String message) {
            return config.usesPasswordAuth() && config.password != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return config.passphrase != null;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
        }
    }
}
