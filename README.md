# UART-DB-Collector

Empfaengt Sensordaten per UART (z.B. ESP32-C6 ThreadReceiver), speichert sie per
Hibernate in einer H2-Datenbank oder in einer MySQL-Datenbank auf einem Server
(ueber einen SSH-Tunnel) und stellt eine Web-App fuer das Phone bereit.

## Funktionen

* UART-Empfang (newline-delimited JSON) und Speicherung je Sensor
* Lokale H2-Datei oder MySQL - direkt oder durch einen SSH-Tunnel
* Web-App (PWA) mit Google-Login, Verlaufscharts und Auto-Aktualisierung
* Gerateverwaltung: eigene Feldgerate verknuepfen, umbenennen, Verknuepfung loesen
* Daten werden pro Nutzer gefiltert (Edge-Devices schreiben weiterhin alles)
* REST-API mit Session- oder Bearer-Authentifizierung
* Betrieb als systemd-Dienst auf dem Raspberry Pi

## Voraussetzungen

* Java 17 oder neuer
* Maven (Build)
* Serielle Schnittstelle zum Empfaenger (z.B. `/dev/serial0` auf dem Pi)
* Optional: MySQL-Server + SSH-Zugang fuer die Remote-Datenbank

## Build & Start

```bash
mvn package
java -jar target/UART-DB-Collector-1.0-SNAPSHOT.jar
```

Tests:

```bash
mvn test
```

Die Tests laufen ohne externe Infrastruktur: HTTP-Server und SSH-Server werden
im Testprozess gestartet. Nur `Test_MySQLConnector` und `Test_UARTCom` sind
deaktiviert, weil sie einen echten MySQL-Server bzw. serielle Hardware
benoetigen.

## Konfiguration

Die Konfiguration liegt in `./app.config` (nicht im Git); Vorlage ist
`app.config.example`.

```properties
UART.PORTNAME=/dev/serial0
UART.BAUD=115200

DB.DRIVER=h2            # h2 (lokal, ./data/<DB.NAME>.mv.db) oder mysql
DB.HOST=localhost
DB.PORT=3306
DB.NAME=wetter
DB.USER=sa
DB.PASSWORD=
DB.PARAMS=              # optional, nur MySQL: z.B. allowPublicKeyRetrieval=true

SERVER.ENABLED=true     # Web-Server ein/ausschalten
SERVER.HOST=0.0.0.0     # im LAN erreichbar
SERVER.PORT=8080
SERVER.WEBROOT=./web    # optional; das web/ Verzeichnis im jar ist der Fallback
SERVER.SECURE_COOKIE=false  # true bei HTTPS hinter Reverse-Proxy

GOOGLE.CLIENT_ID=       # OAuth-Client (Typ "Webanwendung") aus der
GOOGLE.CLIENT_SECRET=   # Google Cloud Console; ohne diese Werte ist die
GOOGLE.REDIRECT_URI=    # API gesperrt (401) - siehe unten

SSH.ENABLED=false       # true: MySQL auf einem Server via SSH-Tunnel
```

Der Collector liest `./app.config` (im Entwicklungsbetrieb alternativ
`./java/Controller/app.config`); fehlt beides, bricht der Start ab. Ohne
`GOOGLE.CLIENT_ID`/`GOOGLE.CLIENT_SECRET` laeuft der Collector weiter, aber die
Web-API bleibt gesperrt (401) und der Login-Button zeigt einen Hinweis.

## Datenbank

**H2 (Default):** `DB.DRIVER=h2` legt die Datenbank als
`./data/<DB.NAME>.mv.db` an. Host/Port werden ignoriert.

**MySQL:** `DB.DRIVER=mysql` verbindet zu `DB.HOST:DB.PORT`. Zusaetzliche
JDBC-Parameter lassen sich mit `DB.PARAMS` angeben (ohne fuehrendes `?`), z.B.
`allowPublicKeyRetrieval=true&serverTimezone=UTC` - nuetzlich bei MySQL 8 mit
`caching_sha2_password`. Die Tabellen werden per Hibernate (`hbm2ddl=update`)
automatisch angelegt/aktualisiert.

Der Collector schreibt ueber eine langlebige Hibernate-Session, der Web-Server
liest ueber kurzlebige Sessions aus derselben Datenbank.

## MySQL auf einem Server via SSH-Tunnel

Der Collector kann die Messwerte in eine MySQL-Datenbank auf einem Server im
Internet schreiben. Die Verbindung laeuft ueber einen SSH-Tunnel (lokales
Port-Forwarding), der MySQL-Port muss also **nicht** oeffentlich erreichbar
sein.

Beim Start baut der Collector die SSH-Verbindung auf, oeffnet einen lokalen
Listener auf `127.0.0.1:<freier Port>` und verbindet Hibernate dorthin; der
SSH-Server reicht jede Verbindung an `SSH.REMOTE_HOST:SSH.REMOTE_PORT` weiter.
Beim Herunterfahren wird zuerst die Datenbank getrennt, danach der Tunnel
geschlossen. `SSH.ENABLED=true` erfordert `DB.DRIVER=mysql`, sonst bricht der
Start mit einer Meldung ab.

### 1. SSH-Schluessel einrichten

```bash
ssh-keygen -t ed25519 -C uart-collector
ssh-copy-id -i ~/.ssh/id_ed25519.pub user@server.example.org
```

Passwort-Auth (`SSH.AUTH=password`) funktioniert ebenfalls, Key-Auth ist aber
empfohlen (kein Klartext-Passwort in der Config).

### 2. Hostkey hinterlegen

```bash
ssh-keyscan -p 22 server.example.org > ./known_hosts
```

Bei `SSH.STRICT_HOST_KEY_CHECKING=true` (Default) muss die Datei existieren und
den Server enthalten, sonst bricht der Start mit einem Hinweis auf
`ssh-keyscan` ab. Nur fuer Tests: `SSH.STRICT_HOST_KEY_CHECKING=false` schaltet
die Pruefung ab (dann ist `SSH.KNOWN_HOSTS` optional).

### 3. app.config

```properties
DB.DRIVER=mysql
DB.NAME=wetter
DB.USER=uart
DB.PASSWORD=...
# DB.HOST/DB.PORT werden bei aktivem Tunnel ignoriert
DB.PARAMS=allowPublicKeyRetrieval=true&serverTimezone=UTC

SSH.ENABLED=true
SSH.HOST=server.example.org
SSH.PORT=22
SSH.USER=uart
SSH.AUTH=publickey
SSH.PRIVATE_KEY=~/.ssh/id_ed25519
SSH.KNOWN_HOSTS=./known_hosts
SSH.REMOTE_HOST=127.0.0.1
SSH.REMOTE_PORT=3306
SSH.LOCAL_PORT=0
```

* `SSH.LOCAL_PORT=0` waehlt automatisch einen freien lokalen Port. Ein fester
  Port (z.B. `3307`) ist moeglich, muss aber frei sein - sonst Startfehler.
* `SSH.AUTH` ist optional: ohne Angabe wird `publickey` verwendet, wenn
  `SSH.PRIVATE_KEY` gesetzt ist, sonst `password`.
* `SSH.PASSPHRASE` wird nur fuer verschluesselte Private Keys benoetigt.
* `SSH.REMOTE_HOST`/`SSH.REMOTE_PORT` gelten aus Sicht des SSH-Servers.
* `SSH.CONNECT_TIMEOUT` (Default `15000` ms) begrenzt den Verbindungsaufbau.
* `~` in `SSH.PRIVATE_KEY`/`SSH.KNOWN_HOSTS` wird zum Home-Verzeichnis des
  laufenden Benutzers expandiert.

### Sicherheit

* Der lokale Listener bindet ausschliesslich an `127.0.0.1`; die Datenbank ist
  dadurch nicht im LAN erreichbar.
* Hostkey-Pruefung ist standardmaessig aktiv, Passwoerter/Passphrasen werden
  nie geloggt.
* Keep-Alive alle 30 s (3 Fehlversuche), damit Idle-Verbindungen nicht
  abgebaut werden.
* Bricht die SSH-Verbindung zur Laufzeit ab, schlaegt der naechste
  DB-Schreibvorgang fehl. Der Dienst sollte dann automatisch neu starten
  (`deploy/uart-db-collector.service` mit `Restart=always`).

### Fehlersuche

| Meldung | Ursache / Loesung |
|---|---|
| `Auth fail` | `SSH.USER`, Key oder Passwort pruefen |
| `Hostkey unbekannt: ssh-keyscan …` | Hostkey in `known_hosts` aufnehmen |
| `ConnectException: Connection refused` | `SSH.HOST`/`SSH.PORT` pruefen |
| `SSH.ENABLED=true erfordert DB.DRIVER=mysql` | `DB.DRIVER=mysql` setzen |
| MySQL `Public Key Retrieval is not allowed` | `DB.PARAMS=allowPublicKeyRetrieval=true` |
| `Address already in use` beim Start | fester `SSH.LOCAL_PORT` ist belegt -> `0` verwenden |

## Web-App (PWA) am Phone

Der Web-Server laeuft im gleichen Prozess wie der Collector und liest ueber den
bestehenden `ORMapper` (Hibernate) aus der Datenbank.

1. Phone und Pi im selben Netzwerk.
2. Am Phone oeffnen: `http://<pi-ip>:8080`
3. Mit Google anmelden.
4. Ueber "Verwalten" das eigene Feldgeraet verknuepfen (aus der DB waehlen
   oder per Namen neu anlegen - Daten erscheinen, sobald die Edge-Device
   dafuer meldet).
5. Geraet nach Bedarf umbenennen (Stift-Symbol auf der Geraetekarte).
6. "Zum Home-Screen hinzufuegen" -> laeuft dann wie eine App (PWA, offline-faehig
   via Service Worker, Auto-Aktualisierung, Verlauf-Charts je Sensor).

Sichtbar sind nur Geraete, die mit dem angemeldeten Konto verknuepft sind; die
Edge-Devices schreiben weiterhin **alle** erreichbaren Geraete in die Datenbank,
die Filterung passiert ausschliesslich beim Lesen.

Einstellungen (Zahnrad): Server-URL ueberschreiben (noetig nur, wenn die App
anderswo heraufgeladen wird) und Auto-Aktualisierungs-Intervall.

### Google-Login einrichten

Der Web-Zugang ist ausschliesslich ueber Google-Konten moeglich - es gibt keine
lokale Registrierung und keine Passwoerter:

1. [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
   -> *APIs & Dienste* -> *OAuth-Berechtigungen* -> *Anwendungsidentitaet*
   erstellen, E-Mail-Adressen als Scope zulassen.
2. *OAuth-Client-ID* vom Typ **Webanwendung** anlegen.
3. Autorisierte Weiterleitungs-URIs: `http://<server-ip>:8080/auth/callback`
   (bzw. die in `GOOGLE.REDIRECT_URI` gesetzte URI).
4. Client-ID/Secret in die `app.config` eintragen und neu starten.

Ist `GOOGLE.REDIRECT_URI` leer, wird die Callback-URL aus dem `Host`-Header des
Requests abgeleitet. Beim ersten Login wird das Konto automatisch angelegt. Die
PWA merkt sich die Sitzung (30 Tage), `Abmelden` beendet sie serverseitig.

### Sicherheit

* Alle `/api/*` Endpunkte ausser `/api/health` verlangen einen Session-Token
  (401 sonst); unbekannte bzw. fremde IDs antworten mit 404, damit sich
  Geraete nicht enumerieren lassen.
* Sessions: 256-Bit-Token (SecureRandom), nur dessen SHA-256-Hash liegt
  serverseitig; Cookie `HttpOnly` + `SameSite=Lax`, 30 Tage gueltig, per
  Logout sofort widerrufen. Native Apps nutzen stattdessen `Authorization:
  Bearer <token>`.
* OAuth `state` ist zufaellig, einseitig und per Cookie gebunden (CSRF).
* Antworten ohne Stacktraces/SQL, Koerper max. 8 KB, Pfad-Traversal geblockt,
  Security-Header (CSP, nosniff, Frame-Deny, Referrer-Policy).
* CORS erlaubt `GET, POST, PUT, DELETE, OPTIONS`, aber bewusst **keine**
  Credentials - cross-origin funktioniert nur mit Bearer-Token, Cookies gelten
  cross-origin nie.

### REST-API

Alle `/api/*` Endpunkte ausser `/api/health` erfordern Authorization (Cookie
oder Bearer) und sind auf den angemeldeten Nutzer eingeschraenkt. Die
`/auth/*` Endpunkte sind der Login-Flow selbst.

| Endpoint | Beschreibung |
|---|---|
| `GET /api/health` | Status + Device-Anzahl (oeffentlich, keine Daten) |
| `GET /auth/login` | Startet den Google-Login (302) |
| `GET /auth/callback` | OAuth-Rueckkehr; setzt Session, leitet auf `/#token=…` um |
| `POST /auth/logout` | Beendet die Session |
| `GET /api/auth/me` | Eigener Account (eigene Session vorausgesetzt) |
| `GET /api/devices` | Verknuepfte Devices inkl. `hardwareId`, Sensor-Anzahl, letzter Messzeitpunkt |
| `GET /api/devices/available` | Noch **nicht** verknuepfte Devices (nur Identitaet, keine Werte) |
| `POST /api/devices` | Verknuepfen: `{"deviceId":n}` oder `{"name":"…"}` (neuer Name legt das Device an) |
| `POST /api/devices/{id}/link` | Verknuepfen per Pfad |
| `PUT /api/devices/{id}` | Umbenennen: `{"name":"…"}` (1-40 Zeichen; 409 bei belegtem Namen, 415 bei falschem Content-Type, 413 bei zu grossem Body) |
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
wird der Session-Token als `Authorization: Bearer` mitgeschickt (Cookies sind
cross-origin bewusst nicht erlaubt).

## Deployment auf dem Pi (systemd)

Vorlage: `deploy/uart-db-collector.service` (Benutzerdienst, kein root):

```bash
mkdir -p ~/.config/systemd/user
cp deploy/uart-db-collector.service ~/.config/systemd/user/
loginctl enable-linger $USER
systemctl --user daemon-reload
systemctl --user enable --now uart-db-collector
```

Das Jar erwartet `app.config` im Arbeitsverzeichnis (`WorkingDirectory=%h/app`
im Dienst), also `app.jar` und `app.config` nach `~/app/` kopieren. Es laeuft
mit `Restart=always` - das ist auch der empfohlene Neustart-Mechanismus, falls
die SSH-Verbindung zur Remote-DB abbrechen sollte. Logs:
`tail -f ~/app/collector.log`.

## Projektstruktur

```
java/Controller/   Start, Konfiguration, UART-Parsing, Persistenz-Fluss
java/UARTCom/      Serielle Schnittstelle + Listener
java/WebServer/    HTTP-Server, Auth (Google/Sessions), REST-API, DB-Zugriffe
java/SSHTunnel/    SSH-Tunnel (lokales Port-Forwarding) + Konfiguration
java/ORMUtil/      Hibernate/ORMapper
java/Model/        Entities (Device, Sensor, Measurement, UserAccount, ...)
java/DBConnector/  Datenbank-Basisklassen (JDBC)
web/               PWA (HTML/CSS/JS, Service Worker, Icons)
test/              JUnit-Tests (H2, HTTP-Server, SSH-Server im Testprozess)
deploy/            systemd-Unit
data/              H2-Dateien (wird zur Laufzeit angelegt)
```
