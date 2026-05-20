package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.connections.transports.TransportBuilder.SaslAuthMode;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Probes systemd-adapter over DBus-on-TCP and returns a {@link SystemdStatusSnapshot}. */
public final class DbusSystemdProbe {

  private static final Logger LOG = LoggerFactory.getLogger(DbusSystemdProbe.class);

  private static final String SYSTEMD_DESTINATION = "org.freedesktop.systemd1";
  private static final String SYSTEMD_MANAGER_PATH = "/org/freedesktop/systemd1";
  private static final String SYSTEMD_MANAGER_INTERFACE = "org.freedesktop.systemd1.Manager";
  private static final String SYSTEMD_UNIT_INTERFACE = "org.freedesktop.systemd1.Unit";
  private static final String SYSTEMD_SERVICE_INTERFACE = "org.freedesktop.systemd1.Service";

  private static final String MANDATORY_TARGET_UNIT = "rke2lab.target";
  private static final String CLOUD_INIT_MAIN_UNIT = "cloud-init-main.service";

  private DbusSystemdProbe() {}

  public static SystemdStatusSnapshot probe(BootstrapConfig config) {
    final String host = config.systemdAdapterDbusHost();
    final int port = config.systemdAdapterDbusPort();
    final String busAddress = "tcp:host=" + host + ",port=" + port;

    LOG.info("connecting to {}", busAddress);

    try (DBusConnection connection =
        DBusConnectionBuilder.forAddress(busAddress)
            .transportConfig()
            .configureSasl()
            .withAuthMode(SaslAuthMode.AUTH_ANONYMOUS)
            .back()
            .back()
            .build()) {

      final Properties managerProps =
          connection.getRemoteObject(SYSTEMD_DESTINATION, SYSTEMD_MANAGER_PATH, Properties.class);

      final Properties targetProps = unitProperties(connection, MANDATORY_TARGET_UNIT);
      final Properties cloudInitProps = unitProperties(connection, CLOUD_INIT_MAIN_UNIT);

      final String targetState = stringProp(targetProps, SYSTEMD_UNIT_INTERFACE, "ActiveState");
      final boolean targetHealthy = "active".equals(targetState);
      final int pendingJobs = intProp(managerProps, SYSTEMD_MANAGER_INTERFACE, "NJobs");
      final int failedUnits = intProp(managerProps, SYSTEMD_MANAGER_INTERFACE, "NFailedUnits");
      final String cloudInitState =
          stringProp(cloudInitProps, SYSTEMD_UNIT_INTERFACE, "ActiveState");
      final String cloudInitResult =
          stringProp(cloudInitProps, SYSTEMD_SERVICE_INTERFACE, "Result");
      final boolean cloudInitHealthy =
          "active".equals(cloudInitState) && "success".equals(cloudInitResult);
      final boolean runtimePrecheckReady = targetHealthy && pendingJobs == 0 && failedUnits == 0;

      final String summary =
          "mandatoryTarget="
              + MANDATORY_TARGET_UNIT
              + "(state="
              + targetState
              + "), pendingJobs="
              + pendingJobs
              + ", failedUnits="
              + failedUnits
              + ", cloudInitMain="
              + CLOUD_INIT_MAIN_UNIT
              + "(state="
              + cloudInitState
              + ",result="
              + cloudInitResult
              + ",healthy="
              + cloudInitHealthy
              + "), source=systemd-dbus-probe";

      final Map<String, String> connectionContext =
          Map.of(
              "adapterHost", host,
              "adapterPort", Integer.toString(port),
              "incusInstance", nullSafe(config.nodeName()),
              "nixosHost", nullSafe(config.imageBuilderHost()),
              "systemBusAddress", busAddress);

      return SystemdStatusSnapshot.builder()
          .observedAt(Instant.now().toString())
          .mandatoryTarget(MANDATORY_TARGET_UNIT)
          .mandatoryTargetState(targetState)
          .mandatoryTargetHealthy(targetHealthy)
          .pendingJobs(pendingJobs)
          .failedUnits(failedUnits)
          .runtimePrecheckReady(runtimePrecheckReady)
          .connectionContext(connectionContext)
          .summary(summary)
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException(
          "systemd dbus probe failed at " + busAddress + ": " + ex.getMessage(), ex);
    }
  }

  private static Properties unitProperties(DBusConnection connection, String unitName)
      throws DBusException {
    final String path = SYSTEMD_MANAGER_PATH + "/unit/" + escapeUnitName(unitName);
    return connection.getRemoteObject(SYSTEMD_DESTINATION, path, Properties.class);
  }

  private static String stringProp(Properties props, String iface, String name) {
    final Object value = props.Get(iface, name);
    return value == null ? "unknown" : value.toString();
  }

  private static int intProp(Properties props, String iface, String name) {
    final Object value = props.Get(iface, name);
    if (value instanceof Number numberValue) {
      return Math.max(numberValue.intValue(), 0);
    }
    if (value == null) {
      return 0;
    }
    try {
      return Math.max(Integer.parseInt(value.toString().trim()), 0);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static String nullSafe(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
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
