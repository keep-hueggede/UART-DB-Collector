package Repository;

/**
 * Who is asking: admins see every device, users only the devices an admin
 * assigned to them.
 */
public record Viewer(int userId, boolean admin) {}
