package Model;

import jakarta.persistence.*;

import java.util.Date;

/** A collector (Pi + Thread receiver) that writes into the database; shown in the network map. */
@Entity
@Table(name = "Collectors")
public class Collector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "Name", unique = true, nullable = false, length = 64)
    private String name;

    @Column(name = "Version", length = 32)
    private String version;

    // sensor firmware the receiver holds and offers (null = none yet)
    @Column(name = "SensorFirmware", length = 32)
    private String sensorFirmware;

    @Column(name = "UartPort", length = 128)
    private String uartPort;

    @Column(name = "StartedAt")
    private Date startedAt;

    @Column(name = "LastSeen")
    private Date lastSeen;

    public Collector() {}

    public Collector(String name) {
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSensorFirmware() { return sensorFirmware; }
    public void setSensorFirmware(String sensorFirmware) { this.sensorFirmware = sensorFirmware; }
    public String getUartPort() { return uartPort; }
    public void setUartPort(String uartPort) { this.uartPort = uartPort; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getLastSeen() { return lastSeen; }
    public void setLastSeen(Date lastSeen) { this.lastSeen = lastSeen; }
}
