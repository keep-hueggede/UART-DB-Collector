# UML

Klassendiagramme der beiden lauffaehigen Module (`collector`, `webserver`) und
der gemeinsamen Bibliothek (`core`). Die Diagramme sind Mermaid und werden von
GitHub und der IntelliJ-Markdown-Vorschau direkt gerendert.

Bewusst weggelassen: Getter/Setter der Entities, private Hilfsmethoden sowie die
ungenutzten Altklassen `ComStruct`, `DataPoint`, `IDatabase`, `MySQLConnector`.

## 1. Module und Verteilung

```mermaid
flowchart LR
    subgraph Pi["Raspberry Pi"]
        C6["ESP32-C6<br/>ThreadReceiver"] -- "UART<br/>JSON-Zeilen" --> COL["uart-db-collector.jar<br/>(collector + core)"]
        COL -. "DB nicht erreichbar" .-> SPOOL[("spool/pending.log")]
    end
    subgraph Server["Webserver (Internet)"]
        APACHE["Apache<br/>HTTPS Reverse-Proxy"] --> WEB["uart-db-webserver.jar<br/>(webserver + core)"]
        WEB --> DB[("MySQL<br/>127.0.0.1:3306")]
    end
    COL -- "SSH-Tunnel" --> DB
    Browser["Browser / PWA"] -- "HTTPS" --> APACHE
    WEB -. "OAuth" .-> Google["Google Login"]
```

| Modul | Abhaengigkeiten | Inhalt |
|---|---|---|
| `core` | Hibernate, MySQL Connector/J | Entities, `ORMapper`, `DatabaseSetup` |
| `collector` | core, jSerialComm, Jackson, H2, JSch | UART-Empfang, Speichern, Puffer, SSH-Tunnel |
| `webserver` | core, Jackson | REST-API, Google-Login, PWA |

## 2. Domain-Modell (core)

```mermaid
classDiagram
    direction LR
    class Device {
        -int id
        -String name
        -String hardwareId
        -DeviceType deviceType
        -String location
        -boolean online
        -Date lastSeen
        -Date createdAt
    }
    class Sensor {
        -int id
        -SensorType sensorType
        -String name
        -String unit
        -Double calibrationFactor
        -Double calibrationOffset
        +getCalibrationFactor() double
        +getCalibrationOffset() double
        +setCalibration(double factor, double offset)
    }
    class Measurement {
        -int id
        -Double value
        -Date timestamp
    }
    class Actuator {
        -int id
        -ActuatorType actuatorType
        -String name
        -Double state
        -Double targetState
    }
    class UserAccount {
        -int id
        -String googleSub
        -String email
        -String name
        -String pictureUrl
    }
    class UserDevice {
        -int id
        -Date addedAt
    }
    class SensorType {
        <<enumeration>>
        TEMPERATURE
        PRESSURE
        HUMIDITY
        WIND
        WIND_DIRECTION
        RAIN
        ALTITUDE
        VOLTAGE
        CUSTOM
    }
    class DeviceType {
        <<enumeration>>
        SENSOR
        ACTUATOR
        GATEWAY
    }

    Device "1" *-- "0..*" Sensor : sensors
    Device "1" *-- "0..*" Actuator : actuators
    Sensor "1" *-- "0..*" Measurement : measurements
    UserAccount "1" -- "0..*" UserDevice
    Device "1" -- "0..*" UserDevice
    Sensor ..> SensorType
    Device ..> DeviceType
```

```mermaid
classDiagram
    direction LR
    class CommonDbConnector {
        <<abstract>>
        #String host
        #Integer port
        #String database
        #buildConnectionString(Boolean toDatabase)* String
    }
    class ORMapper {
        -Session session
        -SessionFactory sessionFactory
        +connect()
        +disconnect()
        +reconnect()
        +ping() boolean
        +persist(Object entity)
        +merge(Object entity)
        +clear()
        +findByHardwareId(Class~T~, String) T
        +findByNameAndDevice(Class~T~, String, Object) T
        +readInSession(Function~Session,T~) T
        +writeInSession(Function~Session,T~) T
    }
    class DatabaseSetup {
        +List~Class~ ENTITIES$
        +loadConfig(String[] args)$ Properties
        +DatabaseSetup(Properties)
        +DatabaseSetup(Properties, Tunnel, int localPort, String description)
        +createMapper() ORMapper
        +connect()
        +reconnect()
        +ping() boolean
        +tunnelLost() boolean
        +close()
    }
    class Tunnel {
        <<interface>>
        +start() int
        +stop()
        +isRunning() boolean
    }
    CommonDbConnector <|-- ORMapper
    DatabaseSetup --> ORMapper : creates
    DatabaseSetup o-- "0..1" Tunnel
    DatabaseSetup .. Tunnel : nested interface
```

## 3. Collector (Pi)

```mermaid
classDiagram
    direction TB
    class Main {
        +main(String[] args)$
    }
    class Controller {
        -UARTCom com
        -DatabaseSetup db
        -Ingestor ingestor
        -ScheduledExecutorService worker
        +Controller(Properties)
        +startListening()
        +shutdown()
        +onSignaled(String line)
        +parseLine(JsonNode root)$ ParsedData
        -reconnect() boolean
        -maintain()
    }
    class ParsedData {
        +String src
        +Map~SensorType,Double~ values
    }
    class Ingestor {
        -ORMapper mapper
        -Spool spool
        -BooleanSupplier reconnect
        -boolean healthy
        +ingest(String line, long receivedAt)
        +drain()
        +markUnhealthy()
        +isHealthy() boolean
    }
    class Spool {
        -Path file
        +append(long receivedAt, String line) boolean
        +readAll() List~Entry~
        +removeFirst(int n)
        +isEmpty() boolean
        +size() int
    }
    class Entry {
        <<record>>
        long receivedAt
        String line
    }
    class Observer {
        <<abstract>>
        #List~IObserverListener~ listeners
        +addListener(IObserverListener)
        #fireSignal(String)
    }
    class IObserverListener {
        <<interface>>
        +onSignaled(String answer)
    }
    class IListenerInterface {
        <<interface>>
        +open()
        +close()
        +Send(String)
        +Listen()
        +isConnected() Boolean
    }
    class UARTCom {
        -SerialPort com
        +open()
        +Listen()
        +close()
    }
    class TunneledDatabase {
        +from(Properties)$ DatabaseSetup
    }
    class SSHTunnel {
        -SSHTunnelConfig config
        -int lastBoundPort
        +start() int
        +stop()
        +isRunning() boolean
    }
    class SSHTunnelConfig {
        +boolean enabled
        +String host
        +int localPort
        +from(Properties)$ SSHTunnelConfig
        +validate()
    }
    class DatabaseSetup {
        <<core>>
    }
    class Tunnel {
        <<interface, core>>
    }
    class ORMapper {
        <<core>>
    }

    Main --> Controller : creates
    Controller ..|> IObserverListener
    Controller *-- Ingestor
    Controller *-- UARTCom
    Controller --> DatabaseSetup
    Controller ..> TunneledDatabase : DB setup
    Controller .. ParsedData : nested
    Ingestor --> Spool
    Ingestor --> ORMapper
    Ingestor ..> ParsedData
    Spool .. Entry : nested
    UARTCom --|> Observer
    UARTCom ..|> IListenerInterface
    Observer o-- IObserverListener : notifies
    TunneledDatabase ..> SSHTunnel : creates
    TunneledDatabase ..> DatabaseSetup : creates
    SSHTunnel ..|> Tunnel
    SSHTunnel --> SSHTunnelConfig
    DatabaseSetup --> ORMapper
```

Ablauf einer UART-Zeile: `UARTCom` → `Controller.onSignaled` (Zeitstempel) →
Worker-Thread `db-writer` → `Ingestor.ingest` → Datenbank, bzw. bei Ausfall
`Spool.append`. Alle 30 s ruft `Controller.maintain` `Ingestor.drain` auf
(Tunnel/DB wiederverbinden, Puffer nachtragen).

## 4. Webserver

```mermaid
classDiagram
    direction TB
    class WebMain {
        +main(String[] args)$
    }
    class WebServer {
        -Router api
        -Router auth
        -StaticFileHandler staticFiles
        -SessionManager sessions
        +start()
        +stop()
        -onApi(HttpExchange)
        -onAuth(HttpExchange)
        -onStatic(HttpExchange)
    }
    class Router {
        +get(String pattern, Handler) Router
        +publicGet(String pattern, Handler) Router
        +post(String pattern, Handler) Router
        +put(String pattern, Handler) Router
        +delete(String pattern, Handler) Router
        +match(String method, String path) Match
    }
    class Match {
        <<record>>
        Handler handler
        Map params
        int status
        boolean isPublic
        +dispatch(HttpCall, String notFoundMessage)
    }
    class Handler {
        <<interface>>
        +handle(HttpCall call)
    }
    class HttpCall {
        +method() String
        +path() String
        +pathId() Integer
        +query() Map
        +user() Session
        +jsonBody() JsonNode
        +json(int status, Object payload)
        +error(int status, String message)
        +redirect(String location)
    }
    class SessionManager {
        +create(UserAccount) String
        +resolve(String cookie, String authorization) Session
        +invalidate(String cookie, String authorization)
    }
    class GoogleAuth {
        +isConfigured() boolean
        +loginUrl(String redirectUri, String state) String
        +exchangeCode(String code, String redirectUri) GoogleUser
    }
    class OAuthStateStore {
        +issue() String
        +consume(String state) boolean
    }
    class Repositories {
        <<record>>
        +of(ORMapper)$ Repositories
    }
    class UserRepository {
        +loginOrCreate(sub, email, name, picture) UserAccount
    }
    class DeviceRepository {
        +listLinked(int userId) List
        +listAvailable(int userId) List
        +link(int userId, Integer deviceId, String name) Map
        +unlink(int userId, int deviceId) boolean
        +rename(int userId, int deviceId, String name) RenameResult
        +count() long
    }
    class SensorRepository {
        +listLinked(int userId) List
        +findLinked(int userId, int sensorId) Map
        +setCalibration(int userId, int sensorId, double factor, double offset) boolean
    }
    class MeasurementRepository {
        +latest(int userId) List
        +series(int userId, int sensorId, Long from, Long to, int limit) Map
    }
    class Ownership {
        <<package-private>>
        ownsSensor(Session, int userId, int sensorId)$ boolean
        ownsDevice(Session, int userId, int deviceId)$ boolean
        corrected(Sensor, Double raw)$ Double
    }
    class ORMapper {
        <<core>>
    }

    WebMain --> WebServer : creates
    WebMain ..> Repositories : creates
    WebServer *-- "2" Router
    WebServer --> SessionManager
    Router .. Match : nested
    Router o-- "0..*" Handler
    Handler ..> HttpCall
    Repositories *-- UserRepository
    Repositories *-- DeviceRepository
    Repositories *-- SensorRepository
    Repositories *-- MeasurementRepository
    DeviceRepository ..> Ownership
    SensorRepository ..> Ownership
    MeasurementRepository ..> Ownership
    UserRepository --> ORMapper
    DeviceRepository --> ORMapper
    SensorRepository --> ORMapper
    MeasurementRepository --> ORMapper
```

### Handler (Command-Pattern)

Jeder Endpunkt ist eine eigene `Handler`-Klasse im Paket `WebServer.Handlers`:

```mermaid
classDiagram
    direction LR
    class Handler {
        <<interface>>
        +handle(HttpCall call)
    }
    class Repositories {
        <<record>>
    }
    Handler <|.. HealthHandler : GET /api/health (public)
    Handler <|.. MeHandler : GET /api/auth/me
    Handler <|.. ListDevicesHandler : GET /api/devices
    Handler <|.. LinkDeviceHandler : POST /api/devices
    Handler <|.. AvailableDevicesHandler : GET /api/devices/available
    Handler <|.. LinkDeviceByIdHandler : POST /api/devices/{id}/link
    Handler <|.. RenameDeviceHandler : PUT /api/devices/{id}
    Handler <|.. UnlinkDeviceHandler : DELETE /api/devices/{id}
    Handler <|.. ListSensorsHandler : GET /api/sensors
    Handler <|.. GetSensorHandler : GET /api/sensors/{id}
    Handler <|.. SetCalibrationHandler : PUT /api/sensors/{id}/calibration
    Handler <|.. LatestMeasurementsHandler : GET /api/measurements/latest
    Handler <|.. MeasurementSeriesHandler : GET /api/measurements
    Handler <|.. LoginHandler : GET /auth/login
    Handler <|.. CallbackHandler : GET /auth/callback
    Handler <|.. LogoutHandler : POST /auth/logout
    Handler <|.. StaticFileHandler : GET / (PWA)

    LoginHandler --> GoogleAuth
    LoginHandler --> OAuthStateStore
    CallbackHandler --> GoogleAuth
    CallbackHandler --> OAuthStateStore
    CallbackHandler --> SessionManager
    CallbackHandler --> Repositories
    LogoutHandler --> SessionManager
```

Ablauf einer API-Anfrage: `WebServer.onApi` setzt Sicherheits-Header und CORS →
`Router.match` → fuer nicht-oeffentliche Routen Session pruefen (401) →
`Match.dispatch` (404/405 oder Handler) → Handler nutzt `HttpCall` und die
`Repositories`; Fehler werden auf 400/413/500 ohne interne Details abgebildet.
