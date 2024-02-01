package Controller;

import Listener.IObserverListener;
import ORMUtil.ORMapper;
import UARTCom.UARTCom;

import java.io.FileInputStream;
import java.util.*;

public class Controller implements IObserverListener {


    private UARTCom com;
    private ORMapper mapper;
    private DataPoint dp;

    Properties prop;
    String configFile = "./java/Controller/app.config";

    public Controller(){
        try {
            this.prop = new Properties();
            this.prop.load(new FileInputStream(this.configFile));

            this.com = new UARTCom(prop.getProperty("UART.PORTNAME"));
            this.mapper = new ORMapper(prop.getProperty("MYSQL.HOST"), Integer.parseInt(prop.getProperty("MYSQL.PORT")), prop.getProperty("MYSQL.DATABASE"), prop.getProperty("MYSQL.USER"), prop.getProperty("MYSQL.PASSWORD"), List.of(DataPoint.class));

            //register subscriber
            this.com.addListener(this);


        }catch (Exception ex) {
                System.err.println(ex);
                ex.printStackTrace();
            }
    }

    protected ComStruct mapUARTData(String data){
        System.out.println(data);
        return null;//new comStruct();
    }

    @Override
    public void onSignaled(String answer) {
        if(Objects.isNull(this.dp)) this.dp = new DataPoint();
        mapUARTData(answer);

    }
}
