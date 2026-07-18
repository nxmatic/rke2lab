package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;

/**
 * The whole product of a played scenario the OUTBOUND channel carries back to its driver — the
 * runbook the run wove and the doctor consultations it raised. It replaces the pair of static
 * {@code AtomicReference} holders every scenario used to expose ({@code LAST_RUNBOOK} + {@code
 * LAST_CONSULTATIONS}): {@link ScenarioOutcomeExtension} builds ONE of these at the run boundary
 * and seeds it into the launcher session store, and the harvest reads it back ({@link
 * ScenarioOutcomeSeed}).
 *
 * <p>The {@link #runbook} is the LIVE jGiven {@link ReportModel} — safe to carry as an object
 * because the store hop stays IN-REALM (the extension writes and the harvest reads on the same
 * worker thread, same session, same loader); nothing crosses the dual-realm membrane here. Only the
 * front-door's later {@code ScenarioJsonWriter} serialisation crosses scion→host. The {@link
 * #consultations} default to an empty list (a run that consulted no one), so the record is never
 * partial.
 */
public record ScenarioOutcome(ReportModel runbook, List<SeedEnvelope> consultations) {

  public ScenarioOutcome {
    consultations = List.copyOf(consultations);
  }

  /**
   * An outcome with a runbook and no consultations — the non-fork-B case (a run that consults no
   * one).
   */
  public static ScenarioOutcome of(ReportModel runbook) {
    return new ScenarioOutcome(runbook, List.of());
  }
}
