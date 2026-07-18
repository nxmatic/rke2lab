package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.junit.testkit.Pipeline;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The committed acceptance for the engine-lifecycle socle (plan Task 7) — the increment-2 go/no-go,
 * kept as a permanent regression. It drives the WHOLE socle end-to-end: {@link JUnitLauncherCore}
 * discovers and plays {@link BulletproofPipeline}, whose {@code @SeedRuntime} discipline connects
 * to a live world ({@link OsgiConnection}) and holds it at the bundle level ({@link
 * StartLevelLever}), and the run yields a jGiven {@code ReportModel}. Green here means increment 2
 * is substituting real phases for the placeholders. First consumer of {@link Pipeline}.
 *
 * <p>The engine test module stages no bundles, so {@code OsgiConnection.embedded()} cannot boot
 * here (increment 2's exec exercises it for real). Instead this driver boots a real Felix via the
 * testkit and hands the pipeline a non-owning connection over it through {@link
 * LaunchedPipelineExchange} — bound on {@code JUnitLauncherCore}'s worker thread, the one thread
 * the whole play runs on. The driver injects the {@code ReportModel} and reads its own reference
 * back (the inject-the-model idiom), so nothing is captured across the membrane.
 */
@OsgiWorld
@Pipeline
class SoclePipelineTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder().withScr().build();

  @Test
  void the_socle_plays_a_connected_pipeline_and_yields_a_two_tier_runbook()
      throws InterruptedException {
    // Non-owning: the testkit booted the world and keeps its teardown.
    final OsgiConnection connection = OsgiConnection.over(felix.context(), false, () -> {});
    final ReportModel runbook = new ReportModel();

    final ReportModel played =
        new JUnitLauncherCore<ReportModel>()
            .run(
                getClass().getClassLoader(),
                JupiterTestEngine.class,
                wiring -> List.of(DiscoverySelectors.selectClass(BulletproofPipeline.class)),
                (launcher, request, sessionStore) -> {
                  // Bind the exchange on THIS worker thread — the same thread the pipeline plays
                  // on, so its ConnectionSeeder and connect step resolve to this connection +
                  // model.
                  try (LaunchedPipelineExchange exchange =
                      LaunchedPipelineExchange.bind(connection, runbook)) {
                    launcher.execute(request);
                    return exchange.runbook();
                  }
                });

    // The launcher played the scenario into the injected model — one scenario, green.
    assertEquals(1, played.getScenarios().size(), "the launcher played exactly one scenario");
    final ScenarioModel scenario = played.getScenarios().get(0);
    assertEquals(
        ExecutionStatus.SUCCESS,
        scenario.getExecutionStatus(),
        "the connected, disciplined pipeline ran green");

    // Two top-level steps: the connect step, then the reconcile step.
    final List<StepModel> steps = scenario.getScenarioCases().get(0).getSteps();
    assertEquals(2, steps.size(), "the scenario has two top-level steps");
    assertEquals("the OSGi world is connected", steps.get(0).getName());
    assertEquals("two placeholder units are reconciled", steps.get(1).getName());

    // The second step carries the two nested sub-steps — the two-tier runbook tree.
    final List<StepModel> nested = steps.get(1).getNestedSteps();
    assertEquals(2, nested.size(), "the reconcile step carries two nested sub-steps");
    assertEquals("the first unit is reconciled", nested.get(0).getName());
    assertEquals("the second unit is reconciled", nested.get(1).getName());

    // The connect step observed a LIVE embedded Felix (asserted inside the step); its PASSED status
    // is the proof it ran against an ACTIVE system bundle rather than being skipped.
    assertTrue(
        scenario.getScenarioCases().get(0).getStep(0).getStatus().name().equals("PASSED"),
        "the connect step passed — it observed a live world");
  }
}
