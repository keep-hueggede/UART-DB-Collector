package Model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

/**
 * Google-linked user account. Created on first Google login; there is no
 * password and no local registration - the Google subject id is the identity.
 */
@Entity
@Table(name = "Users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "GoogleSub", unique = true, nullable = false, length = 255)
    private String googleSub;

    @Column(name = "Email")
    private String email;

    @Column(name = "Name")
    private String name;

    @Column(name = "PictureUrl")
    private String pictureUrl;

    // NULL (accounts from before roles existed) counts as USER
    @Column(name = "Role")
    @Enumerated(EnumType.STRING)
    private Role role;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "LastLoginAt")
    private Date lastLoginAt;

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "CreatedAt")
    private Date createdAt;

    public enum Role { ADMIN, USER }

    public UserAccount() {}

    public UserAccount(String googleSub, String email, String name, String pictureUrl) {
        this.googleSub = googleSub;
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
    }

    public int getId() { return id; }
    public String getGoogleSub() { return googleSub; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPictureUrl() { return pictureUrl; }
    public Date getCreatedAt() { return createdAt; }

    public void setEmail(String email) { this.email = email; }
    public void setName(String name) { this.name = name; }
    public void setPictureUrl(String pictureUrl) { this.pictureUrl = pictureUrl; }
    public Role getRole() { return role == null ? Role.USER : role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isAdmin() { return getRole() == Role.ADMIN; }
    public Date getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Date lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
