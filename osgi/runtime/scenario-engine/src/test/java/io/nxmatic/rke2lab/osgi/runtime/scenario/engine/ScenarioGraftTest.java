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
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ScenarioGraft} — the cross-world graft — in production, not in the bench spike. Two
 * scenarios are played to yield real {@link ReportModel}s: a HOST runbook with a named gateway
 * step, and a REMOTE bloom. The remote's model crosses as a {@code ScenarioJsonWriter} String (the
 * only thing the seam carries), the graft {@link ScenarioGraft#rebuild rebuilds} it in this realm
 * and {@link ScenarioGraft#graftUnder grafts} it under the gateway. What is asserted is the spec's
 * E5 shape (one continuous tree) + the fail-fast across the frontier (P2).
 */
class ScenarioGraftTest {

  private static final String GATEWAY = "the remote world is consulted";

  private final ScenarioGraft graft = new ScenarioGraft();

  @Test
  void a_healthy_remote_grafts_as_a_subtree_and_the_host_continues() {
    final ReportModel remote = play(RemoteStage.class, r -> r.the_remote_scenario_runs_green());
    final ReportModel host = playHost();

    // What actually crosses is a String; rebuild it in this realm before grafting.
    final String crossed = new ScenarioJsonWriter(remote).toString();
    assertFalse(crossed.isBlank(), "the remote scenario serializes to cross the seam");

    graft.graftUnder(host, GATEWAY, graft.rebuild(crossed));

    final StepModel gateway = stepNamed(host, GATEWAY);
    assertFalse(gateway.getNestedSteps().isEmpty(), "the remote steps grafted under the gateway");
    assertEquals(
        StepStatus.PASSED, stepAfter(host, GATEWAY).getStatus(), "the host phase after ran");
  }

  @Test
  void a_failing_remote_fails_the_gateway_and_skips_the_host_downstream() {
    final ReportModel remote = play(RemoteStage.class, r -> r.the_remote_scenario_fails());
    final ReportModel host = playHost();

    assertEquals(
        ExecutionStatus.FAILED,
        remote.getScenarios().get(0).getExecutionStatus(),
        "the remote scenario failed");

    graft.graftUnder(host, GATEWAY, graft.rebuild(new ScenarioJsonWriter(remote).toString()));

    assertEquals(
        StepStatus.FAILED, stepNamed(host, GATEWAY).getStatus(), "the remote failure propagates");
    assertEquals(
        StepStatus.SKIPPED,
        stepAfter(host, GATEWAY).getStatus(),
        "the host phase after a failed gateway is skipped (fail-fast across the frontier)");
  }

  @Test
  void an_unknown_gateway_step_is_a_loud_wiring_bug() {
    final ReportModel remote = play(RemoteStage.class, r -> r.the_remote_scenario_runs_green());
    final ReportModel host = playHost();
    final ReportModel rebuilt = graft.rebuild(new ScenarioJsonWriter(remote).toString());

    assertThrows(
        IllegalArgumentException.class,
        () -> graft.graftUnder(host, "no such step", rebuilt),
        "grafting under a missing gateway step fails loudly, not silently");
  }

  /** The host runbook: a gateway step (the crossing) followed by a downstream phase. */
  private static ReportModel playHost() {
    return play(HostStage.class, h -> h.the_remote_world_is_consulted().the_host_finishes());
  }

  /**
   * Play a standalone jGiven scenario to its {@link ReportModel}, the way a checkpoint does (raw
   * {@code Scenario.create}, not the JUnit runner). {@code finished()} throws on the failing path
   * but has already flushed the FAILED scenario into the model, so it is swallowed.
   */
  private static <T extends Stage<T>> ReportModel play(
      Class<T> stageType, java.util.function.Consumer<T> body) {
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
    @As("the remote world is consulted")
    public HostStage the_remote_world_is_consulted() {
      return self();
    }

    @As("the host finishes")
    public HostStage the_host_finishes() {
      return self();
    }
  }

  /** The remote-world stage: the bloom, green or failing. */
  public static class RemoteStage extends Stage<RemoteStage> {
    @As("the remote scenario runs green")
    public RemoteStage the_remote_scenario_runs_green() {
      return self();
    }

    @As("the remote scenario fails")
    public RemoteStage the_remote_scenario_fails() {
      throw new AssertionError("the remote bloom diagnosed a symptom");
    }
  }
}
