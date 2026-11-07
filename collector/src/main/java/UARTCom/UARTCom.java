package UARTCom;

import Listener.IListenerInterface;
import Listener.Observer;
import com.fazecast.jSerialComm.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class UARTCom extends Observer implements IListenerInterface {

    protected String portDescriptor;
    protected int baudRate;
    protected SerialPort com;

    public UARTCom(String _portDescriptor, int _baudRate) {
        this.portDescriptor = _portDescriptor;
        this.baudRate = _baudRate;
    }

    public UARTCom(String _portDescriptor) {
        this(_portDescriptor, 115200);
    }

    @Override
    public void open() {
        this.com = SerialPort.getCommPort(this.portDescriptor);
        this.com.setBaudRate(this.baudRate);
        this.com.setNumDataBits(8);
        this.com.setNumStopBits(SerialPort.ONE_STOP_BIT);
        this.com.setParity(SerialPort.NO_PARITY);
        // writes block until sent (firmware upload); reading is event driven
        this.com.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 5000);
        if (!this.com.openPort()) {
            throw new IllegalStateException("Cannot open serial port " + this.portDescriptor
                    + " (missing, busy or no permission; is the serial console disabled?)");
        }
    }

    @Override
    public void close() {
        if (this.com != null && this.com.isOpen()) {
            this.com.closePort();
        }
    }

    @Override
    public synchronized void Send(String input) {
        if (this.com == null || !this.com.isOpen()) return;
        byte[] buffer = input.getBytes(StandardCharsets.UTF_8);
        int sent = 0;
        while (sent < buffer.length) {
            int n = this.com.writeBytes(buffer, buffer.length - sent, sent);
            if (n <= 0) throw new IllegalStateException("UART write failed");
            sent += n;
        }
    }

    @Override
    public void Listen() {
        Executors.newSingleThreadExecutor().submit(() -> {
            this.com.addDataListener(new SerialPortMessageListener() {
                @Override
                public byte[] getMessageDelimiter() {
                    return new byte[]{(byte) 0x0A}; // Newline
                }

                @Override
                public boolean delimiterIndicatesEndOfMessage() {
                    return true;
                }

                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                        // USB receiver unplugged or rebooted: the port is gone for good, systemd restarts us
                        System.err.println("UART: " + portDescriptor + " getrennt - Collector wird beendet");
                        // exit from another thread: the shutdown hook closes the port and waits for this one
                        new Thread(() -> System.exit(3), "uart-exit").start();
                        return;
                    }
                    byte[] res = event.getReceivedData();
                    String msg = new String(res, StandardCharsets.UTF_8).trim();
                    if (!msg.isEmpty()) {
                        fireSignal(msg);
                    }
                }
            });
        });
    }

    @Override
    public Boolean isConnected() {
        return this.com != null && this.com.isOpen();
    }
}
