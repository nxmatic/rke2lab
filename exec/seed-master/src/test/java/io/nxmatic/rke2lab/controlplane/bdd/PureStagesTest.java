package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;
import org.osgi.framework.BundleContext;

/**
 * The three pure phases play in DAG order under the launcher: preflight gates, bbox reconciles,
 * incus provisions. Driven with INERT fakes (no git/bbox/incus touched) and a connection whose
 * framework the fakes never call — a pure-jGiven test, no Felix, proving the phase composition and
 * the injected-probe seam. The runbook is inject-the-model (the driver holds the reference).
 */
class PureStagesTest {

  @Test
  void the_three_pure_phases_play_in_dag_order() throws Exception {
    final ReportModel runbook = new ReportModel();
    final HostFacts facts = sampleFacts();
    final OsgiConnection connection = attachedToUnusedFramework();

    new JUnitLauncherCore<Void>()
        .run(
            getClass().getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              return null;
            },
            store -> {
              final Namespace ns = Namespace.create(HostSeeder.NS.getParts());
              store.put(ns, HostSeeder.HOST_FACTS, facts);
              store.put(ns, HostSeeder.CONNECTION, connection);
              store.put(ns, HostSeeder.PROBES, FakeSeedProbes.inert());
              store.put(ns, HostSeeder.RUN_MODEL, runbook);
            });

    assertEquals(1, runbook.getScenarios().size(), "one scenario played into the driver's model");
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());

    // The three phases compose as @NestedSteps: each is one root step (preflight / bbox / incus)
    // carrying its inner phase step. Assert the tree — the root names in DAG order, and that each
    // root actually nests its phase body (the composition, not just three flat lines).
    final List<StepModel> roots =
        runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps();
    assertEquals(
        List.of("preflight", "bbox", "incus"),
        roots.stream().map(StepModel::getName).toList(),
        "the three phases narrate as root steps in DAG order");
    roots.forEach(
        root ->
            assertEquals(
                false,
                root.getNestedSteps().isEmpty(),
                root.getName() + " root nests its phase step (the @NestedSteps composition)"));
  }

  /**
   * A connection over a context whose getBundle(0) returns a Framework the inert fakes never use.
   */
  private static OsgiConnection attachedToUnusedFramework() {
    final BundleContext context =
        (BundleContext)
            Proxy.newProxyInstance(
                PureStagesTest.class.getClassLoader(),
                new Class<?>[] {BundleContext.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getBundle") && args != null && args.length == 1) {
                    return Proxy.newProxyInstance(
                        PureStagesTest.class.getClassLoader(),
                        new Class<?>[] {org.osgi.framework.launch.Framework.class},
                        (p, m, a) -> {
                          throw new UnsupportedOperationException(
                              "inert test: the framework is never called");
                        });
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return OsgiConnection.over(context, false, () -> {});
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
