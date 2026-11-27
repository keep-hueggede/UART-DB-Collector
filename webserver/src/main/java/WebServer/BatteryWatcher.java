package WebServer;

import Repository.MeasurementRepository;
import Repository.MeasurementRepository.BatteryState;
import Repository.SettingsRepository;
import Repository.UserRepository;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Mails the users a device is assigned to once its battery flag (BATTERY_LOW)
 * turns low. The mail is sent again only after the battery reported ok for
 * a whole day, so a flag that flickers near the threshold does not spam.
 */
public class BatteryWatcher {

    static final String KEY_PREFIX = "battery.notified.";
    static final long CLEAR_AFTER_MS = 24L * 60 * 60 * 1000;
    private static final long PERIOD_MIN = 10;

    private final MeasurementRepository measurements;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final Supplier<Mailer> mailer;
    private ScheduledExecutorService scheduler;

    public BatteryWatcher(MeasurementRepository measurements, UserRepository users, SettingsRepository settings,
                          Supplier<Mailer> mailer) {
        this.measurements = measurements;
        this.users = users;
        this.settings = settings;
        this.mailer = mailer;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "battery-watcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkSafely, 1, PERIOD_MIN, TimeUnit.MINUTES);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void checkSafely() {
        try {
            check(System.currentTimeMillis());
        } catch (RuntimeException e) {
            System.err.println("Batterie-Pruefung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** One pass; returns the number of mails sent. */
    int check(long now) {
        Map<String, String> stored = settings.all();
        Mailer m = mailer.get();
        int sent = 0;
        for (BatteryState b : measurements.batteryStates(new Date(now - CLEAR_AFTER_MS))) {
            String key = KEY_PREFIX + b.sensorId();
            boolean notified = stored.containsKey(key);
            if (b.low() && !notified && m.isConfigured()) {
                List<String> to = users.emailsForDevice(b.deviceId());
                if (to.isEmpty()) continue;
                try {
                    m.send(to, "Batterie schwach: " + oneLine(b.deviceName()), text(b));
                } catch (Exception e) {
                    System.err.println("Batterie-Mail fehlgeschlagen: " + e.getMessage());
                    continue;
                }
                settings.putAll(Map.of(key, String.valueOf(now)));
                System.out.println("Benachrichtigung: Batterie schwach an " + b.deviceName() + ", " + to.size()
                        + " Benutzer informiert");
                sent++;
            } else if (notified && !b.lowSince()) {
                settings.putAll(Collections.singletonMap(key, null));
            }
        }
        return sent;
    }

    private static String text(BatteryState b) {
        return "Der Funk-Windsender am Sensor \"" + oneLine(b.deviceName()) + "\" meldet eine schwache Batterie.\n\n"
                + "Bitte die 3 AA-Zellen tauschen. Nach dem Wechsel sendet der Windsender mit einer neuen\n"
                + "Kennung; der Sensor koppelt ihn nach etwa 30 Minuten Funkstille automatisch neu\n"
                + "(sofort: RESET am Sensor druecken).\n";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
