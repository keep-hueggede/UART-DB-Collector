package WebServer;

import Model.UserAccount;
import Repository.UserRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Mails every admin about events that need their attention (new accounts). */
public class AdminNotifier {

    private final UserRepository users;
    private final Supplier<Mailer> mailer;
    // off the request thread: a slow SMTP server must not delay the login
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "admin-notifier");
        t.setDaemon(true);
        return t;
    });

    public AdminNotifier(UserRepository users, Supplier<Mailer> mailer) {
        this.users = users;
        this.mailer = mailer;
    }

    /** A Google account signed in for the first time and waits for device assignment. */
    public void newAccount(UserAccount user, String baseUrl) {
        Mailer m = mailer.get();
        if (!m.isConfigured()) return;
        List<String> to = users.adminEmails();
        if (to.isEmpty()) return;

        String name = oneLine(user.getName());
        String email = oneLine(user.getEmail());
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm 'UTC'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String subject = "Neuer Benutzer: " + (name.isEmpty() ? email : name);
        String text = "Ein neues Konto hat sich angemeldet und wartet auf Freigabe.\n\n"
                + "Name:   " + name + "\n"
                + "E-Mail: " + email + "\n"
                + "Zeit:   " + fmt.format(new Date()) + "\n\n"
                + "Freigeben (Geraete zuweisen) unter Administration -> Benutzer:\n"
                + baseUrl + "/\n";
        executor.execute(() -> {
            try {
                m.send(to, subject, text);
                System.out.println("Benachrichtigung: neues Konto " + email + " an " + to.size() + " Admin(s) gemeldet");
            } catch (Exception e) {
                System.err.println("Benachrichtigung fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
