package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;

/**
 * Drives the empty ClusterSeedScenario through the launcher: seeds HostFacts AND the driver's own
 * ReportModel into the session store, discovery-selects the scenario, plays it. The runbook is not
 * harvested back — the driver holds the model reference it seeded, jGiven writes the run into it,
 * so after the run the driver reads its own reference. Proves the harness (seeder + JGivenExtension
 * ordering, HostFactsAware + RunbookAware, inject-the-model) before any phase exists. Pure jGiven —
 * no Felix yet.
 */
class ClusterSeedScenarioSkeletonTest {

  @Test
  void the_empty_scenario_plays_green_through_the_launcher() throws Exception {
    final HostFacts facts = sampleFacts();
    final ReportModel runbook = new ReportModel();

    new JUnitLauncherCore<Void>()
        .run(
            getClass().getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              return null;
            },
            // The launcher hands a store keyed by the platform Namespace; the seeder reads it back
            // through a Jupiter Namespace, which jGiven's engine converts by its parts — so we seed
            // under the same parts to land in the namespace the seeder resolves.
            store -> {
              final Namespace ns = Namespace.create(HostSeeder.NS.getParts());
              store.put(ns, HostSeeder.HOST_FACTS, facts);
              store.put(ns, HostSeeder.RUN_MODEL, runbook);
            });

    assertEquals(1, runbook.getScenarios().size(), "one scenario played into the driver's model");
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());
  }

  private static HostFacts sampleFacts() {
    final var cfg = OperatorConfiguration.mandatory();
    return new HostFacts(
        cfg.asBootstrapConfig(),
        cfg.asPolicy(),
        io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions.from(cfg.asDto()),
        io.nxmatic.rke2lab.pulumi.edge.LiveGate.forRun(
            io.nxmatic.rke2lab.pulumi.edge.RunMode.STANDALONE),
        new io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator(false),
        new io.nxmatic.rke2lab.controlplane.resources.ResourceManager(),
        new io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder(),
        message -> {},
        io.nxmatic.rke2lab.pipeline.OnFailure.noop(),
        new io.nxmatic.rke2lab.doctor.port.ConsultationLog());
  }
}
