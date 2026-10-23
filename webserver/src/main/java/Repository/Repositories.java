package Repository;

import ORMUtil.ORMapper;

/**
 * All repositories of the web server, created once and handed to the handlers.
 * Every query runs in a short-lived session, so HTTP threads never share the
 * collector's long-lived session, and is scoped to the requesting user.
 */
public record Repositories(UserRepository users, DeviceRepository devices,
                           SensorRepository sensors, MeasurementRepository measurements) {

    public static Repositories of(ORMapper mapper) {
        return new Repositories(new UserRepository(mapper), new DeviceRepository(mapper),
                new SensorRepository(mapper), new MeasurementRepository(mapper));
    }
}
