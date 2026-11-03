package Model;

import jakarta.persistence.*;

/** Application setting edited in the web app (key/value), e.g. the Google OAuth client. */
@Entity
@Table(name = "Settings")
public class Setting {

    @Id
    @Column(name = "SettingKey", length = 100)
    private String key;

    @Column(name = "SettingValue", length = 2000)
    private String value;

    public Setting() {}

    public Setting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
