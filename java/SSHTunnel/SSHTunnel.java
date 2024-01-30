package SSHTunnel;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

public class SSHTunnel implements ISSHTunnel{

    private InetAddress ip;
    private int sshPort;
    private int remotePort;
    private int localPort;

    private String pathToKnownHosts;

    private Session session;


    /**
     * Constructor
     * @param _ip
     * @param _sshPort
     * @param _pathToKnownHosts
     * @param _remotePort
     * @param _localPort
     */
    public SSHTunnel(InetAddress _ip, int _sshPort, String _pathToKnownHosts, int _remotePort, int _localPort){
        this.ip = _ip;
        this.sshPort = _sshPort;
        this.pathToKnownHosts = _pathToKnownHosts;
        this.remotePort = _remotePort;
        this.localPort = _localPort;
    }

    /**
     * Constructor
     * @param _ip
     * @param _sshPort
     * @param _pathToKnownHosts
     */
    public SSHTunnel(InetAddress _ip, int _sshPort, String _pathToKnownHosts){
        this.ip = _ip;
        this.sshPort = _sshPort;
        this.pathToKnownHosts = _pathToKnownHosts;
    }
    @Override
    public void connect(String userName, String password, Boolean portForward) {
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(this.pathToKnownHosts);
            this.session = jsch.getSession(userName, this.ip.getHostAddress(), this.sshPort);
            this.session.setPassword(password);
            this.session.connect();
            if(portForward) this.session.setPortForwardingL(this.localPort, this.ip.getHostAddress()  , this.remotePort);

        }catch (Exception ex){
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        try{
            this.session.disconnect();
        }catch (Exception ex){
            this.session = null;
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    public Boolean isConnected(){
        return  this.session.isConnected();
    }
}
