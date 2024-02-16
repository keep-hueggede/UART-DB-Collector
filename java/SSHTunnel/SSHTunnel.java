package SSHTunnel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.concurrent.Executors;

import Listener.IListenerInterface;
import Listener.Observer;
import com.jcraft.jsch.*;

public class SSHTunnel extends Observer implements IListenerInterface {

    private InetAddress ip;
    private int sshPort;
    private int remotePort;
    private int localPort;
    private String userName;
    private String password;
    private Boolean portForward = false;

    private String pathToKnownHosts;

    private Session session;
    private Channel channel;


    /**
     * Constructor
     *
     * @param _ip
     * @param _sshPort
     * @param _pathToKnownHosts
     * @param _remotePort
     * @param _localPort
     */
    public SSHTunnel(InetAddress _ip, int _sshPort, String _pathToKnownHosts, int _remotePort, int _localPort, String _userName, String _password) {
        this.ip = _ip;
        this.sshPort = _sshPort;
        this.pathToKnownHosts = _pathToKnownHosts;
        this.remotePort = _remotePort;
        this.localPort = _localPort;
        this.userName = _userName;
        this.password = _password;
        this.portForward = true;
    }

    /**
     * Constructor
     *
     * @param _ip
     * @param _sshPort
     * @param _pathToKnownHosts
     */
    public SSHTunnel(InetAddress _ip, int _sshPort, String _pathToKnownHosts, String _userName, String _password) {
        this.ip = _ip;
        this.sshPort = _sshPort;
        this.pathToKnownHosts = _pathToKnownHosts;
        this.userName = _userName;
        this.password = _password;
    }

    @Override
    public void open() {
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(this.pathToKnownHosts);
            this.session = jsch.getSession(userName, this.ip.getHostAddress(), this.sshPort);
            this.session.setPassword(password);
            this.session.connect();
            if (portForward) {
                this.session.setPortForwardingL(this.localPort, this.ip.getHostAddress(), this.remotePort);
            }
            this.channel = session.openChannel("exec");
            //channel.connect();


        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void close() {
        try {
            this.channel.disconnect();
            this.session.disconnect();
        } catch (Exception ex) {
            this.session = null;
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void Send(String input) {

        try {
            ((ChannelExec) channel).setCommand(input);
            this.channel.connect();
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }

    }

    @Override
    public void Listen() {
        Executors.newSingleThreadExecutor().submit(()->{
        try {
            StringBuilder outBuffer = new StringBuilder();
            InputStream input = this.channel.getInputStream();
            while (true) {
               int readByte = input.read();

               if (readByte > -1) {
                   outBuffer.append((char) readByte);
               }else {
                   this.fireSignal(outBuffer.toString());
                   outBuffer = new StringBuilder();
               }
            }


            //this.channel.disconnect();

        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
        });
    }

    @Override
    public Boolean isConnected() {
        return this.session.isConnected();
    }
}
