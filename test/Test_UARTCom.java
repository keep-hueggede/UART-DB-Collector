import Listener.IObserverListener;
import SSHTunnel.SSHTunnel;
import UARTCom.UARTCom;
import org.junit.Test;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Test_UARTCom implements IObserverListener {

    Properties prop;
    String configFile = "./test/app.config";
    Boolean sendSuccess = false;
    private final CountDownLatch waiter = new CountDownLatch(1);

    UARTCom com;
    public  Test_UARTCom(){
        try{
            this.prop = new Properties();
            this.prop.load(new FileInputStream(this.configFile));

            this.com = new UARTCom(prop.getProperty("UART.PORTNAME"));
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }
    @Test
    public void TestConnection(){
        try {

            this.com.open();

            assertEquals(true, this.com.isConnected());
        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Test
    public void TestSend(){
        try {
            this.com = new UARTCom(prop.getProperty("UART.PORTNAME"));
            this.com.open();
            this.com.addListener(this);
            this.com.Listen();
            this.com.Send("");


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
