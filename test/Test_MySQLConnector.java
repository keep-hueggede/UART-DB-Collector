import DBConnector.MySQLConnector;
import SSHTunnel.SSHTunnel;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.sql.ResultSet;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class Test_MySQLConnector {
    Properties prop;
    String configFile = "./test/app.config";
    private MySQLConnector mysqlCon;
    private SSHTunnel sshTunnel;

    public Test_MySQLConnector() {
        try {
            this.prop = new Properties();
            this.prop.load(new FileInputStream(this.configFile));

            this.mysqlCon = new MySQLConnector(prop.getProperty("MYSQL.HOST"),Integer.parseInt(prop.getProperty("MYSQL.PORT")), "", prop.getProperty("MYSQL.USER"), prop.getProperty("MYSQL.PASSWORD") );
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

//    @Before
//    public void buildUpSSH(){
//        try{
//        this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"), Integer.parseInt(prop.getProperty("SSH.LOCALPORT")), Integer.parseInt(prop.getProperty("SSH.REMOTEPORT")),prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"));
//        this.sshTunnel.open();
//
//        } catch (Exception ex) {
//            System.err.println(ex);
//            ex.printStackTrace();
//        }
//    }
//
//    @After
//    public void closeSSH(){
//        try{
//            this.sshTunnel.close();
//
//        } catch (Exception ex) {
//            System.err.println(ex);
//            ex.printStackTrace();
//        }
//    }

    @Test
    public void TestConnect(){
        this.mysqlCon.connect();
        assertEquals(true, this.mysqlCon.isConnected());
    }

    @Test
    public void TestDisconnect(){
        this.mysqlCon.connect();
        assertEquals(true, this.mysqlCon.isConnected());
        this.mysqlCon.disconnect();
        assertEquals(false, this.mysqlCon.isConnected());
    }

    @Test
    public void TestQueryExecuteTransaction(){
        try{
        this.mysqlCon.connect();
        assertEquals(true, this.mysqlCon.isConnected());

        this.mysqlCon.execute("CREATE DATABASE " + prop.getProperty("MYSQL.DATABASE")+";");
        this.mysqlCon.execute("CREATE TABLE tblUnitTest (ID int NOT NULL AUTO_INCREMENT, Key varchar(50), Val int, PRIMARY KEY (ID));");

        for (int i =0; i<100; i++){
            this.mysqlCon.transaction("INSERT INTO tblUnitTest (Key, Val) VALUES ('Counter', "+ i * i +" );");
        }

        ResultSet res = this.mysqlCon.query("SELECT SUM(Val) AS KeySum FROM tblUnitTest;");

        assertEquals(1, res.findColumn("KeySum"));
        this.mysqlCon.disconnect();
        assertEquals(false, this.mysqlCon.isConnected());
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }


}
