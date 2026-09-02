package ORMUtil;

import DBConnector.CommonDbConnector;
import jakarta.persistence.Entity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.service.ServiceRegistry;

import java.util.List;
import java.util.Properties;

public class ORMapper extends CommonDbConnector {

    private Session session;
    private SessionFactory sessionFactory;
    private Configuration conf;
    private String driverClass;
    private String urlPrefix;

    public ORMapper(String _host, Integer _port, String _database, String _user, String _password,
                    String _driver, List<Class> _annotations) {
        super(_host, _port, _database, _user, _password);

        this.conf = new Configuration();

        if (_driver != null && _driver.equalsIgnoreCase("h2")) {
            this.driverClass = "org.h2.Driver";
            this.urlPrefix = "jdbc:h2:file:./data/wetter;MODE=MySQL;AUTO_SERVER=TRUE";
        } else {
            this.driverClass = "com.mysql.cj.jdbc.Driver";
            this.urlPrefix = "jdbc:mysql://" + this.host + ":" + this.port
                    + (!this.database.isEmpty() ? "/" + this.database : "");
        }

        Properties settings = new Properties();
        settings.put(Environment.DRIVER, this.driverClass);
        settings.put(Environment.URL, this.urlPrefix);
        settings.put(Environment.USER, this.user);
        settings.put(Environment.PASS, this.password);
        settings.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        settings.put(Environment.HBM2DDL_AUTO, "update");
        settings.put(Environment.SHOW_SQL, false);
        settings.put(Environment.LOG_JDBC_WARNINGS, false);

        conf.setProperties(settings);

        for (Class a : _annotations) {
            this.conf.addAnnotatedClass(a);
        }
    }

    public void connect() {
        ServiceRegistry reg = new StandardServiceRegistryBuilder()
                .applySettings(this.conf.getProperties())
                .build();
        this.sessionFactory = this.conf.buildSessionFactory(reg);
        this.session = sessionFactory.openSession();
    }

    public void disconnect() {
        if (this.session != null) this.session.close();
        if (this.sessionFactory != null) this.sessionFactory.close();
    }

    public void persist(Object ent) {
        Transaction transaction = this.session.beginTransaction();
        this.session.persist(ent);
        transaction.commit();
    }

    public void merge(Object ent) {
        Transaction transaction = this.session.beginTransaction();
        this.session.merge(ent);
        transaction.commit();
    }

    public <T> T get(Class<T> type, int id) {
        return this.session.get(type, id);
    }

    public <T> List<T> getAll(Class<T> type) {
        return this.session.createQuery("FROM " + type.getSimpleName(), type).list();
    }

    public <T> T findByName(Class<T> type, String name) {
        return this.session.createQuery("FROM " + type.getSimpleName() + " WHERE name = :name", type)
                .setParameter("name", name)
                .uniqueResult();
    }

    public <T> T findByNameAndDevice(Class<T> type, String name, Object device) {
        return this.session.createQuery(
                "FROM " + type.getSimpleName() + " WHERE name = :name AND device = :device", type)
                .setParameter("name", name)
                .setParameter("device", device)
                .uniqueResult();
    }

    public void executeHQL(String hql, Object... params) {
        Transaction tx = this.session.beginTransaction();
        try {
            var query = this.session.createQuery(hql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    query.setParameter(i, params[i]);
                }
            }
            query.executeUpdate();
            tx.commit();
        } catch (Exception ex) {
            tx.rollback();
            throw ex;
        }
    }

    public Session getSession() { return this.session; }

    @Override
    protected String buildConnectionString(Boolean toDatabase) {
        return this.urlPrefix;
    }

    public Boolean isConnected() {
        return this.session != null && this.session.isConnected();
    }
}
