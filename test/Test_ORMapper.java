import DBConnector.MySQLConnector;
import ORMUtil.ORMapper;
import SSHTunnel.SSHTunnel;
import org.junit.Test;

import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class Test_ORMapper {
    Properties prop;
    String configFile = "./test/app.config";
    private ORMapper orMapper;

    public Test_ORMapper() {
        try {
            this.prop = new Properties();
            this.prop.load(new FileInputStream(this.configFile));

            this.orMapper = new ORMapper(prop.getProperty("MYSQL.HOST"),Integer.parseInt(prop.getProperty("MYSQL.PORT")), prop.getProperty("MYSQL.DATABASE"), prop.getProperty("MYSQL.USER"), prop.getProperty("MYSQL.PASSWORD"), Arrays.asList(Hibernate_UnitTest.class));
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Test
    public void testConnect(){
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());
    }

    @Test
    public void testDisconnect(){
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());
        this.orMapper.disconnect();
        assertEquals(false, this.orMapper.isConnected());
    }

    @Test
    public void testPersistGet(){
        this.orMapper.connect();
        assertEquals(true, this.orMapper.isConnected());

        for (int i =1; i<=100; i++){
            Hibernate_UnitTest item = new Hibernate_UnitTest("Counter", i*i);
            this.orMapper.persist(item);
        }

        List<Hibernate_UnitTest> res =  this.orMapper.get("from Hibernate_UnitTest", Hibernate_UnitTest.class).stream().map( e -> (Hibernate_UnitTest)e).toList();
        assertEquals(100, res.size());

        for (int i =1; i<=100; i++){
            Integer sum = i*i;
            assertEquals(sum, res.get(i-1).getVal());
        }

    }


}

