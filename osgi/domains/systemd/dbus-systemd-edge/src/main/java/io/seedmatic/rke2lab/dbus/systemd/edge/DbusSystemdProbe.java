package io.seedmatic.rke2lab.dbus.systemd.edge;

import de.thjom.java.systemd.Manager;
import de.thjom.java.systemd.Systemd;
import de.thjom.java.systemd.Target;
import de.thjom.java.systemd.interfaces.ManagerInterface;
import de.thjom.java.systemd.types.UnitType;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessAwait;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import io.seedmatic.rke2lab.systemd.contract.SystemdProbeRequest;
import io.seedmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.seedmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.connections.transports.TransportBuilder.SaslAuthMode;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.types.UInt32;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code dbus-systemd} edge: the single door toward systemd over its dbus-on-TCP
 * endpoint. It implements the systemd domain's {@link SystemdRuntimeProbe} seam by opening an
 * anonymous-SASL DBus connection to {@code tcp:host=…,port=…}, reading the live state through
 * {@code de.thjom.java.systemd}, and translating it into the pure {@link SystemdStatusSnapshot}.
 *
 * <p>Readiness is AWAITED, not sampled once: the {@link ReadinessAwait} skeleton bounds the two
 * phases the boot window needs — the reach (retried until the budget's connect deadline, absorbing
 * a cold boot / image re-seed where the endpoint refuses) and, once connected, the convergence
 * (dbus {@code JobRemoved} signals wake a recheck of the snapshot until it reads ready, or the
 * ready deadline elapses). This is where the old deadline-poll watcher's patience now lives — the
 * BDD migration had left the seam one-shot, so a gate probed during {@code grow} failed the instant
 * the node was not yet up.
 *
 * <p>TCP is playable in OSGi, so this edge lives in the OSGi world; SCR publishes it and the host
 * resolves it from the registry. The dbus-java stack is embedded as nested jars on this bundle's
 * {@code Bundle-ClassPath}, so the transport {@code ServiceLoader} discovers {@code
 * TcpTransportProvider} inside this one classloader.
 */
@Component(service = SystemdRuntimeProbe.class)
public final class DbusSystemdProbe implements SystemdRuntimeProbe {

  private static final Logger LOG = LoggerFactory.getLogger(DbusSystemdProbe.class);

  private static final String MANDATORY_TARGET_UNIT = "rke2lab.target";

  private static final String SYSTEMD_DESTINATION = "org.freedesktop.systemd1";
  private static final String SYSTEMD_MANAGER_PATH = "/org/freedesktop/systemd1";

  /** Minimal binding for {@code Manager.ListJobs} (returns 6-tuples u s s s o o). */
  @DBusInterfaceName("org.freedesktop.systemd1.Manager")
  @SuppressWarnings("checkstyle:methodname")
  private interface JobLister extends DBusInterface {
    List<Object[]> ListJobs();
  }

  @Override
  public SystemdStatusSnapshot awaitReady(SystemdProbeRequest request, ReadinessBudget budget) {
    final String busAddress = busAddress(request);
    final ReadinessAwait readiness =
        new ReadinessAwait(
            budget.interval(),
            budget.connect(),
            budget.ready(),
            message -> LOG.info("systemd readiness at {}: {}", busAddress, message));

    final Supplier<Optional<DBusConnection>> connect =
        () -> Optional.of(openConnection(busAddress));
    final BiFunction<DBusConnection, Duration, SystemdStatusSnapshot> converge =
        (connection, readyBudget) ->
            awaitConvergence(connection, readyBudget, budget.interval(), request);

    // The reach either connects (→ convergence wait) or throws a ReadinessAwaitException carrying
    // the
    // last connect failure once the connect deadline elapses — the scenario's When catches it as a
    // CONNECTION_REFUSED symptom, so an unreachable node reads as a diagnosed not-ready facet.
    return readiness.await(connect, converge);
  }

  private static String busAddress(SystemdProbeRequest request) {
    return "tcp:host=" + request.dbusHost() + ",port=" + request.dbusPort();
  }

  /**
   * One reach attempt — open the anonymous-SASL DBus connection. A refused endpoint (the node not
   * booted far enough) throws here; the {@link ReadinessAwait} reach loop treats a thrown runtime
   * failure as "not up yet" and retries until the connect deadline, so the wrap is deliberate. The
   * returned connection is the skeleton's {@code AutoCloseable} channel — it closes it after
   * convergence, so nothing here does.
   */
  private static DBusConnection openConnection(String busAddress) {
    try {
      return DBusConnectionBuilder.forAddress(busAddress)
          .transportConfig()
          .configureSasl()
          .withAuthMode(SaslAuthMode.AUTH_ANONYMOUS)
          .back()
          .back()
          .build();
    } catch (Exception notUpYet) {
      throw new IllegalStateException(
          "systemd dbus endpoint not up yet at " + busAddress, notUpYet);
    }
  }

  /**
   * Await systemd converging to ready over a live connection: subscribe to {@code JobRemoved}, read
   * the current snapshot FIRST (a node already converged returns at once), then let each signal
   * wake a recheck — bounded by {@code recheckCap} so a missed signal on a direct peer connection
   * still rechecks, and by the {@code readyBudget} deadline (returning the last not-ready snapshot
   * so the checks downstream see exactly which facet stalled).
   */
  private SystemdStatusSnapshot awaitConvergence(
      DBusConnection connection,
      Duration readyBudget,
      Duration recheckCap,
      SystemdProbeRequest request) {
    try {
      final Systemd systemd = Systemd.fromConnection(connection);
      final Manager manager = systemd.getManager();
      manager.subscribe();

      final Object wake = new Object();
      final DBusSigHandler<ManagerInterface.JobRemoved> handler =
          signal -> {
            synchronized (wake) {
              wake.notifyAll();
            }
          };
      manager.addHandler(ManagerInterface.JobRemoved.class, handler);
      try {
        final long deadline = System.nanoTime() + readyBudget.toNanos();
        while (true) {
          final SystemdStatusSnapshot snapshot = readSnapshot(manager, connection, request);
          if (snapshot.runtimePrecheckReady()) {
            return snapshot;
          }
          final long remainingNanos = deadline - System.nanoTime();
          if (remainingNanos <= 0L) {
            return snapshot; // last not-ready state at the ready deadline
          }
          synchronized (wake) {
            wake.wait(waitMillis(remainingNanos, recheckCap));
          }
        }
      } finally {
        manager.removeHandler(ManagerInterface.JobRemoved.class, handler);
        manager.unsubscribe();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while awaiting systemd convergence", interrupted);
    } catch (DBusException ex) {
      throw new IllegalStateException("systemd convergence wait failed: " + ex.getMessage(), ex);
    }
  }

  /** Each wake bounded by the remaining budget AND the recheck ceiling — never below 1ms. */
  private static long waitMillis(long remainingNanos, Duration recheckCap) {
    final long remainingMillis = Math.max(1L, remainingNanos / 1_000_000L);
    final long capMillis = Math.max(1L, recheckCap.toMillis());
    return Math.min(remainingMillis, capMillis);
  }

  /** Read the live systemd state once and translate it into the pure snapshot. */
  private SystemdStatusSnapshot readSnapshot(
      Manager manager, DBusConnection connection, SystemdProbeRequest request) {
    try {
      final Target target = manager.getTarget(MANDATORY_TARGET_UNIT);

      final String targetState = nullSafeState(target.getActiveState());
      final boolean targetHealthy = "active".equals(targetState);
      final int pendingJobs = clampNonNegative(manager.getNJobs());
      final int failedUnits = clampNonNegative(manager.getNFailedUnits());
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
              + ", source=java-systemd";

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
          .connectionContext(connectionContext(request))
          .summary(summary)
          .pendingDependencies(pendingDependencies)
          .build();
    } catch (DBusException ex) {
      throw new IllegalStateException(
          "systemd snapshot read failed at " + busAddress(request) + ": " + ex.getMessage(), ex);
    }
  }

  private static Map<String, String> connectionContext(SystemdProbeRequest request) {
    return Map.of(
        "adapterHost", nullSafe(request.dbusHost()),
        "adapterPort", Integer.toString(request.dbusPort()),
        "incusInstance", nullSafe(request.nodeName()),
        "nixosHost", nullSafe(request.imageBuilderHost()),
        "systemBusAddress", busAddress(request));
  }

  private Map<String, String> collectPendingJobs(DBusConnection connection) {
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

  private Map<String, String> collectFailedUnitDetails(Manager manager) {
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

  private Map<String, String> collectPendingDependencies(Manager manager, Target target) {
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

  private void addAll(Set<String> sink, List<String> values) {
    if (values == null) {
      return;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        sink.add(value);
      }
    }
  }

  private int clampNonNegative(long value) {
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
