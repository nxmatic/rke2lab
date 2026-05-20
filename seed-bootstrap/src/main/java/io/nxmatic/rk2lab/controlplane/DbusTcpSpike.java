package io.nxmatic.rk2lab.controlplane;

import java.nio.charset.StandardCharsets;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.connections.transports.TransportBuilder.SaslAuthMode;
import org.freedesktop.dbus.interfaces.Properties;

public final class DbusTcpSpike {

  private static final String SYSTEMD_DESTINATION = "org.freedesktop.systemd1";
  private static final String SYSTEMD_MANAGER_PATH = "/org/freedesktop/systemd1";
  private static final String SYSTEMD_MANAGER_INTERFACE = "org.freedesktop.systemd1.Manager";
  private static final String SYSTEMD_UNIT_INTERFACE = "org.freedesktop.systemd1.Unit";

  private DbusTcpSpike() {}

  public static void main(String[] args) throws Exception {
    final String host = args.length > 0 ? args[0] : "10.80.0.10";
    final int port = args.length > 1 ? Integer.parseInt(args[1]) : 12434;
    final String target = args.length > 2 ? args[2] : "default.target";

    final String busAddress = "tcp:host=" + host + ",port=" + port + ",listen=false";
    System.out.println("[spike] connecting to " + busAddress);

    try (DBusConnection connection =
        DBusConnectionBuilder.forAddress(busAddress)
            .transportConfig()
            .configureSasl()
            .withAuthMode(SaslAuthMode.AUTH_ANONYMOUS)
            .back()
            .back()
            .build()) {
      final String unitPath = SYSTEMD_MANAGER_PATH + "/unit/" + escapeUnitName(target);

      final Properties unitProps =
          connection.getRemoteObject(SYSTEMD_DESTINATION, unitPath, Properties.class);
      final Properties managerProps =
          connection.getRemoteObject(SYSTEMD_DESTINATION, SYSTEMD_MANAGER_PATH, Properties.class);

      final Object activeState = unitProps.Get(SYSTEMD_UNIT_INTERFACE, "ActiveState");
      final Object subState = unitProps.Get(SYSTEMD_UNIT_INTERFACE, "SubState");
      final Object nJobs = managerProps.Get(SYSTEMD_MANAGER_INTERFACE, "NJobs");
      final Object nFailed = managerProps.Get(SYSTEMD_MANAGER_INTERFACE, "NFailedUnits");

      System.out.println("[spike] target=" + target);
      System.out.println("[spike] ActiveState=" + activeState);
      System.out.println("[spike] SubState=" + subState);
      System.out.println("[spike] NJobs=" + nJobs);
      System.out.println("[spike] NFailedUnits=" + nFailed);
    }
  }

  private static String escapeUnitName(String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    final StringBuilder builder = new StringBuilder(bytes.length * 3);
    for (byte current : bytes) {
      final int unsigned = current & 0xFF;
      if ((unsigned >= 'A' && unsigned <= 'Z')
          || (unsigned >= 'a' && unsigned <= 'z')
          || (unsigned >= '0' && unsigned <= '9')) {
        builder.append((char) unsigned);
      } else {
        builder.append('_');
        builder.append(Character.forDigit((unsigned >> 4) & 0xF, 16));
        builder.append(Character.forDigit(unsigned & 0xF, 16));
      }
    }
    return builder.toString();
  }
}
