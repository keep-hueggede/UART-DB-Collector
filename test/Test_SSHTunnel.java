import Listener.IObserverListener;
import SSHTunnel.SSHTunnel;
import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Console;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Test_SSHTunnel implements IObserverListener {

    Properties prop;
    String configFile = "./test/app.config";
    Boolean sendSuccess = false;
    private final CountDownLatch waiter = new CountDownLatch(1);

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
            this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"), prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"));
            this.sshTunnel.open();

            assertEquals(true, this.sshTunnel.isConnected());
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }
    @Test
    public void TestConnectionWithPortForwarding(){
        try {
            this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"), Integer.parseInt(prop.getProperty("SSH.LOCALPORT")), Integer.parseInt(prop.getProperty("SSH.REMOTEPORT")),prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"));
            this.sshTunnel.open();

            assertEquals(true, this.sshTunnel.isConnected());
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Test
    public void TestSend(){
        try {
            this.sshTunnel = new SSHTunnel(InetAddress.getByName(prop.getProperty("SERVER.IP")), Integer.parseInt(prop.getProperty("SERVER.PORT")), prop.getProperty("SSH.KNOWNHOSTS"), prop.getProperty("SSH.USER"), prop.getProperty("SSH.PW"));
            this.sshTunnel.open();
            this.sshTunnel.addListener(this);
            this.sshTunnel.Listen();
            this.sshTunnel.Send("apt-get update");


            waiter.await(2000, TimeUnit.MILLISECONDS);
            assertEquals(true, this.sendSuccess);

        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void onSignaled(String answer) {
        System.out.println(answer);
        this.sendSuccess = true;
    }
}
