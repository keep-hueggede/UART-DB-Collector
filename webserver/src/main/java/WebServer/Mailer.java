package WebServer;

import java.util.List;

/** Sends plain-text mails; one message per recipient. */
public interface Mailer {

    boolean isConfigured();

    void send(List<String> to, String subject, String text) throws Exception;
}
