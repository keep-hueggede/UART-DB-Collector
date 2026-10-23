package DBConnector;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public interface IDatabase {

    /**
     * Connect to database
     */
    void connect();

    /**
     * Disconnect from Database
     */
    void disconnect();

    /**
     * Query cmd on Database
     * @param cmd
     * @return
     */
     ResultSet query(String cmd);

    /**
     * Execute cmd on Database
     * @param cmd
     */
    void execute(String cmd);

    /**
     * Execute transaction on Database
     * @param cmd
     */
    void transaction(String cmd);

    /**
     * Check connection
     * @return
     */
    Boolean isConnected();

}
