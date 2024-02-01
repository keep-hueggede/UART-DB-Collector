package Controller;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Entity
@Table(name = "DataPoints")
public class DataPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id")
    private int id;
    @Column(name = "Temperature")
    protected Double temperature;
    @Column(name = "Pressure")
    protected Double pressure;
    @Column(name = "Altitude")
    protected Double altitude;
    @Column(name = "Humidity")
    protected Double humidity;
    @Column(name = "Voltage")
    protected Double voltage;

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "TimeStamp")
    protected Date timestamp;

    public DataPoint(Double _temperature, Double _pressure, Double _altitude, Double _humidity, Double _voltage){
        this.temperature = _temperature;
        this.pressure = _pressure;
        this.altitude = _altitude;
        this.humidity = _humidity;
        this.voltage = _voltage;
    }

    public DataPoint(){

    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getPressure() {
        return pressure;
    }

    public void setPressure(Double pressure) {
        this.pressure = pressure;
    }

    public Double getAltitude() {
        return altitude;
    }

    public void setAltitude(Double altitude) {
        this.altitude = altitude;
    }

    public Double getHumidity() {
        return humidity;
    }

    public void setHumidity(Double humidity) {
        this.humidity = humidity;
    }

    public Double getVoltage() {
        return voltage;
    }

    public void setVoltage(Double voltage) {
        this.voltage = voltage;
    }
}
