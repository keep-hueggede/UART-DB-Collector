package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "Sensors")
public class Sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DeviceId", nullable = false)
    private Device device;

    @Column(name = "SensorType")
    @Enumerated(EnumType.STRING)
    private SensorType sensorType;

    @Column(name = "Name")
    private String name;

    @Column(name = "Unit")
    private String unit;

    @OneToMany(mappedBy = "sensor", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Measurement> measurements = new ArrayList<>();

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "CreatedAt")
    private Date createdAt;

    public enum SensorType {
        TEMPERATURE("C"),
        PRESSURE("hPa"),
        HUMIDITY("%"),
        WIND("m/s"),
        RAIN("mm"),
        ALTITUDE("m"),
        VOLTAGE("V"),
        CUSTOM("");

        private final String defaultUnit;
        SensorType(String defaultUnit) { this.defaultUnit = defaultUnit; }
        public String getDefaultUnit() { return defaultUnit; }
    }

    public Sensor() {}

    public Sensor(Device device, SensorType sensorType, String name) {
        this.device = device;
        this.sensorType = sensorType;
        this.name = name;
        this.unit = sensorType.getDefaultUnit();
    }

    public int getId() { return id; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public SensorType getSensorType() { return sensorType; }
    public void setSensorType(SensorType sensorType) { this.sensorType = sensorType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public List<Measurement> getMeasurements() { return measurements; }
    public Date getCreatedAt() { return createdAt; }

    public void addMeasurement(Measurement measurement) {
        measurements.add(measurement);
        measurement.setSensor(this);
    }
}
