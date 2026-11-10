package Repository;

import Model.UserAccount;
import ORMUtil.ORMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User accounts (Google login) and their roles. */
public class UserRepository {

    public enum RoleResult { OK, NOT_FOUND, LAST_ADMIN }

    private static final String ADMIN_COUNT =
            "SELECT COUNT(u) FROM UserAccount u WHERE u.role = :admin";

    private final ORMapper mapper;

    public UserRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** Creates the account on first login (as USER) / refreshes profile data on later ones. */
    public UserAccount loginOrCreate(String sub, String email, String name, String pictureUrl) {
        return mapper.writeInSession(session -> {
            UserAccount user = session.createQuery(
                            "FROM UserAccount u WHERE u.googleSub = :sub", UserAccount.class)
                    .setParameter("sub", sub)
                    .uniqueResult();
            if (user == null) {
                user = new UserAccount(sub, email, name, pictureUrl);
                user.setRole(UserAccount.Role.USER);
                user.setNewAccount(true);
                session.persist(user);
            } else {
                user.setEmail(email);
                user.setName(name);
                user.setPictureUrl(pictureUrl);
            }
            user.setLastLoginAt(new Date());
            return user;
        });
    }

    public long adminCount() {
        return mapper.readInSession(session -> session.createQuery(ADMIN_COUNT, Long.class)
                .setParameter("admin", UserAccount.Role.ADMIN).getSingleResult());
    }

    /** Mail addresses of all admins (notification recipients). */
    public List<String> adminEmails() {
        return mapper.readInSession(session -> session.createQuery(
                        "SELECT u.email FROM UserAccount u WHERE u.role = :admin AND u.email IS NOT NULL AND u.email <> ''",
                        String.class)
                .setParameter("admin", UserAccount.Role.ADMIN).getResultList());
    }

    public boolean isAdmin(int userId) {
        return mapper.readInSession(session -> {
            UserAccount u = session.get(UserAccount.class, userId);
            return u != null && u.isAdmin();
        });
    }

    /** Makes the user admin only if there is no admin yet (first-run setup). */
    public boolean claimFirstAdmin(int userId) {
        return mapper.writeInSession(session -> {
            long admins = session.createQuery(ADMIN_COUNT, Long.class)
                    .setParameter("admin", UserAccount.Role.ADMIN).getSingleResult();
            UserAccount u = session.get(UserAccount.class, userId);
            if (admins > 0 || u == null) return false;
            u.setRole(UserAccount.Role.ADMIN);
            return true;
        });
    }

    /** All accounts with role and assigned device ids (admin page). */
    public List<Map<String, Object>> listAll() {
        return mapper.readInSession(session -> {
            List<UserAccount> users = session.createQuery(
                    "FROM UserAccount u ORDER BY u.name, u.email", UserAccount.class).getResultList();
            List<Object[]> links = session.createQuery(
                    "SELECT ud.user.id, ud.device.id FROM UserDevice ud", Object[].class).getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (UserAccount u : users) {
                List<Integer> deviceIds = new ArrayList<>();
                for (Object[] l : links) {
                    if (((Number) l[0]).intValue() == u.getId()) deviceIds.add(((Number) l[1]).intValue());
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("name", u.getName());
                m.put("email", u.getEmail());
                m.put("picture", u.getPictureUrl());
                m.put("role", u.getRole().name());
                m.put("lastLogin", Ownership.toMillis(u.getLastLoginAt()));
                m.put("createdAt", Ownership.toMillis(u.getCreatedAt()));
                m.put("deviceIds", deviceIds);
                out.add(m);
            }
            return out;
        });
    }

    /** Changes a role; the last admin cannot be demoted (no lock-out). */
    public RoleResult setRole(int userId, UserAccount.Role role) {
        return mapper.writeInSession(session -> {
            UserAccount u = session.get(UserAccount.class, userId);
            if (u == null) return RoleResult.NOT_FOUND;
            if (u.isAdmin() && role != UserAccount.Role.ADMIN) {
                long admins = session.createQuery(ADMIN_COUNT, Long.class)
                        .setParameter("admin", UserAccount.Role.ADMIN).getSingleResult();
                if (admins <= 1) return RoleResult.LAST_ADMIN;
            }
            u.setRole(role);
            return RoleResult.OK;
        });
    }
}
