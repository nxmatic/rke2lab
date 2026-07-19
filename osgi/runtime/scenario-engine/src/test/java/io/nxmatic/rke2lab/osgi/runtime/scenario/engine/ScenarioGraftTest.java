package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ScenarioGraft} — the cross-world graft — in production, not in the bench spike. Two
 * scenarios are played to yield real {@link ReportModel}s: a HOST runbook with a named rootstock
 * step, and a REMOTE rootstock. The scion's model crosses as a {@code ScenarioJsonWriter} String
 * (the only thing the seam carries), the graft {@link ScenarioGraft#rebuild rebuilds} it in this
 * realm and {@link ScenarioGraft#graftUnder grafts} it under the rootstock. What is asserted is the
 * spec's E5 shape (one continuous tree) + the fail-fast across the frontier (P2).
 */
class ScenarioGraftTest {

  private static final String ROOTSTOCK = "the scion world is consulted";

  private final ScenarioGraft graft = new ScenarioGraft();

  @Test
  void a_healthy_scion_grafts_as_a_subtree_and_the_host_continues() {
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_runs_green());
    final ReportModel host = playHost();

    // What actually crosses is a String; rebuild it in this realm before grafting.
    final String crossed = new ScenarioJsonWriter(scion).toString();
    assertFalse(crossed.isBlank(), "the scion scenario serializes to cross the seam");

    graft.graftUnder(host.getScenarios().get(0), host, ROOTSTOCK, graft.rebuild(crossed));

    final StepModel rootstock = stepNamed(host, ROOTSTOCK);
    assertFalse(
        rootstock.getNestedSteps().isEmpty(), "the scion steps grafted under the rootstock");
    assertEquals(
        StepStatus.PASSED, stepAfter(host, ROOTSTOCK).getStatus(), "the host phase after ran");
  }

  @Test
  void a_failing_scion_fails_the_rootstock_and_skips_the_host_downstream() {
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_fails());
    final ReportModel host = playHost();

    assertEquals(
        ExecutionStatus.FAILED,
        scion.getScenarios().get(0).getExecutionStatus(),
        "the scion scenario failed");

    graft.graftUnder(
        host.getScenarios().get(0),
        host,
        ROOTSTOCK,
        graft.rebuild(new ScenarioJsonWriter(scion).toString()));

    assertEquals(
        StepStatus.FAILED, stepNamed(host, ROOTSTOCK).getStatus(), "the scion failure propagates");
    assertEquals(
        StepStatus.SKIPPED,
        stepAfter(host, ROOTSTOCK).getStatus(),
        "the host phase after a failed rootstock is skipped (fail-fast across the frontier)");
  }

  @Test
  void a_scion_tag_rides_up_with_the_graft_and_the_host_reads_it_back() {
    // The ephemeral cellar: a scion poses a within-run fact on its model; the graft merges its tag
    // map into the host tree, and the host reads it back through the mechanism — not by hand.
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_runs_green());
    scion.addTag(GraftTag.LIVE_ROOT.of("/x/.local.d/bioskop/master/host.live.d"));
    final ReportModel host = playHost();

    // Round-trip across the seam (serialize → rebuild) before grafting, as production does.
    graft.graftUnder(
        host.getScenarios().get(0),
        host,
        ROOTSTOCK,
        graft.rebuild(new ScenarioJsonWriter(scion).toString()));

    assertEquals(
        Optional.of("/x/.local.d/bioskop/master/host.live.d"),
        graft.graftedValue(host, GraftTag.LIVE_ROOT),
        "the scion's tag survived the seam and merged into the host tree");
  }

  @Test
  void a_missing_scion_tag_reads_back_empty() {
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_runs_green());
    final ReportModel host = playHost();

    graft.graftUnder(
        host.getScenarios().get(0),
        host,
        ROOTSTOCK,
        graft.rebuild(new ScenarioJsonWriter(scion).toString()));

    assertEquals(
        Optional.empty(),
        graft.graftedValue(host, GraftTag.LIVE_ROOT),
        "a scion that posed no tag reads back empty, not a crash");
  }

  @Test
  void an_unknown_rootstock_step_is_a_loud_wiring_bug() {
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_runs_green());
    final ReportModel host = playHost();
    final ReportModel rebuilt = graft.rebuild(new ScenarioJsonWriter(scion).toString());

    assertThrows(
        IllegalArgumentException.class,
        () -> graft.graftUnder(host.getScenarios().get(0), host, "no such step", rebuilt),
        "grafting under a missing rootstock step fails loudly, not silently");
  }

  @Test
  void a_scion_grafts_into_the_live_scenario_when_the_report_model_has_no_scenario_yet() {
    // The live condition every other test missed: a host WHEN step grafts MID-run, and jGiven does
    // not append the current scenario to its ReportModel until the scenario FINISHES — so
    // getModel().getScenarios() is empty here, while the current ScenarioModel already carries the
    // rootstock step. The graft must target that live ScenarioModel, not the empty ReportModel;
    // fishing the host scenario out of the ReportModel was the live "no scenario to graft".
    final ScenarioModel hostScenario = playHost().getScenarios().get(0);
    final ReportModel emptyReportModel = new ReportModel(); // scenarios still empty, as mid-run
    final ReportModel scion = play(ScionStage.class, r -> r.the_scion_scenario_runs_green());

    graft.graftUnder(
        hostScenario,
        emptyReportModel,
        ROOTSTOCK,
        graft.rebuild(new ScenarioJsonWriter(scion).toString()));

    final StepModel rootstock =
        hostScenario.getScenarioCases().get(0).getSteps().stream()
            .filter(s -> ROOTSTOCK.equals(s.getName()))
            .findFirst()
            .orElseThrow();
    assertFalse(
        rootstock.getNestedSteps().isEmpty(),
        "the scion grafts into the live ScenarioModel even though the ReportModel holds no scenario"
            + " yet (the live mid-run condition)");
  }

  /** The host runbook: a rootstock step (the crossing) followed by a downstream phase. */
  private static ReportModel playHost() {
    return play(HostStage.class, h -> h.the_scion_is_grafted().the_host_finishes());
  }

  /**
   * Play a standalone jGiven scenario to its {@link ReportModel}, the way a checkpoint does (raw
   * {@code Scenario.create}, not the JUnit runner). {@code finished()} throws on the failing path
   * but has already flushed the FAILED scenario into the model, so it is swallowed.
   */
  private static <T extends Stage<T>> ReportModel play(Class<T> stageType, Consumer<T> body) {
    final ReportModel model = new ReportModel();
    model.setClassName(stageType.getSimpleName());
    final Scenario<T, T, T> scenario = Scenario.create(stageType);
    scenario.setModel(model);
    scenario.startScenario("scenario");
    body.accept(scenario.getGivenStage());
    try {
      scenario.finished();
    } catch (Throwable diagnosed) {
      // the failing path throws; finished() has already flushed the FAILED scenario into the model.
    }
    return model;
  }

  private static StepModel stepNamed(ReportModel model, String name) {
    return model.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .filter(s -> name.equals(s.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static StepModel stepAfter(ReportModel model, String name) {
    final var steps = model.getScenarios().get(0).getScenarioCases().get(0).getSteps();
    for (int i = 0; i < steps.size() - 1; i++) {
      if (name.equals(steps.get(i).getName())) {
        return steps.get(i + 1);
      }
    }
    throw new IllegalStateException("no step after " + name);
  }

  /** The host-world stages: a crossing step, then a downstream phase. */
  public static class HostStage extends Stage<HostStage> {
    @As("the scion world is consulted")
    public HostStage the_scion_is_grafted() {
      return self();
    }

    @As("the host finishes")
    public HostStage the_host_finishes() {
      return self();
    }
  }

  /** The scion-world stage: the rootstock, green or failing. */
  public static class ScionStage extends Stage<ScionStage> {
    @As("the scion scenario runs green")
    public ScionStage the_scion_scenario_runs_green() {
      return self();
    }

    @As("the scion scenario fails")
    public ScionStage the_scion_scenario_fails() {
      throw new AssertionError("the scion rootstock diagnosed a symptom");
    }
  }
}
