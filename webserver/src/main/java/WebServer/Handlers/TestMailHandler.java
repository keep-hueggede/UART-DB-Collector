package WebServer.Handlers;

import WebServer.AppSettings;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** POST /api/admin/settings/test-mail (admin) - sends a test mail to the calling admin. */
public class TestMailHandler implements Handler {

    private final AppSettings settings;

    public TestMailHandler(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        if (!HandlerSupport.requireJson(call)) return;
        if (!settings.mailer().isConfigured()) {
            call.error(409, "mail is not configured");
            return;
        }
        String to = call.user().email;
        if (to == null || to.isBlank()) {
            call.error(409, "your account has no mail address");
            return;
        }
        try {
            settings.mailer().send(List.of(to), "Testmail vom Wetter-Server",
                    "Der E-Mail-Versand funktioniert. Neue Benutzer werden ab jetzt an alle Administratoren gemeldet.\n");
            call.json(200, Map.of("sent", true, "to", to));
        } catch (Exception e) {
            System.err.println("Testmail fehlgeschlagen: " + e.getMessage());
            // the SMTP reply helps the admin (wrong password, port blocked); no stack trace
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("\\s+", " ");
            call.error(502, "sending failed: " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
        }
    }
}
