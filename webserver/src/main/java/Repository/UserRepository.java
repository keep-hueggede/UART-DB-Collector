package Repository;

import Model.UserAccount;
import ORMUtil.ORMapper;

/** User accounts (Google login). */
public class UserRepository {

    private final ORMapper mapper;

    public UserRepository(ORMapper mapper) {
        this.mapper = mapper;
    }

    /** Creates the account on first login / refreshes profile data on later ones. */
    public UserAccount loginOrCreate(String sub, String email, String name, String pictureUrl) {
        return mapper.writeInSession(session -> {
            UserAccount user = session.createQuery(
                            "FROM UserAccount u WHERE u.googleSub = :sub", UserAccount.class)
                    .setParameter("sub", sub)
                    .uniqueResult();
            if (user == null) {
                user = new UserAccount(sub, email, name, pictureUrl);
                session.persist(user);
            } else {
                user.setEmail(email);
                user.setName(name);
                user.setPictureUrl(pictureUrl);
            }
            return user;
        });
    }
}
