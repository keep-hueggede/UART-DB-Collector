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
    private Configuration conf;

    /**
     * Constructor
     *
     * @param _host
     * @param _port
     * @param _database
     * @param _user
     * @param _password
     */
    public ORMapper(String _host, Integer _port, String _database, String _user, String _password, List<Class> _annotations) {
        super(_host, _port, _database, _user, _password);

        this.conf = new Configuration();

        Properties settings = new Properties();
        settings.put(Environment.DRIVER, "com.mysql.cj.jdbc.Driver");
        settings.put(Environment.URL, this.buildConnectionString(!this.database.isEmpty()));
        settings.put(Environment.USER, this.user);
        settings.put(Environment.PASS, this.password);
        settings.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        settings.put(Environment.HBM2DDL_AUTO, "create-drop");

        conf.setProperties(settings);

        for (Class a : _annotations) {
            this.conf.addAnnotatedClass(a);
        }

    }

    public void connect(){
        ServiceRegistry reg = new StandardServiceRegistryBuilder()
                .applySettings(this.conf.getProperties())
                .build();

        SessionFactory fact = this.conf.buildSessionFactory(reg);

        this.session = fact.openSession();
    }

    public void disconnect(){
        this.session.close();
        this.session = null;
    }

    public void persist(Object ent){
        Transaction transaction = this.session.beginTransaction();
        this.session.persist(ent);
        transaction.commit();
    }

    public List<Object> get(String query, Class ent){
        return this.session.createQuery(query,ent).list();
    }

    @Override
    protected String buildConnectionString(Boolean toDatabase) {
        return "jdbc:mysql://" + this.host + ":" + this.port + (toDatabase ? "/" + this.database : "");
    }

    public Boolean isConnected(){
        return this.session.isConnected();
    }
}
