package UARTCom;

import Listener.IListenerInterface;
import Listener.Observer;
import com.fazecast.jSerialComm.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class UARTCom extends Observer implements IListenerInterface {

    protected String portDescriptor;
    protected SerialPort com;

    public UARTCom(String _portDescriptor) {
        this.portDescriptor = _portDescriptor;
    }

    @Override
    public void open() {
        this.com = SerialPort.getCommPort(this.portDescriptor);
        this.com.openPort();
    }

    @Override
    public void close() {
        this.com.closePort();
    }

    @Override
    public void Send(String input) {
        byte[] buffer = input.getBytes();
        this.com.writeBytes(buffer, buffer.length);
    }

    @Override
    public void Listen() {
        // Thread.ofPlatform().start(() -> {
        Executors.newSingleThreadExecutor().submit(()->{
            this.com.addDataListener(new SerialPortMessageListener() {
                @Override
                public byte[] getMessageDelimiter() {
                    return new byte[]{(byte) 0x0B, (byte)0x65};
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
                    fireSignal(new String(res, StandardCharsets.UTF_8));
                }
            });
        });
    }

    @Override
    public Boolean isConnected() {
        return this.com.isOpen();
    }
}
