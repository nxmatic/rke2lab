package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.SeedBroker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;

/**
 * The systemd-adapter verdict decision, played through the full scenario under the launcher with a
 * FAILING injected probe. The {@code Then} throws (status != ok), so jGiven marks the scenario
 * FAILED and the runbook stays exhaustive — the fidelity the fluent chain gives for free (cf.
 * {@code NestedRunbookTest}). The verdict is NOT the scenario status (always FAILED on a failed
 * probe): it is what the phase's {@code onFailure} does with the authority's answer — STOP throws
 * {@code TopicFailure} (propagates out, aborting the seed), CONTINUE_DEGRADED records a degraded
 * observation and returns (the seed continues). The broker is served by a {@link StubConnection} —
 * the decision is stage LOGIC, unit-tested without a Felix; the OSGi wiring that resolves the real
 * broker is proven in {@code SystemdAdapterStageTest}. Replaces the old {@code
 * SystemdAdapterTopic}-based verdict test.
 */
class SystemdAdapterVerdictTest {

  private static final DocumentCodec CODEC = new DocumentCodec();

  @Test
  void aFailedProbeRendersAFailedScenarioWhateverTheVerdict() throws Exception {
    // Fidelity: the probe failed, so the runbook shows a FAILED node — for BOTH verdicts. The
    // verdict changes what happens to the seed (propagate vs continue), never the report status
    // (cf. NestedRunbookTest: a failing phase renders FAILED, downstream phases SKIPPED).
    assertEquals(
        ExecutionStatus.FAILED,
        playWithVerdict(Action.STOP),
        "a stop verdict — the failed probe renders a FAILED scenario");
    assertEquals(
        ExecutionStatus.FAILED,
        playWithVerdict(Action.CONTINUE_DEGRADED),
        "a continue-degraded verdict — the failed probe still renders a FAILED scenario");
  }

  /** Play the scenario with a failing systemd probe and an authority returning {@code action}. */
  private static ExecutionStatus playWithVerdict(Action action) throws Exception {
    final AtomicReference<ReportModel> runbook = new AtomicReference<>();
    final OsgiConnection connection =
        StubConnection.serving(Map.of(SeedBroker.class, brokerReturning(action)));

    new JUnitLauncherCore<Void>()
        .run(
            SystemdAdapterVerdictTest.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              return null;
            },
            store -> {
              final Namespace ns = Namespace.create(HostSeeder.NS.getParts());
              store.put(ns, HostSeeder.HOST_FACTS, sampleFacts());
              store.put(ns, HostSeeder.CONNECTION, connection);
              store.put(ns, HostSeeder.PROBES, FakeSeedProbes.inert());
              store.put(ns, HostSeeder.SYSTEMD_PROBE, FakeSystemdAdapterProbes.connectionRefused());
              store.put(ns, HostSeeder.RUN_MODEL, runbook);
            });

    return runbook.get().getScenarios().get(0).getExecutionStatus();
  }

  /**
   * A broker whose {@code sow(READINESS_VERDICT, …)} returns a verdict Document with {@code
   * action}.
   */
  private static SeedBroker brokerReturning(Action action) {
    return (wanted, seed) ->
        new Document(
            Domain.DOCTOR.slug(),
            Coordinate.READINESS_VERDICT.slug(),
            CODEC.encode(new ReadinessVerdict(action, "test")));
  }

  private static HostFacts sampleFacts() {
    final var cfg = OperatorConfiguration.mandatory();
    return new HostFacts(
        cfg.asBootstrapConfig(),
        cfg.asPolicy(),
        BootstrapOptions.from(cfg.asDto()),
        LiveGate.forRun(RunMode.STANDALONE),
        RunMode.STANDALONE.materialises(),
        new BboxReconciliationOrchestrator(false),
        new ResourceManager(),
        new OutputBuilder(),
        message -> {},
        OnFailure.noop(),
        new ConsultationLog());
  }
}
