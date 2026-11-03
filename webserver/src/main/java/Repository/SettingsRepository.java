package Repository;

import Model.Setting;
import ORMUtil.ORMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Key/value application settings edited in the web app. */
public class SettingsRepository {

    private final ORMapper mapper;

    public SettingsRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, String> all() {
        return mapper.readInSession(session -> {
            Map<String, String> out = new HashMap<>();
            List<Setting> rows = session.createQuery("FROM Setting", Setting.class).getResultList();
            for (Setting s : rows) out.put(s.getKey(), s.getValue());
            return out;
        });
    }

    /** Stores the given values in one transaction; a null value removes the key. */
    public void putAll(Map<String, String> values) {
        mapper.writeInSession(session -> {
            for (Map.Entry<String, String> e : values.entrySet()) {
                Setting existing = session.get(Setting.class, e.getKey());
                if (e.getValue() == null) {
                    if (existing != null) session.remove(existing);
                } else if (existing == null) {
                    session.persist(new Setting(e.getKey(), e.getValue()));
                } else {
                    existing.setValue(e.getValue());
                }
            }
            return null;
        });
    }
}
