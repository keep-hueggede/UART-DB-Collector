package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Entity
@Table(name = "Actuators")
public class Actuator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DeviceId", nullable = false)
    private Device device;

    @Column(name = "ActuatorType")
    @Enumerated(EnumType.STRING)
    private ActuatorType actuatorType;

    @Column(name = "Name")
    private String name;

    @Column(name = "State")
    private Double state;

    @Column(name = "TargetState")
    private Double targetState;

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "CreatedAt")
    private Date createdAt;

    public enum ActuatorType {
        RELAY, SERVO, MOTOR, VALVE, LIGHT, CUSTOM
    }

    public Actuator() {}

    public Actuator(Device device, ActuatorType actuatorType, String name) {
        this.device = device;
        this.actuatorType = actuatorType;
        this.name = name;
        this.state = 0.0;
    }

    public int getId() { return id; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public ActuatorType getActuatorType() { return actuatorType; }
    public void setActuatorType(ActuatorType actuatorType) { this.actuatorType = actuatorType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getState() { return state; }
    public void setState(Double state) { this.state = state; }
    public Double getTargetState() { return targetState; }
    public void setTargetState(Double targetState) { this.targetState = targetState; }
    public Date getCreatedAt() { return createdAt; }
}
