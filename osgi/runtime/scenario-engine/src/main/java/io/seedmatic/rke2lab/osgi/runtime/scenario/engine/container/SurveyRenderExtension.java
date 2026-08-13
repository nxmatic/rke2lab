package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.ScenarioBase;
import com.tngtech.jgiven.impl.ScenarioExecutor;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.osgi.framework.FrameworkUtil;

/**
 * The RENDER frontier: when the ambient run is surveying, swap the scenario's jGiven executor for a
 * {@link PendingMarkingScenarioExecutor} so every step narrates {@code PENDING} — a survey PRODUCES
 * a pending plan, not a green result. The counterpart of {@link OsgiServiceExtension} on the other
 * axis: that one chooses WHAT touches the world (the collaborator), this one chooses HOW the run
 * READS (plan vs result). Both read the same ambient {@link RunGate} once, via {@link
 * GardeningSelection} — the mode has ONE source.
 *
 * <p>Only SCIONS are surveyed. A bundle-loaded scenario resolves against a registry and can be
 * rendered pending; the host-flat ROOT (not bundle-loaded) is the orchestrator — its {@code
 * sow}/{@code graft} steps genuinely run to place the scions under their crossings, so it renders
 * its real orchestration, never pending. So this is a no-op for a non-bundle-loaded instance, the
 * same opt-in shape {@link OsgiServiceExtension} has for a scenario with no {@code @OsgiService}
 * field.
 *
 * <p>It runs as a {@link TestInstancePostProcessor} — after jGiven's own post-processing set the
 * scenario up, and BEFORE {@code beforeEach} calls {@code startScenario} (which is where jGiven
 * hands the executor its listener, {@code performInitialization} guarded by an {@code initialized}
 * flag still false here). So the swapped executor is the one that receives the listener, and the
 * stage injection is replayed onto it to mirror jGiven exactly.
 */
public final class SurveyRenderExtension implements TestInstancePostProcessor {

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    if (FrameworkUtil.getBundle(testInstance.getClass()) == null) {
      return; // host-flat root: it orchestrates for real, never surveyed
    }
    if (!(testInstance instanceof ScenarioTestBase<?, ?, ?> scenarioTest)) {
      return;
    }
    final GardeningSelection selection =
        GardeningSelection.from(ScenarioRegistry.of(testInstance).optional(RunGate.class));
    if (!selection.surveying()) {
      return; // cultivating: keep jGiven's stock executor, render results normally
    }
    final ScenarioBase scenario = scenarioTest.getScenario();
    // A pure PROBE (SurveyInert) has no surveying collaborator to run against, so its bodies must
    // be
    // SKIPPED (contact nothing); a MATERIALISER's bodies still run against the surveying impl the
    // frontier picked, only the render is rewritten PENDING.
    final ScenarioExecutor executor =
        testInstance instanceof SurveyInert
            ? new SurveyInertScenarioExecutor()
            : new PendingMarkingScenarioExecutor();
    scenario.setExecutor(executor);
    // Replay onto the swapped executor what jGiven's post-processing did on the discarded one, so
    // the executor the body drives is fully set up (both are no-ops for a stage-less scenario
    // instance, but this keeps the swap faithful and future-proof).
    executor.injectStages(testInstance);
    executor.readScenarioState(testInstance);
  }
}
