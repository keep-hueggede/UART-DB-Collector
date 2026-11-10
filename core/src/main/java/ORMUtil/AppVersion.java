package ORMUtil;

import java.io.InputStream;
import java.util.Properties;

/** Release version of the running jar ("dev" for local builds). */
public final class AppVersion {

    private static final String VERSION = load();

    private AppVersion() {}

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/app-version.properties")) {
            if (in == null) return "dev";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "dev").trim();
            return v.isEmpty() || v.startsWith("${") ? "dev" : v;
        } catch (Exception e) {
            return "dev";
        }
    }
}
