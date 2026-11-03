# UART-DB-Collector

Empfaengt Sensordaten per UART (z.B. ESP32-C6 ThreadReceiver), speichert sie per
Hibernate in einer H2-Datenbank oder in einer MySQL-Datenbank auf einem Server
(ueber einen SSH-Tunnel) und stellt eine Web-App fuer das Phone bereit.

## Funktionen

* Zwei getrennt lauffaehige Programme (Maven-Module):
  `uart-db-collector.jar` auf dem Pi, `uart-db-webserver.jar` auf einem Server
* UART-Empfang (newline-delimited JSON) und Speicherung je Sensor
* Lokale H2-Datei oder MySQL - direkt oder durch einen SSH-Tunnel
* Lokaler Puffer auf dem Pi: ist die Datenbank nicht erreichbar, werden die
  Daten zwischengespeichert und mit Empfangszeit nachgetragen; der SSH-Tunnel
  verbindet sich selbststaendig neu
* Web-App (PWA) mit Google-Login, Verlaufscharts und Auto-Aktualisierung
* Ersteinrichtung im Browser (wie bei einem CMS): Einmal-Code aus einer Datei
  auf dem Server, das erste Google-Konto wird Administrator
* Rollen: Administratoren weisen Benutzern Feldgeraete zu, benennen Geraete um,
  setzen Korrekturwerte und pflegen die Einstellungen
* Daten werden pro Nutzer gefiltert (Edge-Devices schreiben weiterhin alles)
* REST-API mit Session- oder Bearer-Authentifizierung
* Betrieb als systemd-Dienst auf dem Raspberry Pi

## Voraussetzungen

* Java 17 oder neuer
* Maven (Build)
* Serielle Schnittstelle zum Empfaenger (z.B. `/dev/serial0` auf dem Pi)
* Optional: MySQL-Server + SSH-Zugang fuer die Remote-Datenbank

## Build & Start

Das Projekt ist ein Maven-Multi-Modul-Projekt (vergleichbar mit einer
C#-Solution): das Root-`pom.xml` baut alle Module.

```bash
mvn package                           # alles bauen + testen
mvn -pl collector -am package         # nur Collector (+ core)
mvn -pl webserver -am package         # nur Webserver (+ core)

java -jar collector/target/uart-db-collector.jar    # Pi: UART -> DB
java -jar webserver/target/uart-db-webserver.jar    # Server: Web-App + API
```

Beide lesen `./app.config` im Arbeitsverzeichnis (oder den Pfad als erstes
Argument). Tests und Testabdeckung:

```bash
mvn verify      # Tests + JaCoCo-Bericht je Modul in */target/site/jacoco/index.html
```

Die GitHub Action (`.github/workflows/maven.yml`) baut bei jedem Push/PR auf
`main` alle Module, fuehrt die Tests aus, zeigt die Abdeckung je Modul in der
Job-Zusammenfassung und stellt Test-/Abdeckungsberichte und beide Jars als
Artefakte bereit.

Die Tests laufen ohne externe Infrastruktur: HTTP-Server, SSH-Server und ein
Stub der Google-Endpunkte werden im Testprozess gestartet. Nur `Test_MySQLConnector` und `Test_UARTCom` sind
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

SSH.ENABLED=false       # true: MySQL auf einem Server via SSH-Tunnel
```

Beide Jars lesen `./app.config` oder den als erstes Argument uebergebenen
Pfad; fehlt die Datei, bricht der Start ab.

Google-Login und Sitzungsdauer stehen **nicht** mehr in der `app.config`,
sondern in der Datenbank (Tabelle `Settings`) und werden bei der
Ersteinrichtung bzw. spaeter unter *Verwalten -> Server* gepflegt. Alte
`GOOGLE.*`-Werte werden beim ersten Start einmalig uebernommen und koennen danach
aus der Datei entfernt werden.

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
| `Auth cancel` | verschluesselter Key ohne `SSH.PASSPHRASE`; Passphrase setzen |
| `Hostkey unbekannt: ssh-keyscan …` | Hostkey in `known_hosts` aufnehmen |
| `ConnectException: Connection refused` | `SSH.HOST`/`SSH.PORT` pruefen |
| `SSH.ENABLED=true erfordert DB.DRIVER=mysql` | `DB.DRIVER=mysql` setzen |
| MySQL `Public Key Retrieval is not allowed` | `DB.PARAMS=allowPublicKeyRetrieval=true` |
| `Address already in use` beim Start | fester `SSH.LOCAL_PORT` ist belegt -> `0` verwenden |

## Web-App (PWA) am Phone

Der Web-Server laeuft im gleichen Prozess wie der Collector und liest ueber den
bestehenden `ORMapper` (Hibernate) aus der Datenbank.

1. Phone und Server im selben Netzwerk (bzw. Server im Internet).
2. Am Phone oeffnen: `http://<server-ip>:8080`
3. Mit Google anmelden.
4. Ein Administrator weist dem Konto unter *Verwalten* die Feldgeraete zu.
   Bis dahin zeigt die App nur einen Hinweis.
5. "Zum Home-Screen hinzufuegen" -> laeuft dann wie eine App (PWA, offline-faehig
   via Service Worker, Auto-Aktualisierung, Verlauf-Charts je Sensor).

Die Edge-Devices schreiben weiterhin **alle** erreichbaren Geraete in die
Datenbank; welche ein Benutzer sieht, entscheidet allein die Zuweisung durch
einen Administrator.

Einstellungen (Zahnrad): Server-URL ueberschreiben (noetig nur, wenn die App
anderswo heraufgeladen wird) und Auto-Aktualisierungs-Intervall.

### Ersteinrichtung und erster Administrator

Solange es keinen Administrator gibt, schreibt der Webserver beim Start einen
Einmal-Code in `setup-code.txt` in seinem Arbeitsverzeichnis (Rechte `600`)
und meldet den Pfad im Log. Wer die Seite dann aufruft, landet auf der
Einrichtungsseite:

1. Code aus der Datei eintragen, z.B. `cat /opt/uart-db/setup-code.txt` (`WorkingDirectory` des Dienstes).
   Damit ist nachgewiesen, dass man Zugriff auf das Server-Verzeichnis hat -
   Root-Rechte sind nicht noetig.
2. In der [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
   einen OAuth-Client vom Typ **Webanwendung** anlegen; die Seite zeigt die
   Weiterleitungs-URI an, die dort eingetragen werden muss. Client-ID und
   Clientschluessel auf der Einrichtungsseite eintragen.
3. *Speichern und mit Google anmelden*: Das Google-Konto, mit dem man sich jetzt
   anmeldet, wird Administrator. Die Code-Datei wird geloescht, die
   Einrichtung ist geschlossen.

Nach 10 falschen Codes wird ein neuer Code in die Datei geschrieben. Geht der
letzte Administrator verloren (z.B. DB neu aufgesetzt), oeffnet ein Neustart
ohne Admin die Einrichtung erneut.

### Rollen

| | Benutzer | Administrator |
|---|---|---|
| Daten sehen | nur zugewiesene Feldgeraete | alle |
| Geraete umbenennen, Korrekturwerte | – | ja |
| Benutzer freigeben, Geraete zuweisen | – | ja |
| Andere zu Administratoren machen | – | ja (der letzte Admin kann nicht entzogen werden) |
| Google-Login, Sitzungsdauer aendern | – | ja (*Verwalten -> Server*) |

Neue Google-Konten werden beim ersten Login als Benutzer ohne Geraete angelegt.
Rollenaenderungen gelten sofort, auch fuer bestehende Sitzungen.

### Google-Login

Der Web-Zugang ist ausschliesslich ueber Google-Konten moeglich - es gibt keine
lokale Registrierung und keine Passwoerter. Ist keine Weiterleitungs-URI
eingetragen, wird sie aus dem `Host`-Header des Requests abgeleitet (hinter
einem Reverse-Proxy mit `X-Forwarded-Proto`). Die PWA merkt sich die Sitzung
(Standard 30 Tage), `Abmelden` beendet sie serverseitig.

### Sicherheit

* Alle `/api/*` Endpunkte ausser `/api/health` und `/api/setup` verlangen einen
  Session-Token (401 sonst); Admin-Endpunkte antworten Benutzern mit 403.
  Unbekannte bzw. nicht zugewiesene IDs antworten mit 404, damit sich Geraete
  nicht enumerieren lassen.
* Ersteinrichtung: Code nur in einer Datei auf dem Server, nie per HTTP;
  Vergleich in konstanter Zeit, nach 10 Fehlversuchen neuer Code. Der
  Nachweis wird per kurzlebigem `HttpOnly`-Cookie an den Google-Login gebunden.
* Der Google-Clientschluessel wird nie ausgeliefert, auch nicht an Admins.
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

Alle `/api/*` Endpunkte ausser `/api/health` und `/api/setup` erfordern
Authorization (Cookie oder Bearer). Lesende Endpunkte zeigen Benutzern nur die
zugewiesenen Geraete, Administratoren alles. Schreibende Endpunkte verlangen
`Content-Type: application/json` (sonst 415). Die `/auth/*` Endpunkte sind der
Login-Flow selbst.

| Endpoint | Zugriff | Beschreibung |
|---|---|---|
| `GET /api/health` | oeffentlich | Status + Device-Anzahl (keine Daten) |
| `GET /api/setup` | oeffentlich | `setupRequired`; waehrend der Einrichtung auch Pfad der Code-Datei und Weiterleitungs-URI |
| `POST /api/setup` | oeffentlich | `{"code","googleClientId","googleClientSecret"}`; 403 falscher Code, 409 schon eingerichtet |
| `GET /auth/login` | oeffentlich | Startet den Google-Login (302) |
| `GET /auth/callback` | oeffentlich | OAuth-Rueckkehr; setzt Session, leitet auf `/#token=…` um |
| `POST /auth/logout` | Benutzer | Beendet die Session |
| `GET /api/auth/me` | Benutzer | Eigener Account inkl. `role` und `approved` |
| `GET /api/devices` | Benutzer | Sichtbare Devices inkl. `hardwareId`, Sensor-Anzahl, letzter Messzeitpunkt |
| `GET /api/sensors` | Benutzer | Sensoren der sichtbaren Devices |
| `GET /api/sensors/{id}` | Benutzer | Ein Sensor (nicht sichtbar/unbekannt -> 404) |
| `GET /api/measurements/latest` | Benutzer | Letzter Messwert je sichtbarem Sensor |
| `GET /api/measurements?sensorId={id}&from={ms}&to={ms}&limit={n}` | Benutzer | Zeitreihe + Min/Max/Ø |
| `GET /api/measurements?sensorId={id}&window={ms}` | Benutzer | Zeitreihe der letzten `window` Millisekunden |
| `PUT /api/devices/{id}` | Admin | Umbenennen: `{"name":"…"}` (1-40 Zeichen; 409 bei belegtem Namen) |
| `PUT /api/sensors/{id}/calibration` | Admin | Korrekturwerte `{"factor","offset"}` |
| `GET /api/admin/users` | Admin | Alle Konten mit Rolle, letztem Login und zugewiesenen Device-IDs |
| `PUT /api/admin/users/{id}/role` | Admin | `{"role":"ADMIN"\|"USER"}`; 409 fuer den letzten Admin |
| `PUT /api/admin/users/{id}/devices/{deviceId}` | Admin | Device zuweisen |
| `DELETE /api/admin/users/{id}/devices/{deviceId}` | Admin | Zuweisung entfernen |
| `GET /api/admin/settings` | Admin | Google-Client (ohne Schluessel) und Sitzungsdauer |
| `PUT /api/admin/settings` | Admin | `googleClientId`, `googleClientSecret` (leer = unveraendert), `googleRedirectUri`, `sessionTtlDays` (1-365) |

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

Der Dienst erwartet `uart-db-collector.jar` und `app.config` in `~/app/`
(`WorkingDirectory=%h/app`). Faellt die Datenbank oder der SSH-Tunnel aus,
laeuft der Collector weiter: UART-Zeilen landen in `./spool/pending.log` und
werden alle 30 s nachgetragen, sobald die Verbindung wieder steht. Logs:
`tail -f ~/app/collector.log`.

## Getrennter Betrieb: Collector auf dem Pi, Web-App im Internet

```
ESP32-C6 --UART--> Pi: uart-db-collector.jar --SSH-Tunnel--> Server: MySQL (127.0.0.1)
                                                               ^
                        Browser --HTTPS--> Reverse-Proxy --> uart-db-webserver.jar
```

* Die Verbindung geht nur **vom Pi nach aussen** (SSH) - am Heimrouter wird
  kein Port freigegeben, MySQL lauscht auf dem Server nur auf 127.0.0.1.
* **Pi** (`app.config`): `DB.DRIVER=mysql`, `SSH.ENABLED=true` (siehe
  Abschnitt SSH-Tunnel). Fuer den SSH-Nutzer auf dem Server genuegt reines
  Port-Forwarding, z.B. in `authorized_keys`:
  `restrict,port-forwarding,permitopen="127.0.0.1:3306" ssh-ed25519 AAAA...`
* **Server** (`app.config`): `DB.DRIVER=mysql`, `DB.HOST=127.0.0.1`,
  `SERVER.HOST=127.0.0.1`, `SERVER.SECURE_COOKIE=true`. Dienst:
  `deploy/uart-db-webserver.service` (Systemdienst unter eigenem Nutzer).
  Danach `https://<domain>` aufrufen und die Ersteinrichtung durchlaufen
  (Code liegt in `setup-code.txt` im `WorkingDirectory` des Dienstes).
* **Reverse-Proxy mit HTTPS**, z.B. Caddy (holt das Zertifikat selbst):

  ```
  wetter.example.org {
      reverse_proxy 127.0.0.1:8080
  }
  ```

Beide Programme legen das Schema per Hibernate an bzw. aktualisieren es -
deshalb immer **beide Jars aus demselben Stand** ausrollen.

## Projektstruktur

Klassendiagramme (UML) beider Module: [docs/UML.md](docs/UML.md)

```
pom.xml                      Parent ("Solution"): Module + Versionen
core/                        gemeinsame Bibliothek
  src/main/java/Model/         Entities (Device, Sensor, Measurement, UserAccount, ...)
  src/main/java/ORMUtil/       Hibernate/ORMapper, DatabaseSetup (Config, DB)
  src/main/java/DBConnector/   Datenbank-Basisklassen (JDBC)
collector/                   -> uart-db-collector.jar (Pi)
  src/main/java/Controller/    Start, UART-Parsing, Ingestor (Speichern), Spool (Puffer)
  src/main/java/UARTCom/       Serielle Schnittstelle + Listener
  src/main/java/SSHTunnel/     SSH-Tunnel zur MySQL-DB auf dem Webserver
webserver/                   -> uart-db-webserver.jar (Server, nur MySQL: ohne H2 und SSH)
  src/main/java/WebServer/     HTTP-Server, Auth (Google/Sessions), REST-API, WebMain
  src/main/resources/web/      PWA (HTML/CSS/JS, Service Worker, Icons)
*/src/test/java/             JUnit-Tests (H2, HTTP-Server, SSH-Server im Testprozess)
deploy/                      systemd-Units (Collector, Webserver)
.github/workflows/           CI: Build + Tests aller Module
```
