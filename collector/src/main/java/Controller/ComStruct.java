package Controller;

public final class ComStruct {
    private Integer datapointID;
    private String id;
    private String key;
    private String sensorType;
    private Double dValue;
    private  String sValue;
    Boolean type;

    public ComStruct(Integer _datapointID, String _id, String _key, String _sensorType, Double _dValue, String _sValue, Boolean _type) {
        this.datapointID = _datapointID;
        this.id = _id;
        this.key =_key;
        this.sensorType = _sensorType;
        this.dValue = _dValue;
        this.sValue = _sValue;
        this.type = _type;
    }
}
