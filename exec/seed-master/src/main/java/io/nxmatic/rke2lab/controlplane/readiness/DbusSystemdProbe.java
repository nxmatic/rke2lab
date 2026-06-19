package io.nxmatic.rke2lab.controlplane.readiness;

import de.thjom.java.systemd.Manager;
import de.thjom.java.systemd.Service;
import de.thjom.java.systemd.Systemd;
import de.thjom.java.systemd.Target;
import de.thjom.java.systemd.types.UnitType;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.systemd.port.SystemdStatusSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.connections.transports.TransportBuilder.SaslAuthMode;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Probes systemd-adapter over DBus-on-TCP and returns a {@link SystemdStatusSnapshot}. */
public final class DbusSystemdProbe {

  private static final Logger LOG = LoggerFactory.getLogger(DbusSystemdProbe.class);

  private static final String MANDATORY_TARGET_UNIT = "rke2lab.target";
  private static final String CLOUD_INIT_MAIN_UNIT = "cloud-init-main.service";

  private static final String SYSTEMD_DESTINATION = "org.freedesktop.systemd1";
  private static final String SYSTEMD_MANAGER_PATH = "/org/freedesktop/systemd1";

  /** Minimal binding for {@code Manager.ListJobs} (returns 6-tuples u s s s o o). */
  @DBusInterfaceName("org.freedesktop.systemd1.Manager")
  @SuppressWarnings("checkstyle:methodname")
  private interface JobLister extends DBusInterface {
    List<Object[]> ListJobs();
  }

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

      final Systemd systemd = Systemd.fromConnection(connection);
      final Manager manager = systemd.getManager();

      final Target target = manager.getTarget(MANDATORY_TARGET_UNIT);
      final Service cloudInit = manager.getService(CLOUD_INIT_MAIN_UNIT);

      final String targetState = nullSafeState(target.getActiveState());
      final boolean targetHealthy = "active".equals(targetState);
      final int pendingJobs = clampNonNegative(manager.getNJobs());
      final int failedUnits = clampNonNegative(manager.getNFailedUnits());
      final String cloudInitState = nullSafeState(cloudInit.getActiveState());
      final String cloudInitResult = nullSafeState(cloudInit.getResult());
      final boolean cloudInitHealthy =
          "success".equals(cloudInitResult)
              && ("active".equals(cloudInitState) || "inactive".equals(cloudInitState));
      final boolean runtimePrecheckReady = targetHealthy && pendingJobs == 0 && failedUnits == 0;

      final Map<String, String> pendingDependencies = collectPendingDependencies(manager, target);
      final Map<String, String> failedUnitDetails = collectFailedUnitDetails(manager);
      final Map<String, String> pendingJobDetails = collectPendingJobs(connection);

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
              + "), source=java-systemd";

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
          .pendingJobDetails(pendingJobDetails)
          .failedUnits(failedUnits)
          .failedUnitDetails(failedUnitDetails)
          .runtimePrecheckReady(runtimePrecheckReady)
          .connectionContext(connectionContext)
          .summary(summary)
          .pendingDependencies(pendingDependencies)
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException(
          "systemd dbus probe failed at " + busAddress + ": " + ex.getMessage(), ex);
    }
  }

  private static Map<String, String> collectPendingJobs(DBusConnection connection) {
    final LinkedHashMap<String, String> jobs = new LinkedHashMap<>();
    try {
      final JobLister lister =
          connection.getRemoteObject(SYSTEMD_DESTINATION, SYSTEMD_MANAGER_PATH, JobLister.class);
      final List<Object[]> rows = lister.ListJobs();
      if (rows == null) {
        return Map.of();
      }
      for (Object[] row : rows) {
        if (row == null || row.length < 4) {
          continue;
        }
        final long jobId = row[0] instanceof UInt32 u ? u.longValue() : 0L;
        final String unitName = row[1] == null ? "" : row[1].toString();
        if (unitName.isBlank()) {
          continue;
        }
        final String jobType = row[2] == null ? "unknown" : row[2].toString();
        final String jobState = row[3] == null ? "unknown" : row[3].toString();
        jobs.put(unitName, jobType + "/" + jobState + "#" + jobId);
      }
    } catch (Exception ex) {
      LOG.debug("ListJobs lookup failed: {}", ex.getMessage());
    }
    return Map.copyOf(jobs);
  }

  private static Map<String, String> collectFailedUnitDetails(Manager manager) {
    final LinkedHashMap<String, String> details = new LinkedHashMap<>();
    try {
      for (UnitType unit : manager.listUnits()) {
        if (unit == null) {
          continue;
        }
        if (!"failed".equals(unit.getActiveState())) {
          continue;
        }
        final String unitName = unit.getUnitName();
        if (unitName == null || unitName.isBlank()) {
          continue;
        }
        details.put(unitName, unit.getActiveState() + "/" + unit.getSubState());
      }
    } catch (Exception ex) {
      LOG.debug("listUnits lookup failed: {}", ex.getMessage());
    }
    return Map.copyOf(details);
  }

  private static Map<String, String> collectPendingDependencies(Manager manager, Target target) {
    final Set<String> dependencies = new LinkedHashSet<>();
    addAll(dependencies, target.getRequires());
    addAll(dependencies, target.getWants());
    addAll(dependencies, target.getBindsTo());

    final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    for (String unitName : dependencies) {
      try {
        final String state = nullSafeState(manager.getUnit(unitName).getActiveState());
        if (!"active".equals(state)) {
          pending.put(unitName, state);
        }
      } catch (DBusException ex) {
        pending.put(unitName, "unreadable");
      }
    }
    return Map.copyOf(pending);
  }

  private static void addAll(Set<String> sink, List<String> values) {
    if (values == null) {
      return;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        sink.add(value);
      }
    }
  }

  private static int clampNonNegative(long value) {
    if (value <= 0L) {
      return 0;
    }
    return (int) Math.min(value, Integer.MAX_VALUE);
  }

  private static String nullSafeState(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String nullSafe(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
