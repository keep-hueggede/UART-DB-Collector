package WebServer;

import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;

import java.util.Properties;

/** Entry point of the web server jar: REST API, Google login and PWA, no UART. */
public class WebMain {

    public static void main(String[] args) throws Exception {
        Properties prop = DatabaseSetup.loadConfig(args);

        DatabaseSetup db = new DatabaseSetup(prop);
        WebServer webServer;
        try {
            ORMapper mapper = db.createMapper();
            db.connect();

            String host = prop.getProperty("SERVER.HOST", "0.0.0.0");
            int port = Integer.parseInt(prop.getProperty("SERVER.PORT", "8080"));
            String webRoot = prop.getProperty("SERVER.WEBROOT", "./web");
            boolean secureCookie = Boolean.parseBoolean(prop.getProperty("SERVER.SECURE_COOKIE", "false"));
            GoogleAuth google = new GoogleAuth(
                    prop.getProperty("GOOGLE.CLIENT_ID", ""),
                    prop.getProperty("GOOGLE.CLIENT_SECRET", ""),
                    prop.getProperty("GOOGLE.REDIRECT_URI", ""));

            webServer = new WebServer(new DbReader(mapper), new SessionManager(), google,
                    host, port, webRoot, secureCookie);
            webServer.start();

            System.out.println("UART-DB-Webserver gestartet.");
            System.out.println("DB:   " + db.describe());
            System.out.println("Web:  http://" + host + ":" + webServer.getPort() + "/");
            if (!google.isConfigured()) {
                System.out.println("Web:  Google-Login nicht konfiguriert (GOOGLE.CLIENT_ID / GOOGLE.CLIENT_SECRET fehlen)");
            }
        } catch (Exception ex) {
            db.close();
            throw new IllegalStateException("UART-DB-Webserver startup failed: " + ex.getMessage(), ex);
        }

        WebServer started = webServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            started.stop();
            db.close();
            System.out.println("UART-DB-Webserver beendet.");
        }));
    }
}
