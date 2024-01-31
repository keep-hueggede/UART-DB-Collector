package Listener;

public interface IListenerInterface {
    void open();
    void close();
    void Send(String input);
    void Listen();
    Boolean isConnected();
}
