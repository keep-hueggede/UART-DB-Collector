import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.Repositories;
import WebServer.AppSettings;
import WebServer.GoogleAuth;
import WebServer.SessionManager;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Settings stored in the database and applied at runtime. */
public class Test_AppSettings {

    private ORMapper mapper;
    private GoogleAuth google;
    private SessionManager sessions;
    private AppSettings settings;

    @BeforeEach
    public void setUp() {
        mapper = new ORMapper("localhost", 3306, "settingstest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        mapper.executeHQL("DELETE FROM Setting");
        google = new GoogleAuth("", "", "");
        sessions = new SessionManager();
        settings = new AppSettings(Repositories.of(mapper).settings(), google, sessions);
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    @Test
    public void importsGoogleValuesFromConfigOnce() {
        Properties p = new Properties();
        p.setProperty("GOOGLE.CLIENT_ID", "id-from-config");
        p.setProperty("GOOGLE.CLIENT_SECRET", "secret-from-config");
        settings.importFromConfig(p);
        settings.apply();
        assertTrue(google.isConfigured());
        assertEquals("id-from-config", settings.view().get("googleClientId"));

        // later edits in the web app win over the (stale) config file
        settings.update("id-from-web", null, null, null);
        settings.importFromConfig(p);
        assertEquals("id-from-web", settings.view().get("googleClientId"));
    }

    @Test
    public void secretIsNeverReturned() {
        settings.update("id", "top-secret", "", 7);
        Map<String, Object> view = settings.view();
        assertEquals(true, view.get("googleClientSecretSet"));
        assertFalse(view.toString().contains("top-secret"));
    }

    @Test
    public void nullKeepsTheStoredSecret() {
        settings.update("id", "secret", "", null);
        settings.update("other-id", null, null, null);
        assertTrue(google.isConfigured());
        assertEquals(true, settings.view().get("googleClientSecretSet"));
    }

    @Test
    public void sessionLifetimeAppliesImmediately() {
        settings.update(null, null, null, 7);
        assertEquals(7L * 24 * 60 * 60 * 1000, sessions.getTtlMs());
        assertEquals(7, settings.view().get("sessionTtlDays"));
    }

    @Test
    public void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> settings.update(null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> settings.update(null, null, null, 9999));
        assertThrows(IllegalArgumentException.class, () -> settings.update("a\nb", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings.update(null, null, "https://evil.example/steal", null));
        settings.update(null, null, "https://sh.rwx-dev.de/auth/callback", null);
        assertEquals("https://sh.rwx-dev.de/auth/callback", settings.view().get("googleRedirectUri"));
    }
}
