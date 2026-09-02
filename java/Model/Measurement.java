package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Entity
@Table(name = "Measurements")
public class Measurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SensorId", nullable = false)
    private Sensor sensor;

    @Column(name = "MeasurementValue")
    private Double value;

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "Timestamp")
    private Date timestamp;

    public Measurement() {}

    public Measurement(Sensor sensor, Double value) {
        this.sensor = sensor;
        this.value = value;
    }

    public int getId() { return id; }
    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public Date getTimestamp() { return timestamp; }
}
