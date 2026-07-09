package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The cross-world graft: the host-side mechanism that folds a scenario played in ANOTHER world (an
 * OSGi scion, played in-container) into the host's own runbook. A {@code @NestedSteps} call
 * composes within ONE jGiven engine; a dependency that crosses the world boundary cannot be a
 * nested step — two engines, two {@link ReportModel}s, and a live jGiven object would {@code
 * ClassCastException} on the flat host loader. So the scion world serializes its scenario to JSON
 * (a {@code ScenarioJsonWriter} String — the only thing that crosses the seam), the host {@link
 * #rebuild}s a model of ITS OWN realm from that String, and {@link #graftUnder grafts} the scion
 * steps as the nested children of a host rootstock step.
 *
 * <p>Generic by construction — it speaks only jGiven's report model, never a domain vocabulary — so
 * EVERY domain whose scenario blooms in-container reuses it: cluster, systemd, and the rest. The
 * observability twin of the seed-broker (which carries the crossing's EXECUTION); this carries the
 * crossing's NARRATION. Host-side (this base package is the flat-host half of the engine, never
 * installed as a bundle), because the graft happens where the host owns its runbook.
 *
 * <p>The vocabulary is horticultural, one register end to end: the host sows a seed toward the
 * other world (the seed-broker's {@code sow}), it grows there into a scion (played in-container),
 * and the scion is GRAFTED onto the host tree — onto the host's <em>rootstock step</em>, the step
 * that stands for that crossing and receives the graft.
 *
 * <p>The verdict travels IN the model: if the scion scenario is {@link ExecutionStatus#FAILED}, the
 * rootstock step is marked FAILED and every host step AFTER it is set {@link StepStatus#SKIPPED} —
 * the local fail-fast reproduced across the frontier. One serialized scenario suffices: it carries
 * both the narration (its steps → the sub-tree) and the verdict (its execution status → the
 * propagation).
 */
public final class ScenarioGraft {

  /**
   * Rebuild a host-realm {@link ReportModel} from the serialized runbook JSON a scion world's
   * front-door produced ({@code ScenarioJsonWriter(model).toString()}). {@link ScenarioJsonReader}
   * reads a file, so the String is spilled to a throwaway temp file and read back — no jGiven type
   * crossed live, only the flat JSON. The rebuilt model belongs to THIS classloader, so its steps
   * are graftable into a host runbook.
   */
  public ReportModel rebuild(String runbookJson) {
    try {
      final File tmp = Files.createTempFile("grafted-runbook", ".json").toFile();
      tmp.deleteOnExit();
      Files.writeString(tmp.toPath(), runbookJson);
      return new ScenarioJsonReader().apply(tmp);
    } catch (IOException cause) {
      throw new UncheckedIOException("cannot rebuild the scion runbook model", cause);
    }
  }

  /**
   * Graft {@code scion}'s scenario under the host runbook's step named {@code rootstockStepName}:
   * the scion scenario's steps become that step's nested children (one continuous tree — the
   * operator descends from the seed to the scion root cause), and the scion verdict propagates.
   * When the scion scenario is FAILED, the rootstock step is marked FAILED and every top-level host
   * step after it is set SKIPPED (fail-fast across the frontier).
   *
   * @throws IllegalArgumentException if no top-level host step is named {@code rootstockStepName},
   *     or the scion model carries no scenario — a wiring bug, surfaced loudly rather than silently
   *     grafting nothing.
   */
  public void graftUnder(ReportModel hostTree, String rootstockStepName, ReportModel scion) {
    final ScenarioModel scionScenario = firstScenarioOf(scion);
    final List<StepModel> hostSteps = topLevelStepsOf(hostTree);
    final int rootstockIndex = indexOfStep(hostSteps, rootstockStepName);
    if (rootstockIndex < 0) {
      throw new IllegalArgumentException(
          "no host rootstock step named '" + rootstockStepName + "' to graft under");
    }
    final StepModel rootstock = hostSteps.get(rootstockIndex);

    scionScenario.getScenarioCases().get(0).getSteps().forEach(rootstock::addNestedStep);

    if (scionScenario.getExecutionStatus() == ExecutionStatus.FAILED) {
      rootstock.setStatus(StepStatus.FAILED);
      for (int i = rootstockIndex + 1; i < hostSteps.size(); i++) {
        hostSteps.get(i).setStatus(StepStatus.SKIPPED);
      }
    }
  }

  private static ScenarioModel firstScenarioOf(ReportModel model) {
    if (model.getScenarios().isEmpty()) {
      throw new IllegalArgumentException("the scion model carries no scenario to graft");
    }
    return model.getScenarios().get(0);
  }

  private static List<StepModel> topLevelStepsOf(ReportModel host) {
    return firstScenarioOf(host).getScenarioCases().get(0).getSteps();
  }

  private static int indexOfStep(List<StepModel> steps, String name) {
    for (int i = 0; i < steps.size(); i++) {
      if (name.equals(steps.get(i).getName())) {
        return i;
      }
    }
    return -1;
  }
}
