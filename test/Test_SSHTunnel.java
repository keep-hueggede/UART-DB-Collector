import SSHTunnel.SSHTunnel;
import org.junit.Assert;
import org.junit.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Console;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.Properties;

public class Test_SSHTunnel {

    Properties prop;
    String configFile = "./test/app.config";

    SSHTunnel sshTunnel;
    public  Test_SSHTunnel(){
        try{
            this.prop = new Properties();
            this.prop.load(new FileInputStream(this.configFile));
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Test
    public void TestConnection(){
        try {
            this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"));
            this.sshTunnel.connect(prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"), false);

            assertEquals(true, this.sshTunnel.isConnected());
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }
    @Test
    public void TestConnectionWithPortForwarding(){
        try {
            this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"), Integer.parseInt(prop.getProperty("SSH.LOCALPORT")), Integer.parseInt(prop.getProperty("SSH.REMOTEPORT")));
            this.sshTunnel.connect(prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"), true);

            assertEquals(true, this.sshTunnel.isConnected());
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

}
