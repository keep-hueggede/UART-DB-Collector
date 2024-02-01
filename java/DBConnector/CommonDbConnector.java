package DBConnector;

import java.sql.Connection;

public abstract class CommonDbConnector implements IDatabase{

    protected String host;
    protected Integer port;
    protected String database;
    protected String user;
    protected String password;

    protected Connection con;

    /**
     * Constructor
     * @param _host
     * @param _port
     * @param _database
     * @param _user
     * @param _password
     */
    public CommonDbConnector(String _host,Integer _port, String _database, String _user, String _password){
        this.host = _host;
        this.port = _port;
        this.database = _database;
        this.user = _user;
        this.password = _password;
    }

    /**
     * Build connection string from Object properties
     * @return
     */
    protected abstract String buildConnectionString(Boolean toDatabase);



}
