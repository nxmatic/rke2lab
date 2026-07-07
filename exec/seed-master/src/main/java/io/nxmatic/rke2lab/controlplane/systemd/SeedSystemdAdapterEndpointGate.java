package io.nxmatic.rke2lab.controlplane.systemd;

import io.nxmatic.rke2lab.controlplane.bdd.ObservationView;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.incus.port.IncusExecRequest;
import io.nxmatic.rke2lab.incus.port.IncusInstanceContact;
import io.nxmatic.rke2lab.world.gateway.port.SymptomKind;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Pulumi-side gate that waits for the dbus-on-TCP probe to report ok.
 *
 * <p>Both external axes are now OSGi services resolved from the registry: the dbus runtime status
 * ({@code SystemdRuntimeProbe}) and the instance-reachability contact ({@code
 * IncusInstanceContact}, the incus-edge). This gate holds no host I/O — it composes the two
 * injected contacts and owns the orchestration (the retry loop, the deadline, the {@link
 * ObservationView} projection).
 */
public final class SeedSystemdAdapterEndpointGate {

  private static final String API_VERSION = "rke2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterEndpointGateStatus";
  // Adaptive retry intervals based on bootstrap phase
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_EARLY = Duration.ofSeconds(15);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_MID = Duration.ofSeconds(8);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_LATE = Duration.ofSeconds(3);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_FINAL = Duration.ofSeconds(2);
  private static final long PHASE_EARLY_CUTOFF_SECONDS = 90; // Image building / first boot
  private static final long PHASE_MID_CUTOFF_SECONDS = 150; // Systemd initialization
  private static final long PHASE_LATE_CUTOFF_SECONDS = 210; // Service convergence
  private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(15);
  private static final Duration INSTANCE_READY_RETRY_INTERVAL = Duration.ofSeconds(2);

  // Injected collaborators: the gate's real-world I/O (wall clock, sleep, the dbus runtime probe,
  // the incus-exec reachability check). live() wires the live ones; tests substitute fakes so
  // the deadline paths are reachable without real infrastructure or wall-clock waits.
  private final LongSupplier nanoClock;
  private final Consumer<Duration> sleeper;
  private final Function<BootstrapConfig, Map<String, Object>> runtimeProbe;
  private final Function<BootstrapConfig, Optional<String>> instanceReachability;

  SeedSystemdAdapterEndpointGate(
      LongSupplier nanoClock,
      Consumer<Duration> sleeper,
      Function<BootstrapConfig, Map<String, Object>> runtimeProbe,
      Function<BootstrapConfig, Optional<String>> instanceReachability) {
    this.nanoClock = nanoClock;
    this.sleeper = sleeper;
    this.runtimeProbe = runtimeProbe;
    this.instanceReachability = instanceReachability;
  }

  /**
   * The live gate, wired to the real wall clock and sleep, the dbus runtime probe, and the
   * incus-edge instance-reachability contact — both resolved from the OSGi registry by the caller
   * and passed in. The gate projects the host {@link BootstrapConfig} into the flat {@link
   * IncusExecRequest} the contact takes.
   */
  public static SeedSystemdAdapterEndpointGate live(
      SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus, IncusInstanceContact instanceContact) {
    return new SeedSystemdAdapterEndpointGate(
        System::nanoTime,
        SeedSystemdAdapterEndpointGate::sleep,
        runtimeStatus::snapshot,
        config -> instanceContact.isReachable(execRequestFrom(config)));
  }

  private static IncusExecRequest execRequestFrom(BootstrapConfig config) {
    return new IncusExecRequest(
        config.imageBuilderHost(), config.incusProject(), config.nodeName());
  }

  public static ObservationView deferredPreview(BootstrapConfig config) {
    return ObservationView.of(
        "deferred-preview",
        Optional.empty(),
        "adapter endpoint gate deferred during preview",
        details(
            Map.of(
                "source",
                "systemd-adapter-endpoint-gate",
                "probeMode",
                "systemd-adapter-runtime")));
  }

  /**
   * Wait for the dbus-on-TCP adapter to report ok, returning the captured {@link ObservationView}.
   * The contract the {@code SystemdAdapterProbe} interface promises: a reachable adapter yields an
   * ok observation; a deadline that the instance never came up, or that dbus never answered, yields
   * a non-ok observation carrying the typed {@link SymptomKind} — never a bare exception. The
   * symptom and the last summary's "why" flow into the captured observation so the doctor is
   * consulted on a real {@code pulumi up}, not only in preview-simulate.
   */
  public ObservationView ensureReachable(BootstrapConfig config, Consumer<String> logger) {
    final Optional<ObservationView> instanceFailure = waitForInstanceReachable(config, logger);
    if (instanceFailure.isPresent()) {
      return instanceFailure.get();
    }

    return waitForRuntimeProbe(config, logger);
  }

  private ObservationView waitForRuntimeProbe(BootstrapConfig config, Consumer<String> logger) {
    final Duration tolerance = config.readinessTimeout();
    final long startedAt = nanoClock.getAsLong();
    final long deadlineNanos = startedAt + tolerance.toNanos();
    long nextProgressLogAt = startedAt;

    Map<String, Object> lastSnapshot = Map.of();
    while (nanoClock.getAsLong() < deadlineNanos) {
      final Map<String, Object> runtimeSnapshot = runtimeProbe.apply(config);
      final String runtimeStatus =
          String.valueOf(runtimeSnapshot.getOrDefault("status", "unknown")).trim();

      if ("ok".equalsIgnoreCase(runtimeStatus)) {
        return reachableObservation(config, runtimeSnapshot, logger);
      }

      lastSnapshot = runtimeSnapshot;

      final long now = nanoClock.getAsLong();
      if (logger != null && now >= nextProgressLogAt) {
        logger.accept(
            "systemd adapter runtime probe not ready yet; status="
                + runtimeStatus
                + ", summary="
                + runtimeSnapshot.getOrDefault("summary", "n/a")
                + " (retrying for up to "
                + tolerance
                + ")");
        nextProgressLogAt = now + PROGRESS_LOG_INTERVAL.toNanos();
      }

      // Adaptive retry interval: slower during early boot, faster as we approach readiness
      final long elapsedSeconds = Duration.ofNanos(now - startedAt).toSeconds();
      final Duration retryInterval = computeRuntimeProbeRetryInterval(elapsedSeconds);
      sleeper.accept(retryInterval);
    }

    final String lastSummary = String.valueOf(lastSnapshot.getOrDefault("summary", "unknown"));
    final String lastStatus = String.valueOf(lastSnapshot.getOrDefault("status", "unknown"));
    final String summary =
        "Adapter runtime probe failed at "
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " after "
            + tolerance
            + " (last status="
            + lastStatus
            + ", summary="
            + lastSummary
            + ")";
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }
    // A refused/unanswered dbus deadline is a CONNECTION_REFUSED symptom, not a bare throw: the
    // snapshot stamps status=ok the instant dbus answers, so reaching this deadline means the
    // connection itself never succeeded. The last summary carries the dbus "why" — preserved in
    // details so the doctor consults and the runbook stays loquacious. The endpoint + node are
    // promoted to FLAT details keys (the gate holds them host-side, from config) so the pure
    // dbus-tcp specialist reads them off the observation without reaching back to BootstrapConfig
    // and without spelunking the nested adapterStatus map.
    return ObservationView.failed(
        SymptomKind.CONNECTION_REFUSED,
        summary,
        details(
            Map.of(
                "source",
                "systemd-adapter-endpoint-gate",
                "probeMode",
                "systemd-adapter-runtime",
                "adapterHost",
                config.systemdAdapterDbusHost(),
                "adapterPort",
                Integer.toString(config.systemdAdapterDbusPort()),
                "nodeName",
                config.nodeName(),
                "adapterStatus",
                Map.copyOf(lastSnapshot))));
  }

  private ObservationView reachableObservation(
      BootstrapConfig config, Map<String, Object> runtimeSnapshot, Consumer<String> logger) {
    final String runtimeStatus = String.valueOf(runtimeSnapshot.getOrDefault("status", "unknown"));
    final String summary =
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status="
            + runtimeStatus
            + " probeMode=systemd-adapter-runtime";
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }
    return ObservationView.ok(
        summary,
        details(
            Map.of(
                "source",
                "systemd-adapter-endpoint-gate",
                "probeMode",
                "systemd-adapter-runtime",
                "adapterStatus",
                Map.copyOf(runtimeSnapshot))));
  }

  /**
   * Compute adaptive retry interval based on bootstrap phase. Early boot (image building, first
   * boot): slower checks to reduce CPU load. Later phases (service convergence): faster checks for
   * responsiveness.
   */
  private static Duration computeRuntimeProbeRetryInterval(long elapsedSeconds) {
    if (elapsedSeconds < PHASE_EARLY_CUTOFF_SECONDS) {
      // Phase 1: Image building / first boot - check every 15s
      return RUNTIME_PROBE_RETRY_INTERVAL_EARLY;
    } else if (elapsedSeconds < PHASE_MID_CUTOFF_SECONDS) {
      // Phase 2: Systemd initialization - check every 8s
      return RUNTIME_PROBE_RETRY_INTERVAL_MID;
    } else if (elapsedSeconds < PHASE_LATE_CUTOFF_SECONDS) {
      // Phase 3: Service convergence - check every 3s
      return RUNTIME_PROBE_RETRY_INTERVAL_LATE;
    } else {
      // Phase 4: Final readiness - check every 2s
      return RUNTIME_PROBE_RETRY_INTERVAL_FINAL;
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  // Wait for the seed instance to be reachable via `incus exec`. Pulumi
  // registers the instance resource concurrently with this Main-driven gate
  // call, so on first apply the instance may not yet exist when ensureReachable
  // runs. Retry the cheapest no-op probe until incus exec succeeds.
  private Optional<ObservationView> waitForInstanceReachable(
      BootstrapConfig config, Consumer<String> logger) {
    final Duration tolerance = config.readinessTimeout();
    final long startedAt = nanoClock.getAsLong();
    final long deadlineNanos = startedAt + tolerance.toNanos();
    long nextProgressLogAt = startedAt;
    String lastFailureSummary = null;
    while (nanoClock.getAsLong() < deadlineNanos) {
      final Optional<String> failure = instanceReachability.apply(config);
      if (failure.isEmpty()) {
        return Optional.empty();
      }
      lastFailureSummary = failure.get();
      final long now = nanoClock.getAsLong();
      if (logger != null && now >= nextProgressLogAt) {
        logger.accept(
            "instance "
                + config.nodeName()
                + " in project "
                + config.incusProject()
                + " not reachable yet via incus exec; "
                + lastFailureSummary
                + " (retrying for up to "
                + tolerance
                + ")");
        nextProgressLogAt = now + PROGRESS_LOG_INTERVAL.toNanos();
      }
      sleeper.accept(INSTANCE_READY_RETRY_INTERVAL);
    }
    final String summary =
        "Instance "
            + config.nodeName()
            + " in project "
            + config.incusProject()
            + " did not become reachable via incus exec within "
            + tolerance
            + " (last result: "
            + (lastFailureSummary == null ? "<no attempts>" : lastFailureSummary)
            + ")";
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }
    // The instance never came up within tolerance. Distinct from a refused dbus port: the infra
    // isn't there yet, so this is a TIMEOUT symptom (no dedicated "instance-not-found" kind), still
    // carrying the why so the doctor consults rather than the gate throwing past the captured slot.
    return Optional.of(
        ObservationView.failed(
            SymptomKind.TIMEOUT,
            summary,
            details(
                Map.of(
                    "source",
                    "systemd-adapter-endpoint-gate",
                    "probeMode",
                    "systemd-adapter-runtime"))));
  }

  /**
   * The gate's resource-identity metadata ({@code apiVersion}/{@code kind}) merged ahead of the
   * call-site details, forming the {@link ObservationView}'s details map. {@code status}/{@code
   * summary} are the observation's own fields and are re-added by {@link
   * ObservationView#toOutputMap()}, so the flat output keys are unchanged from the former envelope.
   */
  private static Map<String, Object> details(Map<String, Object> callerDetails) {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("apiVersion", API_VERSION);
    map.put("kind", KIND);
    if (callerDetails != null) {
      map.putAll(callerDetails);
    }
    return map;
  }
}
