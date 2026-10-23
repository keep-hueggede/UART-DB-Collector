package Listener;

public interface IListenerInterface {
    /**
     * Open Connection
     */
    void open();

    /**
     * Close Connection
     */
    void close();

    /**
     * Send Data to connceted device
     * @param input
     */
    void Send(String input);

    /**
     * Start listener
     * Event based async data receiver
     */
    void Listen();

    /**
     * Check connection
     * @return
     */
    Boolean isConnected();
}
