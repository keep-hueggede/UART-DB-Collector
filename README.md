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
SERVER.SECURE_COOKIE=false  # true bei HTTPS hinter Reverse-Proxy

GOOGLE.CLIENT_ID=       # OAuth-Client (Typ "Webanwendung") aus der
GOOGLE.CLIENT_SECRET=   # Google Cloud Console; ohne diese Werte ist die
GOOGLE.REDIRECT_URI=    # API gesperrt (401) - siehe unten
```

### Google-Login einrichten

Der Web-Zugang ist ausschliesslich ueber Google-Konten moeglich - es gibt keine
lokale Registrierung und keine Passwoerter:

1. [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
   -> *APIs & Dienste* -> *OAuth-Berechtigungen* -> *Anwendungsidentitaet*
   erstellen, E-Mail-Adressen als Scope zulassen.
2. *OAuth-Client-ID* vom Typ **Webanwendung** anlegen.
3. Autorisierte Weiterleitungs-URIs: `http://<server-ip>:8080/auth/callback`
4. Client-ID/Secret in die `app.config` eintragen und neu starten.

Beim ersten Login wird das Konto automatisch angelegt. Die PWA merkt sich die
Sitzung (30 Tage), `Abmelden` beendet sie serverseitig.

Auf dem Pi als Dienst: siehe `deploy/uart-db-collector.service`.

## Web-App (PWA) am Phone

Der Web-Server lauft im gleichen Prozess wie der Collector und liest ueber den
bestehenden `ORMapper` (Hibernate) aus der Datenbank.

1. Phone und Pi im selben Netzwerk.
2. Am Phone oeffnen: `http://<pi-ip>:8080`
3. Mit Google anmelden.
4. Ueber "Verwalten" das eigene Feldgeraet verknuepfen (aus der DB waehlen
   oder per Namen neu anlegen - Daten erscheinen, sobald die Edge-Device
   dafuer meldet).
5. "Zum Home-Screen hinzufuegen" -> laeuft dann wie eine App (PWA, offline-faehig
   via Service Worker, Auto-Aktualisierung, Verlauf-Charts je Sensor).

Daten sichtbar sind nur fuer Geraete, die mit dem angemeldeten Konto verknuepft
sind; die Edge-Devices schreiben weiterhin **alle** erreichbaren Geraete in die
Datenbank, die Filterung passiert ausschliesslich beim Lesen.

Einstellungen (Zahnrad): Server-URL ueberschreiben (noetig nur, wenn die App
anderswo heraufgeladen wird) und Auto-Aktualisierungs-Intervall.

### Sicherheit

* Alle `/api/*` Endpunkte ausser `/api/health` verlangen einen Session-Token
  (401 sonst); unbestimmte bzw. fremde IDs antworten mit 404, damit sich
  Geraete nicht enumerieren lassen.
* Sessions: 256-Bit-Token (SecureRandom), nur dessen SHA-256-Hash liegt
  serverseitig; Cookie `HttpOnly` + `SameSite=Lax`, 30 Tage gueltig, per
  Logout sofort widerrufen. Native Apps nutzen stattdessen `Authorization:
  Bearer <token>` (CORS erlaubt Reads ohne Credentials - Cookies gelten
  cross-origin nie).
* OAuth `state` ist zufaellig, einseitig und per Cookie gebunden (CSRF).
* Antworten ohne Stacktraces/SQL, Koerper max. 8 KB, Pfad-Traversal geblockt,
  Security-Header (CSP, nosniff, Frame-Deny, Referrer-Policy).

### REST-API

Alle Endpunkte ausser `/api/health` und `GET /api/auth/me` erfordern
Authorization (Cookie oder Bearer) und sind auf den angemeldeten Nutzer
eingeschraeenkt.

| Endpoint | Beschreibung |
|---|---|
| `GET /api/health` | Status + Device-Anzahl (oeffentlich, keine Daten) |
| `GET /auth/login` | Startet den Google-Login (302) |
| `GET /auth/callback` | OAuth-Rueckkehr; setzt Session, leitet auf `/#token=…` um |
| `POST /auth/logout` | Beendet die Session |
| `GET /api/auth/me` | Eigener Account (eigene Session vorausgesetzt) |
| `GET /api/devices` | Verknuepfte Devices inkl. Sensor-Anzahl, letzter Messzeitpunkt |
| `GET /api/devices/available` | Noch **nicht** verknuepfte Devices (nur Identitaet, keine Werte) |
| `POST /api/devices` | Verknuepfen: `{"deviceId":n}` oder `{"name":"…"}` (neuer Name legt das Device an) |
| `POST /api/devices/{id}/link` | Verknuepfen per Pfad |
| `DELETE /api/devices/{id}` | Verknuepfung loesen |
| `GET /api/sensors` | Alle Sensoren der verknuepften Devices |
| `GET /api/sensors/{id}` | Ein Sensor (fremd/unbekannt -> 404) |
| `GET /api/measurements/latest` | Letzter Messwert je eigenem Sensor |
| `GET /api/measurements?sensorId={id}&from={ms}&to={ms}&limit={n}` | Zeitreihe + Min/Max/Ø (fremd -> 404) |
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
damit der WebView der nativen App die Daten laden kann. Nach dem Google-Login
wird der Session-Token als `Authorization: Bearer` mitschickt (Cookies sind
cross-origin bewusst nicht erlaubt).

## SSH-Tunnel

* connect to ssh host via: "ssh -o HostKeyAlgorithms=ssh-rsa -o FingerprintHash=md5 user@target.host" to obtain ssh key in knownhost file
