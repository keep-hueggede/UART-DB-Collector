package WebServer;

import Model.Device;
import Model.Measurement;
import Model.Sensor;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.Repositories;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Low-battery mails go to the assigned users once, and again only after a day of "ok". */
public class Test_BatteryWatcher {

    private static final long HOUR = 60L * 60 * 1000;

    private ORMapper mapper;
    private Repositories repos;
    private Sensor battery;
    private final List<String> mails = new ArrayList<>();
    private boolean configured = true;
    private BatteryWatcher watcher;

    @BeforeEach
    public void setUp() {
        mapper = new ORMapper("localhost", 3306, "batterytest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        for (String e : List.of("UserDevice", "Measurement", "Sensor", "Device", "UserAccount", "Setting")) {
            mapper.executeHQL("DELETE FROM " + e);
        }
        repos = Repositories.of(mapper);
        int owner = repos.users().loginOrCreate("sub-owner", "owner@test.example", "Owner", "").getId();
        repos.users().loginOrCreate("sub-other", "other@test.example", "Other", "");

        Device d = new Device("Garten", Device.DeviceType.SENSOR, "");
        mapper.persist(d);
        battery = new Sensor(d, Sensor.SensorType.BATTERY_LOW, "BATTERY_LOW");
        mapper.persist(battery);
        repos.devices().assign(owner, d.getId());
        mapper.clear();

        watcher = new BatteryWatcher(repos.measurements(), repos.users(), repos.settings(), () -> new Mailer() {
            public boolean isConfigured() { return configured; }
            public void send(List<String> to, String subject, String text) { mails.add(to + " " + subject); }
        });
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    private void reading(double value, long at) {
        mapper.persist(new Measurement(battery, value, new Date(at)));
        mapper.clear();
    }

    @Test
    public void lowBatteryMailsOnlyAssignedUsersOnce() {
        long now = System.currentTimeMillis();
        reading(0, now - 2 * HOUR);
        assertEquals(0, watcher.check(now));

        reading(1, now - HOUR);
        assertEquals(1, watcher.check(now));
        assertEquals(List.of("[owner@test.example] Batterie schwach: Garten"), mails);

        // still low, or briefly ok again: no second mail
        assertEquals(0, watcher.check(now + HOUR));
        reading(0, now);
        assertEquals(0, watcher.check(now + HOUR));
        reading(1, now + HOUR);
        assertEquals(0, watcher.check(now + 2 * HOUR));
        assertEquals(1, mails.size());
    }

    @Test
    public void mailsAgainAfterADayOfOk() {
        long now = System.currentTimeMillis();
        reading(1, now - 30 * HOUR);
        assertEquals(1, watcher.check(now - 30 * HOUR));
        reading(0, now - 26 * HOUR);
        assertEquals(0, watcher.check(now));      // no low reading in the last 24 h -> reset
        reading(1, now);
        assertEquals(1, watcher.check(now));
        assertEquals(2, mails.size());
    }

    @Test
    public void waitsUntilMailIsConfigured() {
        long now = System.currentTimeMillis();
        reading(1, now);
        configured = false;
        assertEquals(0, watcher.check(now));
        configured = true;
        assertEquals(1, watcher.check(now));
    }
}
