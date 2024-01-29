package SSHTunnel;

import java.net.InetAddress;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

public class SSHTunnel implements ISSHTunnel{

    private InetAddress ip;
    private int sshPort;
    private int remotePort;
    private int localPort;

    private String userName;
    private String password;

    private Session session;


    public SSHTunnel(InetAddress _ip, int _sshPort, int _remotePort, int _localPort){
        this.ip = _ip;
        this.sshPort = _sshPort;
        this.remotePort = _remotePort;
        this.localPort = _localPort;
    }

    public SSHTunnel(InetAddress _ip, int _sshPort){
        this.ip = _ip;
        this.sshPort = _sshPort;
    }
    @Override
    public void connect(String userName, String password, Boolean portForward) {
        try {
            JSch jsch = new JSch();
            this.session = jsch.getSession(userName, "127.0.0.1", this.sshPort);
            this.session.setPassword(password);
            this.session.connect();
            if(portForward) this.session.setPortForwardingL(this.localPort, this.ip.toString()  , this.remotePort);

        }catch (Exception ex){
            //throw ex;
        }
    }

    @Override
    public void disconnect() {
        try{
            this.session.disconnect();
        }catch (Exception ex){
            this.session = null;
        }
    }
}
