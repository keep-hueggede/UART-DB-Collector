import Model.UserAccount;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** app.config loading and the shared database setup (H2, optional tunnel). */
public class Test_DatabaseSetup {

    private static Properties h2(String name) {
        Properties p = new Properties();
        p.setProperty("DB.DRIVER", "h2");
        p.setProperty("DB.NAME", name);
        return p;
    }

    /** Counts start/stop calls instead of opening a real SSH tunnel. */
    private static class FakeTunnel implements DatabaseSetup.Tunnel {
        int starts;
        boolean running;

        public int start() { starts++; running = true; return 0; }
        public void stop() { running = false; }
        public boolean isRunning() { return running; }
    }

    @Test
    public void loadsConfigFromGivenPath() throws Exception {
        Path file = Files.createTempFile("app", ".config");
        Files.writeString(file, "DB.DRIVER=mysql\nDB.NAME=SmartHome\n");
        Properties p = DatabaseSetup.loadConfig(new String[]{file.toString()});
        assertEquals("mysql", p.getProperty("DB.DRIVER"));
        assertEquals("SmartHome", p.getProperty("DB.NAME"));
    }

    @Test
    public void missingConfigNamesTheFile() {
        IOException e = assertThrows(IOException.class,
                () -> DatabaseSetup.loadConfig(new String[]{"./does-not-exist.config"}));
        assertTrue(e.getMessage().contains("does-not-exist.config"));
    }

    @Test
    public void describeShowsTargetWithDefaults() {
        assertEquals("h2 -> localhost:3306/wetter", new DatabaseSetup(new Properties()).describe());
        Properties p = new Properties();
        p.setProperty("DB.DRIVER", "mysql");
        p.setProperty("DB.HOST", " db.example.org ");
        p.setProperty("DB.PORT", "3307");
        p.setProperty("DB.NAME", "SmartHome");
        assertEquals("mysql -> db.example.org:3307/SmartHome", new DatabaseSetup(p).describe());
    }

    @Test
    public void connectsAndPingsH2() {
        DatabaseSetup db = new DatabaseSetup(h2("setupcore"));
        assertFalse(db.ping(), "no mapper yet");
        ORMapper mapper = db.createMapper();
        db.connect();
        try {
            assertTrue(db.ping());
            assertFalse(db.tunnelLost());
            mapper.persist(new UserAccount("sub-" + System.nanoTime(), "a@test.example", "A", ""));
            db.reconnect();
            assertTrue(db.ping());
        } finally {
            db.close();
        }
    }

    @Test
    public void tunnelIsStartedAndRestored() {
        FakeTunnel tunnel = new FakeTunnel();
        DatabaseSetup db = new DatabaseSetup(h2("setuptunnel"), tunnel, 13306, "pi@server");
        assertEquals("h2 -> SSH-Tunnel pi@server/setuptunnel", db.describe());
        db.createMapper();
        db.connect();
        assertEquals(1, tunnel.starts);
        assertFalse(db.tunnelLost());

        tunnel.running = false;          // e.g. network drop
        assertTrue(db.tunnelLost());
        db.reconnect();
        assertEquals(2, tunnel.starts);
        assertFalse(db.tunnelLost());

        db.close();
        assertFalse(tunnel.isRunning());
    }

    @Test
    public void entityListContainsAllTables() {
        assertTrue(DatabaseSetup.ENTITIES.contains(UserAccount.class));
        assertTrue(DatabaseSetup.ENTITIES.contains(Model.Setting.class));
        assertTrue(DatabaseSetup.ENTITIES.contains(Model.Collector.class));
        assertEquals(8, DatabaseSetup.ENTITIES.size());
    }

    @Test
    public void userRoleDefaultsToUser() {
        UserAccount u = new UserAccount("sub", "a@test.example", "A", "");
        assertFalse(u.isAdmin());
        u.setRole(UserAccount.Role.ADMIN);
        assertTrue(u.isAdmin());
    }
}
