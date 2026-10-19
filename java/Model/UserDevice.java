package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

/**
 * Ownership relation: which user may see which field device (sensor/actuator).
 * The edge devices keep shovelling data for every reachable device into the
 * database; visibility is decided solely by this mapping.
 */
@Entity
@Table(name = "UserDevices",
        uniqueConstraints = @UniqueConstraint(columnNames = {"UserId", "DeviceId"}))
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "DeviceId", nullable = false)
    private Device device;

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "AddedAt")
    private Date addedAt;

    public UserDevice() {}

    public UserDevice(UserAccount user, Device device) {
        this.user = user;
        this.device = device;
    }

    public int getId() { return id; }
    public UserAccount getUser() { return user; }
    public Device getDevice() { return device; }
    public Date getAddedAt() { return addedAt; }
}
