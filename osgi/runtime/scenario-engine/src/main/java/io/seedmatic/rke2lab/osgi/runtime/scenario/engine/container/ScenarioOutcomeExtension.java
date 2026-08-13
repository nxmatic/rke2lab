package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.impl.ScenarioHolder;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The OUTBOUND bracket: at the run boundary it PULLS the played scenario's {@link ScenarioOutcome}
 * — the jGiven {@link ReportModel} and any doctor consultations — and seeds it into the launcher
 * session store ({@link ScenarioOutcomeSeed}) for the driver's harvest to read. It replaces the
 * static {@code LAST_RUNBOOK}/{@code LAST_CONSULTATIONS} holders every scenario used to set by
 * hand: one wiring point on {@code @SeedScenario}, no per-scenario scaffolding.
 *
 * <p>It runs on {@link AfterTestExecutionCallback}, which fires EVEN when the test body failed — so
 * the runbook is ALWAYS harvested (failed steps and all), never masked by a "no runbook" NPE. That
 * is the whole point: the {@link ReportModel} is the fail-at-end collector (jGiven defers a failed
 * step's throw to scenario-end, so a FAILED runbook is still complete and, for a fork-B scenario,
 * its consultation still computed). No {@code failFast} decorator is needed — the driver reads the
 * outcome, renders it, and only THEN inspects the runbook's execution status.
 *
 * <p>Ordering: registered on {@code @SeedScenario} AFTER {@link
 * com.tngtech.jgiven.junit5.JGivenExtension}, and Jupiter runs after-callbacks in REVERSE
 * registration order — so this fires BEFORE {@code JGivenExtension.afterTestExecution} removes the
 * scenario from the {@link ScenarioHolder}, i.e. the model is still bound when this reads it (the
 * same read {@link ScenarioCellarExtension} does at its own boundary).
 */
public final class ScenarioOutcomeExtension implements AfterTestExecutionCallback {

  private final ScenarioOutcomeSeed outcomeSeed = new ScenarioOutcomeSeed();

  @Override
  public void afterTestExecution(ExtensionContext context) {
    final ReportModel runbook = ScenarioHolder.get().getScenarioOfCurrentThread().getModel();
    outcomeSeed.put(context, new ScenarioOutcome(runbook, consultationsOf(context)));
  }

  /**
   * The consultations the scenario raised — pulled from the instance when it is a {@link
   * ConsultationSource} (a fork-B scenario that consulted its doctor), else empty (the root,
   * incus-reconcile, manifests: they consult no one).
   */
  private static List<SeedEnvelope> consultationsOf(ExtensionContext context) {
    if (context.getRequiredTestInstance() instanceof ConsultationSource source) {
      return source.consultations();
    }
    return List.of();
  }
}
