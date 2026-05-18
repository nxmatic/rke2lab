package io.nxmatic.rk2lab.systemdadapter.service;

import io.nxmatic.rk2lab.systemdadapter.SystemdAdapterProperties;
import io.nxmatic.rk2lab.systemdadapter.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.springframework.stereotype.Component;

@Component
public class DbusSystemdStatusSnapshotProvider implements SystemdStatusSnapshotProvider {

  private static final String SYSTEMD_DESTINATION = "org.freedesktop.systemd1";
  private static final String SYSTEMD_MANAGER_PATH = "/org/freedesktop/systemd1";
  private static final String SYSTEMD_MANAGER_INTERFACE = "org.freedesktop.systemd1.Manager";
  private static final String SYSTEMD_UNIT_INTERFACE = "org.freedesktop.systemd1.Unit";

  private final SystemdAdapterProperties properties;

  public DbusSystemdStatusSnapshotProvider(SystemdAdapterProperties properties) {
    this.properties = properties;
  }

  @Override
  public SystemdStatusSnapshot currentSnapshot() {
    final String mandatoryTarget = properties.mandatoryTarget();
    final String mandatoryTargetPath = unitObjectPath(mandatoryTarget);

    try (DBusConnection connection = DBusConnectionBuilder.forSystemBus().build()) {
      final Properties targetProps =
          connection.getRemoteObject(SYSTEMD_DESTINATION, mandatoryTargetPath, Properties.class);
      final Properties managerProps =
          connection.getRemoteObject(SYSTEMD_DESTINATION, SYSTEMD_MANAGER_PATH, Properties.class);

      final String targetState =
          normalizeState(String.valueOf(targetProps.Get(SYSTEMD_UNIT_INTERFACE, "ActiveState")));
      final boolean mandatoryHealthy = "active".equals(targetState);

      final int pendingJobs =
          parseNonNegativeInt(managerProps.Get(SYSTEMD_MANAGER_INTERFACE, "NJobs"), "NJobs");
      final int failedUnits =
          parseNonNegativeInt(
              managerProps.Get(SYSTEMD_MANAGER_INTERFACE, "NFailedUnits"), "NFailedUnits");

      final Map<String, Integer> jobsByState = new LinkedHashMap<>();
      if (pendingJobs > 0) {
        jobsByState.put("pending", pendingJobs);
      }

      final boolean runtimeReady = mandatoryHealthy && pendingJobs == 0 && failedUnits == 0;

      final String summary =
          "mandatoryTarget="
              + mandatoryTarget
              + "(state="
              + targetState
              + "), pendingJobs="
              + pendingJobs
              + ", failedUnits="
              + failedUnits
              + ", source=dbus-java";

      return new SystemdStatusSnapshot(
          Instant.now(),
          mandatoryTarget,
          targetState,
          mandatoryHealthy,
          pendingJobs,
          jobsByState,
          failedUnits,
          runtimeReady,
          summary);
    } catch (DBusException | IOException ex) {
      throw new IllegalStateException("Failed to query systemd state over D-Bus", ex);
    }
  }

  private int parseNonNegativeInt(Object value, String fieldName) {
    if (value instanceof Number number) {
      return Math.max(number.intValue(), 0);
    }
    if (value != null) {
      final String raw = value.toString().trim();
      try {
        return Math.max(Integer.parseInt(raw), 0);
      } catch (NumberFormatException ex) {
        throw new IllegalStateException(
            "Invalid integer returned for " + fieldName + ": '" + raw + "'", ex);
      }
    }
    throw new IllegalStateException("Missing integer value for " + fieldName);
  }

  private String unitObjectPath(String unitName) {
    return SYSTEMD_MANAGER_PATH + "/unit/" + escapeUnitName(unitName);
  }

  private String escapeUnitName(String value) {
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

  private String normalizeState(String value) {
    if (value == null) {
      return "unknown";
    }
    final String trimmed = value.trim().toLowerCase();
    if (trimmed.isBlank()) {
      return "unknown";
    }
    return trimmed;
  }
}
