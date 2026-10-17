# UART-DB-Collector

Empfaengt Sensordaten per UART (z.B. ESP32-C6 ThreadReceiver), speichert sie per
Hibernate in einer H2-Datenbank (optional MySQL) und stellt eine Web-App fuer
das Phone bereit.

## Build & Start

```bash
mvn package
java -jar target/UART-DB-Collector-1.0-SNAPSHOT.jar
```

Konfiguration in `./app.config` (Vorlage: `app.config.example`):

```
UART.PORTNAME=/dev/serial0
UART.BAUD=115200

DB.DRIVER=h2            # h2 (lokal, ./data/<DB.NAME>.mv.db) oder mysql
DB.NAME=wetter
DB.USER=sa

SERVER.ENABLED=true     # Web-Server ein/ausschalten
SERVER.HOST=0.0.0.0     # im LAN erreichbar
SERVER.PORT=8080
SERVER.WEBROOT=./web    # optional; das web/ Verzeichnis im jar ist der Fallback
```

Auf dem Pi als Dienst: siehe `deploy/uart-db-collector.service`.

## Web-App (PWA) am Phone

Der Web-Server lauft im gleichen Prozess wie der Collector und liest ueber den
bestehenden `ORMapper` (Hibernate) aus der Datenbank.

1. Phone und Pi im selben Netzwerk.
2. Am Phone oeffnen: `http://<pi-ip>:8080`
3. "Zum Home-Screen hinzufuegen" -> laeuft dann wie eine App (PWA, offline-faehig
   via Service Worker, Auto-Aktualisierung, Verlauf-Charts je Sensor).

Einstellungen (Zahnrad): Server-URL ueberschreiben (noetig nur, wenn die App
anderswo heraufgeladen wird) und Auto-Aktualisierungs-Intervall.

### REST-API

| Endpoint | Beschreibung |
|---|---|
| `GET /api/health` | Status + Device-Anzahl |
| `GET /api/devices` | Devices inkl. Sensor-Anzahl, letzter Messzeitpunkt |
| `GET /api/sensors` | Alle Sensoren mit Device, Einheit |
| `GET /api/sensors/{id}` | Ein Sensor |
| `GET /api/measurements/latest` | Letzter Messwert je Sensor |
| `GET /api/measurements?sensorId={id}&from={ms}&to={ms}&limit={n}` | Zeitreihe + Min/Max/Ø |
| `GET /api/measurements?sensorId={id}&window={ms}` | Zeitreihe der letzten `window` Millisekunden |

## Native App (spaeter)

`web/` ist bewusst ein build-freies, in sich geschlossenes Asset-Verzeichnis und
kann direkt als `webDir` fuer [Capacitor](https://capacitorjs.com) verwendet
werden:

```bash
npm init -y
npm install @capacitor/core @capacitor/cli @capacitor/android
npx cap init "UART DB Collector" de.keephueggede.uartdb --web-dir web
npx cap add android
npx cap sync && npx cap open android
```

In der App unter Einstellungen die Server-URL des Collectors setzen
(z.B. `http://192.168.1.10:8080`). Der API-Endpunkt antwortet mit CORS-Headern,
damit der WebView der nativen App die Daten laden kann.

## SSH-Tunnel

* connect to ssh host via: "ssh -o HostKeyAlgorithms=ssh-rsa -o FingerprintHash=md5 user@target.host" to obtain ssh key in knownhost file
