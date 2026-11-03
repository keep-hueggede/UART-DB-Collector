import Model.UserAccount;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import Repository.Repositories;
import Repository.UserRepository;
import WebServer.SetupService;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** One-time setup code and claiming the first admin. */
public class Test_SetupService {

    private ORMapper mapper;
    private UserRepository users;
    private Path dir;
    private SetupService setup;

    @BeforeEach
    public void setUp() throws Exception {
        mapper = new ORMapper("localhost", 3306, "setuptest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        mapper.executeHQL("DELETE FROM UserDevice");
        mapper.executeHQL("DELETE FROM UserAccount");
        users = Repositories.of(mapper).users();
        dir = Files.createTempDirectory("setup");
        setup = new SetupService(users, dir);
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    private String codeFromFile() throws Exception {
        return Files.readString(setup.codeFile()).strip();
    }

    @Test
    public void writesCodeFileWhenNoAdminExists() throws Exception {
        setup.init();
        assertTrue(setup.isRequired());
        assertEquals(dir.resolve(SetupService.CODE_FILE_NAME).toAbsolutePath().normalize(), setup.codeFile());
        assertTrue(codeFromFile().matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"), codeFromFile());
    }

    @Test
    public void correctCodeAndLoginMakeFirstAdmin() throws Exception {
        setup.init();
        int id = users.loginOrCreate("sub-1", "first@test.example", "First", "").getId();
        assertFalse(users.isAdmin(id), "new accounts start as USER");

        String claim = setup.verify(codeFromFile());
        assertNotNull(claim);
        assertTrue(setup.completeIfClaimed(claim, id));
        assertTrue(users.isAdmin(id));
        assertFalse(setup.isRequired());
        assertFalse(Files.exists(setup.codeFile()), "code file is removed once setup is done");

        // the claim cannot be reused
        int other = users.loginOrCreate("sub-2", "second@test.example", "Second", "").getId();
        assertFalse(setup.completeIfClaimed(claim, other));
        assertFalse(users.isAdmin(other));
    }

    @Test
    public void codeIsAcceptedWithoutDashesAndInLowerCase() throws Exception {
        setup.init();
        String code = codeFromFile();
        assertNotNull(setup.verify(" " + code.replace("-", "").toLowerCase() + " "));
        assertNotNull(setup.verify(code.replace("-", " ")));
    }

    @Test
    public void wrongCodesAreRejectedAndRotateTheCode() throws Exception {
        setup.init();
        String original = codeFromFile();
        assertNull(setup.verify(null));
        assertNull(setup.verify(""));
        for (int i = 0; i < 8; i++) assertNull(setup.verify("AAAA-AAAA-AAAA-AAAA"));
        assertNotEquals(original, codeFromFile(), "10 failures -> new code");
        assertNull(setup.verify(original), "old code is dead");
        assertNotNull(setup.verify(codeFromFile()));
    }

    @Test
    public void invalidClaimsDoNothing() throws Exception {
        setup.init();
        int id = users.loginOrCreate("sub-1", "first@test.example", "First", "").getId();
        assertFalse(setup.completeIfClaimed(null, id));
        assertFalse(setup.completeIfClaimed("", id));
        assertFalse(setup.completeIfClaimed("forged", id));
        assertFalse(setup.completeIfClaimed("x".repeat(600), id));
        assertFalse(users.isAdmin(id));
        assertTrue(setup.isRequired());
    }

    @Test
    public void existingAdminClosesSetupAndRemovesStaleFile() throws Exception {
        Files.writeString(dir.resolve(SetupService.CODE_FILE_NAME), "STALE");
        UserAccount admin = users.loginOrCreate("sub-a", "admin@test.example", "Admin", "");
        assertTrue(users.claimFirstAdmin(admin.getId()));

        setup.init();
        assertFalse(setup.isRequired());
        assertFalse(Files.exists(setup.codeFile()));
        assertNull(setup.verify("STALE"));
    }

    @Test
    public void claimFirstAdminOnlyWorksOnce() {
        int a = users.loginOrCreate("sub-a", "a@test.example", "A", "").getId();
        int b = users.loginOrCreate("sub-b", "b@test.example", "B", "").getId();
        assertTrue(users.claimFirstAdmin(a));
        assertFalse(users.claimFirstAdmin(b));
        assertFalse(users.claimFirstAdmin(999999));
        assertEquals(1, users.adminCount());
    }
}
