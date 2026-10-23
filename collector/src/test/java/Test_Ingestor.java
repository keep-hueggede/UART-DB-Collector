import Controller.Ingestor;
import Controller.Spool;
import Model.Measurement;
import ORMUtil.DatabaseSetup;
import ORMUtil.ORMapper;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Buffering while the database is unreachable and replay with original timestamps. */
public class Test_Ingestor {

    private static final String LINE_A = "{\"src\":\"1051dbfffe60b875\",\"temp\":20.5,\"rain\":0.45}";
    private static final String LINE_B = "{\"src\":\"1051dbfffe60b875\",\"temp\":21.0,\"rain\":0.0}";
    private static final long T_A = 1_700_000_000_000L;
    private static final long T_B = T_A + 300_000;

    private ORMapper mapper;
    private Spool spool;
    private Ingestor ingestor;
    private final AtomicBoolean serverUp = new AtomicBoolean(true);
    private Path tmp;

    @BeforeEach
    public void setUp() throws Exception {
        mapper = new ORMapper("localhost", 3306, "ingesttest", "sa", "", "h2", DatabaseSetup.ENTITIES);
        mapper.connect();
        mapper.executeHQL("DELETE FROM Measurement");
        mapper.executeHQL("DELETE FROM Sensor");
        mapper.executeHQL("DELETE FROM Device");
        tmp = Files.createTempDirectory("spool");
        spool = new Spool(tmp.resolve("pending.log"));
        ingestor = new Ingestor(mapper, spool, this::reconnect, true);
    }

    @AfterEach
    public void tearDown() {
        mapper.disconnect();
    }

    private boolean reconnect() {
        if (!serverUp.get()) return false;
        mapper.reconnect();
        return true;
    }

    private List<Measurement> measurements() {
        return mapper.readInSession(s -> s.createQuery("FROM Measurement m ORDER BY m.timestamp, m.id",
                Measurement.class).getResultList());
    }

    @Test
    public void storesWithReceiveTime() {
        ingestor.ingest(LINE_A, T_A);
        List<Measurement> ms = measurements();
        assertEquals(2, ms.size());
        assertEquals(T_A, ms.get(0).getTimestamp().getTime());
        assertTrue(spool.isEmpty());
    }

    @Test
    public void buffersWhileDatabaseIsDownAndReplaysInOrder() {
        serverUp.set(false);
        mapper.disconnect();

        ingestor.ingest(LINE_A, T_A);
        ingestor.ingest(LINE_B, T_B);
        assertFalse(ingestor.isHealthy());
        assertEquals(2, spool.size());

        ingestor.drain();               // still down: nothing happens
        assertEquals(2, spool.size());

        serverUp.set(true);
        ingestor.drain();
        assertTrue(ingestor.isHealthy());
        assertTrue(spool.isEmpty());

        List<Measurement> ms = measurements();
        assertEquals(4, ms.size());
        assertEquals(T_A, ms.get(0).getTimestamp().getTime());
        assertEquals(T_B, ms.get(3).getTimestamp().getTime());
    }

    @Test
    public void newDataWaitsBehindBacklog() {
        serverUp.set(false);
        mapper.disconnect();
        ingestor.ingest(LINE_A, T_A);

        // Database is back, but the backlog has not been replayed yet
        serverUp.set(true);
        mapper.reconnect();
        ingestor.ingest(LINE_B, T_B);
        assertEquals(2, spool.size());
        assertTrue(measurements().isEmpty());

        ingestor.drain();
        assertEquals(4, measurements().size());
    }

    @Test
    public void bufferSurvivesRestart() throws Exception {
        serverUp.set(false);
        mapper.disconnect();
        ingestor.ingest(LINE_A, T_A);

        Spool reopened = new Spool(tmp.resolve("pending.log"));
        assertEquals(1, reopened.size());
        assertEquals(T_A, reopened.readAll().get(0).receivedAt());
        assertEquals(LINE_A, reopened.readAll().get(0).line());
    }

    @Test
    public void badRecordIsDroppedWhileDatabaseIsUp() {
        // Hardware id longer than the column: fails although the database is reachable
        String bad = "{\"src\":\"" + "x".repeat(300) + "\",\"temp\":1.0}";
        ingestor.ingest(bad, T_A);
        assertTrue(ingestor.isHealthy());
        assertTrue(spool.isEmpty());

        ingestor.ingest(LINE_A, T_B);
        assertEquals(2, measurements().size());
    }

    @Test
    public void linesWithoutValuesAreIgnored() {
        ingestor.ingest("{\"sys\":\"ESP-ThreadReceiver boot\"}", T_A);
        ingestor.ingest("not json", T_A);
        ingestor.ingest("{\"src\":\"abc\",\"tips\":3}", T_A);
        assertTrue(measurements().isEmpty());
        assertTrue(spool.isEmpty());
    }

    @Test
    public void rainOnlyMessageIsStored() {
        // Deep-sleep node without BME280 sends only wind/rain
        ingestor.ingest("{\"src\":\"1051dbfffe60b875\",\"wind\":0.0,\"rain\":0.9,\"tips\":2}", T_A);
        assertEquals(2, measurements().size());
    }
}
