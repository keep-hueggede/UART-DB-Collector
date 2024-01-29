import java.net.InetAddress;

public interface ISSHTunnel {
    public void connect(InetAddress ip, int remotePort, int localPort);
    public void disconnect();
}
