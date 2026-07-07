package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;

/**
 * The full seed scenario plays OFFLINE under the launcher: preflight gates, bbox reconciles, incus
 * provisions, systemd adapter launches — all four phases in DAG order. The three pure phases run
 * INERT fakes ({@link FakeSeedProbes}); the systemd phase runs an INJECTED reachable probe (its own
 * channel, {@link HostSeeder#SYSTEMD_PROBE}) so it plays without the live gate's host-side {@code
 * incus exec}. None touches the world, and the systemd phase short-circuits {@code liveProbe()}, so
 * the connection's framework is never called — a pure-jGiven test, no Felix. The registry-resolved
 * path (real fakes in a Felix) is proven separately by {@code SystemdAdapterStageTest}. The runbook
 * is inject-the-model (the driver holds the reference).
 */
class PureStagesTest {

  @Test
  void the_full_seed_scenario_plays_offline_in_dag_order() throws Exception {
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
              store.put(ns, HostSeeder.SYSTEMD_PROBE, FakeSeedProbes.reachableSystemdAdapter());
              store.put(ns, HostSeeder.RUN_MODEL, runbook);
            });

    assertEquals(1, runbook.getScenarios().size(), "one scenario played into the driver's model");
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());

    // The four phases compose as @NestedSteps: each is one root step carrying its inner phase step.
    // Assert the tree — the root names in DAG order, and that each root actually nests its phase
    // body (the composition, not just four flat lines).
    final List<StepModel> roots =
        runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps();
    assertEquals(
        List.of("preflight", "bbox", "incus", "systemd adapter"),
        roots.stream().map(StepModel::getName).toList(),
        "the four phases narrate as root steps in DAG order");
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
                        new Class<?>[] {Framework.class},
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
        BootstrapOptions.from(cfg.asDto()),
        LiveGate.forRun(RunMode.STANDALONE),
        new BboxReconciliationOrchestrator(false),
        new ResourceManager(),
        new OutputBuilder(),
        message -> {},
        OnFailure.noop(),
        new ConsultationLog());
  }
}
