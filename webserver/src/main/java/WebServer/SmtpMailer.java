package WebServer;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.List;
import java.util.Properties;

/** SMTP mailer; reconfigured at runtime from the admin settings. */
public class SmtpMailer implements Mailer {

    public enum Security { STARTTLS, SSL, NONE }

    public record Config(String host, int port, Security security, String user, String password, String from) {}

    private static final String TIMEOUT_MS = "15000";

    private volatile Config config;

    public void configure(Config config) {
        this.config = config;
    }

    @Override
    public boolean isConfigured() {
        Config c = config;
        return c != null && !c.host().isEmpty() && !c.from().isEmpty();
    }

    @Override
    public void send(List<String> to, String subject, String text) throws MessagingException {
        Config c = config;
        if (!isConfigured()) throw new IllegalStateException("mail is not configured");

        Properties p = new Properties();
        p.put("mail.smtp.host", c.host());
        p.put("mail.smtp.port", String.valueOf(c.port()));
        p.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        p.put("mail.smtp.timeout", TIMEOUT_MS);
        p.put("mail.smtp.writetimeout", TIMEOUT_MS);
        boolean auth = !c.user().isEmpty();
        p.put("mail.smtp.auth", String.valueOf(auth));
        if (c.security() == Security.STARTTLS) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        } else if (c.security() == Security.SSL) {
            p.put("mail.smtp.ssl.enable", "true");
        }
        p.put("mail.smtp.ssl.checkserveridentity", "true");

        Session session = Session.getInstance(p);
        for (String recipient : to) {
            MimeMessage m = new MimeMessage(session);
            m.setFrom(new InternetAddress(c.from(), true));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
            m.setSubject(subject, "UTF-8");
            m.setText(text, "UTF-8");
            m.setSentDate(new Date());
            if (auth) Transport.send(m, c.user(), c.password());
            else Transport.send(m);
        }
    }
}
