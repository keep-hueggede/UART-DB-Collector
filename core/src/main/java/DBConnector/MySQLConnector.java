package DBConnector;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class MySQLConnector extends CommonDbConnector  implements IDatabase {
    public MySQLConnector(String _host, Integer _port, String _database, String _user, String _password) {
        super(_host, _port, _database, _user, _password);
    }

    @Override
    protected String buildConnectionString(Boolean toDatabase) {
        return "jdbc:mysql://" + this.host + ":" + this.port + (toDatabase ? "/" + this.database : "");
    }

    @Override
    public void connect() {
        try {
            String conString = this.buildConnectionString(this.database.isEmpty() ? false : true);
            this.con = DriverManager.getConnection(conString, this.user, this.password);
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        try {
            this.con.close();
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public ResultSet query(String cmd) {
        try {
            if (cmd.isEmpty()) throw new Exception("SQL query empty");
            return con.createStatement().executeQuery(cmd);
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
            return null;
        }
    }

    @Override
    public void execute(String cmd) {
        try {
            if (cmd.isEmpty()) throw new Exception("SQL query empty");
            con.createStatement().execute(cmd);
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void transaction(String cmd) {
        try {
            if (cmd.isEmpty()) throw new Exception("SQL query empty");
            con.setAutoCommit(false);
            con.createStatement().execute(cmd);
            con.commit();
        } catch (Exception ex) {
            try {
                con.rollback();
            } catch (Exception ex1) {
                System.err.println(ex1);
                ex1.printStackTrace();
            }
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public Boolean isConnected() {
        try {
            return !this.con.isClosed();
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
            return false;
        }
    }
}
