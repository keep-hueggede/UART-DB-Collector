package Controller;

import Listener.IObserverListener;
import ORMUtil.ORMapper;
import UARTCom.UARTCom;

import java.io.FileInputStream;
import java.util.*;

public class Controller implements IObserverListener {

    class comStruct {
        private Integer datapointID;
        private String id;
        private String key;
        private String sensorType;
        private Double dValue;
        private  String sValue;
        Boolean type;

        public comStruct(Integer _datapointID, String _id, String _key, String _sensorType, Double _dValue, String _sValue, Boolean _type) {
            this.datapointID = _datapointID;
            this.id = _id;
            this.key =_key;
            this.sensorType = _sensorType;
            this.dValue = _dValue;
            this.sValue = _sValue;
            this.type = _type;
        }
    }
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



        }catch (Exception ex) {
                System.err.println(ex);
                ex.printStackTrace();
            }
    }

    protected comStruct mapUARTData(String data){
        return null;//new comStruct();
    }

    @Override
    public void onSignaled(String answer) {
        if(Objects.isNull(this.dp)) this.dp = new DataPoint();

    }
}
