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
        this.com.openPort();
    }

    @Override
    public void close() {
        if (this.com != null && this.com.isOpen()) {
            this.com.closePort();
        }
    }

    @Override
    public void Send(String input) {
        if (this.com == null || !this.com.isOpen()) return;
        byte[] buffer = input.getBytes();
        this.com.writeBytes(buffer, buffer.length);
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
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
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
