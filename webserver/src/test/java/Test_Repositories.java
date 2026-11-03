import Model.Device;
import Model.Sensor;
import Model.UserAccount;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.DeviceRepository;
import Repository.Repositories;
import Repository.UserRepository;
import Repository.Viewer;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Roles, device assignment and admin/user visibility on the repository level. */
public class Test_Repositories {

    private ORMapper mapper;
    private Repositories repos;
    private UserRepository users;
    private DeviceRepository devices;
    private int admin;
    private int user;
    private int d1;
    private int d2;

    @BeforeEach
    public void setUp() {
        mapper = new ORMapper("localhost", 3306, "repotest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        for (String e : List.of("UserDevice", "Measurement", "Sensor", "Device", "UserAccount")) {
            mapper.executeHQL("DELETE FROM " + e);
        }
        repos = Repositories.of(mapper);
        users = repos.users();
        devices = repos.devices();

        admin = users.loginOrCreate("sub-admin", "admin@test.example", "Admin", "").getId();
        assertTrue(users.claimFirstAdmin(admin));
        user = users.loginOrCreate("sub-user", "user@test.example", "User", "").getId();

        Device a = new Device("dev1", Device.DeviceType.SENSOR, "");
        Device b = new Device("dev2", Device.DeviceType.SENSOR, "");
        mapper.persist(a);
        mapper.persist(b);
        mapper.persist(new Sensor(a, Sensor.SensorType.TEMPERATURE, "t"));
        mapper.persist(new Sensor(b, Sensor.SensorType.HUMIDITY, "h"));
        d1 = a.getId();
        d2 = b.getId();
        mapper.clear();
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    @Test
    public void loginRefreshesProfileWithoutChangingRole() {
        UserAccount again = users.loginOrCreate("sub-admin", "new@test.example", "Renamed", "pic");
        assertEquals(admin, again.getId());
        assertEquals("new@test.example", again.getEmail());
        assertTrue(users.isAdmin(admin));
        assertNotNull(again.getLastLoginAt());
    }

    @Test
    public void lastAdminCannotBeDemoted() {
        assertEquals(UserRepository.RoleResult.LAST_ADMIN, users.setRole(admin, UserAccount.Role.USER));
        assertEquals(UserRepository.RoleResult.OK, users.setRole(user, UserAccount.Role.ADMIN));
        assertEquals(2, users.adminCount());
        // with a second admin the first may step down
        assertEquals(UserRepository.RoleResult.OK, users.setRole(admin, UserAccount.Role.USER));
        assertFalse(users.isAdmin(admin));
        assertEquals(UserRepository.RoleResult.NOT_FOUND, users.setRole(999999, UserAccount.Role.ADMIN));
        assertFalse(users.isAdmin(999999));
    }

    @Test
    public void assignmentControlsVisibility() {
        Viewer asUser = new Viewer(user, false);
        Viewer asAdmin = new Viewer(admin, true);
        assertEquals(2, devices.listVisible(asAdmin).size());
        assertEquals(0, devices.listVisible(asUser).size());
        assertEquals(0, devices.countAssigned(user));

        assertEquals(DeviceRepository.AssignResult.OK, devices.assign(user, d1));
        assertEquals(DeviceRepository.AssignResult.OK, devices.assign(user, d1));
        assertEquals(1, devices.countAssigned(user));
        assertEquals(1, devices.listVisible(asUser).size());
        assertEquals(1, repos.sensors().listVisible(asUser).size());
        assertEquals(2, repos.sensors().listVisible(asAdmin).size());

        Map<String, Object> listed = users.listAll().stream()
                .filter(u -> u.get("id").equals(user)).findFirst().orElseThrow();
        assertEquals(List.of(d1), listed.get("deviceIds"));

        assertTrue(devices.unassign(user, d1));
        assertFalse(devices.unassign(user, d1));
        assertEquals(0, devices.listVisible(asUser).size());
    }

    @Test
    public void assignRejectsUnknownIds() {
        assertEquals(DeviceRepository.AssignResult.USER_NOT_FOUND, devices.assign(999999, d1));
        assertEquals(DeviceRepository.AssignResult.DEVICE_NOT_FOUND, devices.assign(user, 999999));
        assertEquals(2, devices.count());
    }

    @Test
    public void renameScopedByViewer() {
        assertEquals(DeviceRepository.RenameResult.NOT_FOUND, devices.rename(new Viewer(user, false), d2, "x"));
        assertEquals(DeviceRepository.RenameResult.OK, devices.rename(new Viewer(admin, true), d2, "x"));
        assertEquals(DeviceRepository.RenameResult.NAME_TAKEN, devices.rename(new Viewer(admin, true), d1, "x"));
    }
}
