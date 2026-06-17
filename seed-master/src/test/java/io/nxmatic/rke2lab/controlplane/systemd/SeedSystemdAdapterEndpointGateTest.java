package io.nxmatic.rke2lab.controlplane.systemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.bdd.Observation;
import io.nxmatic.rke2lab.controlplane.bdd.Symptom;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * The live gate's failure contract. {@link io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe}
 * promises a non-ok {@link Observation} carrying the typed {@link Symptom} the doctor routes on —
 * the simulate and fake probes honor it. These tests pin the <em>live</em> gate to the same
 * contract at its two deadlines, so a real {@code pulumi up} consults the doctor instead of
 * aborting on a bare exception (which bypasses the captured observation and silences the runbook).
 *
 * <p>The gate's I/O is injected so the deadline paths are reachable with no real infrastructure and
 * no wall-clock waits: a mutable {@code clock} the failing probe advances past the deadline drives
 * exactly one attempt before the loop exits.
 */
class SeedSystemdAdapterEndpointGateTest {

  // bioskop-master:12434 (the defaults), one-second tolerance so the deadline math is positive.
  private static BootstrapConfig config() {
    return OperatorConfiguration.mandatory()
        .with("readiness", "timeout", "PT1S")
        .asBootstrapConfig();
  }

  // A clock backed by a single mutable value; the injected probe advances it past any deadline.
  private static final class TestClock implements LongSupplier {
    private long now = 0L;

    @Override
    public long getAsLong() {
      return now;
    }

    void jumpPastDeadline() {
      now = Long.MAX_VALUE / 2;
    }
  }

  private static final Consumer<Duration> NO_SLEEP = duration -> {};

  @Test
  void runtime_probe_deadline_yields_a_connection_refused_observation() {
    final TestClock clock = new TestClock();

    // The instance is reachable, so the gate advances to the runtime probe; the runtime probe keeps
    // failing with the dbus "Connection refused" snapshot and advances the clock past the deadline.
    final Function<BootstrapConfig, Optional<String>> reachable = cfg -> Optional.empty();
    final Function<BootstrapConfig, Map<String, Object>> refusedRuntime =
        cfg -> {
          clock.jumpPastDeadline();
          return Map.of(
              "status",
              "execution-error",
              "summary",
              "systemd adapter runtime probe execution error: systemd dbus probe failed at"
                  + " tcp:host=bioskop-master,port=12434: Connection refused");
        };

    final SeedSystemdAdapterEndpointGate gate =
        new SeedSystemdAdapterEndpointGate(clock, NO_SLEEP, refusedRuntime, reachable);

    final Observation observation = gate.ensureReachable(config(), null);

    assertEquals("failed", observation.status());
    assertEquals(Optional.of(Symptom.CONNECTION_REFUSED), observation.symptom());
    assertTrue(
        observation.summary().contains("Connection refused"),
        "summary should preserve the dbus why: " + observation.summary());
  }

  @Test
  void instance_unreachable_deadline_yields_a_timeout_observation() {
    final TestClock clock = new TestClock();

    // The instance never becomes reachable; each attempt advances the clock past the deadline.
    final Function<BootstrapConfig, Optional<String>> neverReachable =
        cfg -> {
          clock.jumpPastDeadline();
          return Optional.of("Instance not found");
        };
    final Function<BootstrapConfig, Map<String, Object>> unusedRuntime =
        cfg -> {
          throw new AssertionError("runtime probe must not run when the instance is unreachable");
        };

    final SeedSystemdAdapterEndpointGate gate =
        new SeedSystemdAdapterEndpointGate(clock, NO_SLEEP, unusedRuntime, neverReachable);

    final Observation observation = gate.ensureReachable(config(), null);

    assertEquals("failed", observation.status());
    assertEquals(Optional.of(Symptom.TIMEOUT), observation.symptom());
    assertTrue(
        observation.summary().contains("did not become reachable"),
        "summary should preserve the why: " + observation.summary());
  }
}
