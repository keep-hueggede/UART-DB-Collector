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
import java.util.function.Function;

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
            String dbFile = (this.database == null || this.database.isEmpty()) ? "wetter" : this.database;
            this.urlPrefix = "jdbc:h2:file:./data/" + dbFile + ";MODE=MySQL;AUTO_SERVER=TRUE";
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
        inTransaction(() -> this.session.persist(ent));
    }

    public void merge(Object ent) {
        inTransaction(() -> this.session.merge(ent));
    }

    // Roll back on failure, otherwise the long-lived session stays unusable.
    private void inTransaction(Runnable work) {
        Transaction tx = this.session.beginTransaction();
        try {
            work.run();
            tx.commit();
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            this.session.clear();
            throw ex;
        }
    }

    /**
     * Detach all cached entities. Call after each processed batch so the
     * first-level cache does not grow with every stored measurement.
     */
    public void clear() {
        this.session.clear();
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

    public <T> T findByHardwareId(Class<T> type, String hardwareId) {
        return this.session.createQuery("FROM " + type.getSimpleName() + " WHERE hardwareId = :hw", type)
                .setParameter("hw", hardwareId)
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

    public SessionFactory getSessionFactory() { return this.sessionFactory; }

    /**
     * Run a read operation in a fresh, short-lived session. The collector
     * writes through the long-lived session, which must not be shared with
     * other threads (e.g. the web server's HTTP request threads). The session
     * is closed (and its first-level cache freed) after the call.
     */
    public <T> T readInSession(Function<Session, T> work) {
        if (this.sessionFactory == null) {
            throw new IllegalStateException("ORMapper is not connected");
        }
        try (Session s = this.sessionFactory.openSession()) {
            return work.apply(s);
        }
    }

    /** Like readInSession, but inside a transaction that is committed or rolled back. */
    public <T> T writeInSession(Function<Session, T> work) {
        if (this.sessionFactory == null) {
            throw new IllegalStateException("ORMapper is not connected");
        }
        try (Session s = this.sessionFactory.openSession()) {
            Transaction tx = s.beginTransaction();
            try {
                T result = work.apply(s);
                tx.commit();
                return result;
            } catch (RuntimeException ex) {
                if (tx.isActive()) tx.rollback();
                throw ex;
            }
        }
    }

    @Override
    protected String buildConnectionString(Boolean toDatabase) {
        return this.urlPrefix;
    }

    public Boolean isConnected() {
        return this.session != null && this.session.isConnected();
    }
}
