package SSHTunnel;

import java.net.InetAddress;

public interface ISSHTunnel {
    /**
     * Connect to ssh server - optional via tunnel
     * @param userName
     * @param password
     * @param portForward
     */
    public void connect(String userName, String password, Boolean portForward);

    /**
     * Disconnect ssh session
     */
    public void disconnect();
}
