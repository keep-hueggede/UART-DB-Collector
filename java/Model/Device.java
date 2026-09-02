package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "Devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "Name", unique = true, nullable = false)
    private String name;

    @Column(name = "DeviceType")
    @Enumerated(EnumType.STRING)
    private DeviceType deviceType;

    @Column(name = "Location")
    private String location;

    @Column(name = "Online")
    private boolean online;

    @Column(name = "LastSeen")
    private Date lastSeen;

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Sensor> sensors = new ArrayList<>();

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Actuator> actuators = new ArrayList<>();

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "CreatedAt")
    private Date createdAt;

    public enum DeviceType { SENSOR, ACTUATOR, GATEWAY }

    public Device() {}

    public Device(String name, DeviceType deviceType, String location) {
        this.name = name;
        this.deviceType = deviceType;
        this.location = location;
        this.online = false;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DeviceType getDeviceType() { return deviceType; }
    public void setDeviceType(DeviceType deviceType) { this.deviceType = deviceType; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public Date getLastSeen() { return lastSeen; }
    public void setLastSeen(Date lastSeen) { this.lastSeen = lastSeen; }
    public List<Sensor> getSensors() { return sensors; }
    public List<Actuator> getActuators() { return actuators; }
    public Date getCreatedAt() { return createdAt; }

    public void addSensor(Sensor sensor) {
        sensors.add(sensor);
        sensor.setDevice(this);
    }

    public void addActuator(Actuator actuator) {
        actuators.add(actuator);
        actuator.setDevice(this);
    }
}
