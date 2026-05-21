package io.nxmatic.rk2lab.systemdadapter.service;

import de.thjom.java.systemd.Manager;
import de.thjom.java.systemd.Systemd;
import de.thjom.java.systemd.Target;
import io.nxmatic.rk2lab.systemdadapter.SystemdAdapterProperties;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.freedesktop.dbus.exceptions.DBusException;
import org.springframework.stereotype.Component;

@Component
public class DbusSystemdStatusSnapshotProvider implements SystemdStatusSnapshotProvider {

  private static final String DEFAULT_SYSTEM_BUS_ADDRESS =
      "unix:path=/var/run/dbus/system_bus_socket";

  private final SystemdAdapterProperties properties;
  private final Systemd systemd;

  public DbusSystemdStatusSnapshotProvider(SystemdAdapterProperties properties, Systemd systemd) {
    this.properties = properties;
    this.systemd = systemd;
  }

  @Override
  public SystemdStatusSnapshot currentSnapshot() {
    final String mandatoryTarget = properties.mandatoryTarget();

    try {
      final Manager manager = systemd.getManager();
      final Target target = manager.getTarget(mandatoryTarget);

      final String targetState = normalizeState(target.getActiveState());
      final boolean mandatoryHealthy = "active".equals(targetState);

      final int pendingJobs = clampToNonNegativeInt(manager.getNJobs());
      final int failedUnits = clampToNonNegativeInt(manager.getNFailedUnits());

      final Map<String, Integer> jobsByState = new LinkedHashMap<>();
      if (pendingJobs > 0) {
        jobsByState.put("pending", pendingJobs);
      }

      final boolean runtimeReady = mandatoryHealthy && pendingJobs == 0 && failedUnits == 0;
      final Map<String, String> connectionContext = connectionContext();
      final String nixosHost = connectionContext.getOrDefault("nixosHost", "unknown");
      final String incusInstance = connectionContext.getOrDefault("incusInstance", "unknown");
      final String systemBusAddress = connectionContext.getOrDefault("systemBusAddress", "unknown");

      final String summary =
          "mandatoryTarget="
              + mandatoryTarget
              + "(state="
              + targetState
              + "), pendingJobs="
              + pendingJobs
              + ", failedUnits="
              + failedUnits
              + ", nixosHost="
              + nixosHost
              + ", incusInstance="
              + incusInstance
              + ", systemBusAddress="
              + systemBusAddress
              + ", source=java-systemd";

      return new SystemdStatusSnapshot(
          java.time.Instant.now().toString(),
          mandatoryTarget,
          targetState,
          mandatoryHealthy,
          pendingJobs,
          jobsByState,
          Map.of(), // pendingJobDetails
          failedUnits,
          Map.of(), // failedUnitDetails
          runtimeReady,
          connectionContext,
          summary,
          Map.of());
    } catch (DBusException ex) {
      throw new IllegalStateException("Failed to query systemd state over D-Bus", ex);
    }
  }

  private int clampToNonNegativeInt(long value) {
    if (value <= 0L) {
      return 0;
    }
    return (int) Math.min(value, Integer.MAX_VALUE);
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

  private Map<String, String> connectionContext() {
    final String hostName = detectHostName();
    final String nixosHost =
        firstNonBlank(System.getenv("RK2LAB_NIXOS_HOST"), System.getenv("RKE2LAB_ACCESS_HOST"));
    final String incusInstance =
        firstNonBlank(System.getenv("RKE2LAB_NODE_NAME"), System.getenv("HOSTNAME"), hostName);
    final String systemBusAddress =
        firstNonBlank(System.getenv("DBUS_SYSTEM_BUS_ADDRESS"), DEFAULT_SYSTEM_BUS_ADDRESS);

    final LinkedHashMap<String, String> context = new LinkedHashMap<>();
    context.put("nixosHost", normalizeLabel(nixosHost));
    context.put("incusInstance", normalizeLabel(incusInstance));
    context.put("adapterHost", normalizeLabel(hostName));
    context.put("systemBusAddress", normalizeLabel(systemBusAddress));
    return Map.copyOf(context);
  }

  private String detectHostName() {
    try {
      return normalizeLabel(InetAddress.getLocalHost().getHostName());
    } catch (UnknownHostException ignored) {
      return normalizeLabel(System.getenv("HOSTNAME"));
    }
  }

  private String normalizeLabel(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.trim();
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }

    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }
}
