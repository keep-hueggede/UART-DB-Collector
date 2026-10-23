package SSHTunnel;

import java.util.Properties;

public final class SSHTunnelConfig {

    public enum AuthType {
        PASSWORD,
        PUBLICKEY;

        public static AuthType parse(String value) {
            if (value == null || value.isBlank()) return null;
            return switch (value.trim().toLowerCase()) {
                case "publickey", "key", "public_key" -> PUBLICKEY;
                case "password" -> PASSWORD;
                default -> throw new IllegalArgumentException(
                        "SSH.AUTH muss 'password' oder 'publickey' sein: " + value);
            };
        }
    }

    public boolean enabled = false;
    public String host;
    public int port = 22;
    public String user;
    public AuthType authType;
    public String password;
    public String privateKey;
    public String passphrase;
    public String knownHosts;
    public boolean strictHostKeyChecking = true;
    public String remoteHost = "127.0.0.1";
    public int remotePort = 3306;
    public int localPort = 0;
    public int connectTimeoutMs = 15_000;

    public static SSHTunnelConfig from(Properties p) {
        SSHTunnelConfig c = new SSHTunnelConfig();
        c.enabled = Boolean.parseBoolean(value(p, "SSH.ENABLED", "false"));
        c.host = value(p, "SSH.HOST", null);
        c.port = integer(p, "SSH.PORT", c.port);
        c.user = value(p, "SSH.USER", null);
        c.password = secret(p, "SSH.PASSWORD");
        c.privateKey = expandHome(value(p, "SSH.PRIVATE_KEY", null));
        c.passphrase = secret(p, "SSH.PASSPHRASE");
        c.knownHosts = expandHome(value(p, "SSH.KNOWN_HOSTS", null));
        c.strictHostKeyChecking = Boolean.parseBoolean(
                value(p, "SSH.STRICT_HOST_KEY_CHECKING", "true"));
        c.remoteHost = value(p, "SSH.REMOTE_HOST", c.remoteHost);
        c.remotePort = integer(p, "SSH.REMOTE_PORT", c.remotePort);
        c.localPort = integer(p, "SSH.LOCAL_PORT", c.localPort);
        c.connectTimeoutMs = integer(p, "SSH.CONNECT_TIMEOUT", c.connectTimeoutMs);

        AuthType explicit = AuthType.parse(value(p, "SSH.AUTH", null));
        if (explicit != null) {
            c.authType = explicit;
        } else if (c.privateKey != null) {
            c.authType = AuthType.PUBLICKEY;
        } else {
            c.authType = AuthType.PASSWORD;
        }
        return c;
    }

    public void validate() {
        require(host, "SSH.HOST fehlt");
        require(user, "SSH.USER fehlt");
        checkPort(port, "SSH.PORT");
        checkPort(remotePort, "SSH.REMOTE_PORT");
        if (localPort < 0 || localPort > 65_535) {
            throw new IllegalArgumentException("SSH.LOCAL_PORT ungueltig: " + localPort);
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("SSH.CONNECT_TIMEOUT muss > 0 sein");
        }
        if (authType == AuthType.PUBLICKEY) {
            require(privateKey, "SSH.PRIVATE_KEY fehlt (SSH.AUTH=publickey)");
        } else {
            require(password, "SSH.PASSWORD fehlt (SSH.AUTH=password)");
        }
        if (strictHostKeyChecking) {
            require(knownHosts, "SSH.KNOWN_HOSTS fehlt bei strikter Hostkey-Pruefung "
                    + "(oder SSH.STRICT_HOST_KEY_CHECKING=false setzen)");
        }
    }

    public boolean usesPasswordAuth() {
        return authType != AuthType.PUBLICKEY;
    }

    static String expandHome(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        if (p.equals("~")) return System.getProperty("user.home");
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }

    private static void checkPort(int port, String key) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(key + " ungueltig: " + port);
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String value(Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        if (v == null) return fallback;
        v = v.trim();
        return v.isEmpty() ? fallback : v;
    }

    private static String secret(Properties p, String key) {
        String v = p.getProperty(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static int integer(Properties p, String key, int fallback) {
        String v = value(p, key, null);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " ist keine Zahl: " + v);
        }
    }
}
