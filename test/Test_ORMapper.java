import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Test_ORMapper {

    private ORMapper orMapper;

    public Test_ORMapper() {
        try {
            this.orMapper = new ORMapper("localhost", 3306, "test",
                    "sa", "", "h2",
                    Arrays.asList(Hibernate_UnitTest.class));
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Test
    public void testConnect() {
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());
        this.orMapper.disconnect();
    }

    @Test
    public void testDisconnect() {
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());
        this.orMapper.disconnect();
        assertEquals(false, this.orMapper.isConnected());
    }

    @Test
    public void testPersistGet() {
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());

        this.orMapper.executeHQL("DELETE FROM Hibernate_UnitTest");

        for (int i = 1; i <= 100; i++) {
            Hibernate_UnitTest item = new Hibernate_UnitTest("Counter", i * i);
            this.orMapper.persist(item);
        }

        List<Hibernate_UnitTest> res = this.orMapper.getAll(Hibernate_UnitTest.class);
        assertEquals(100, res.size());

        for (int i = 1; i <= 100; i++) {
            Integer sum = i * i;
            assertEquals(sum, res.get(i - 1).getVal());
        }

        this.orMapper.disconnect();
    }
}
