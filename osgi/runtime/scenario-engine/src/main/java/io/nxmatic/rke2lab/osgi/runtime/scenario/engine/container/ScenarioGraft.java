package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioCaseModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import com.tngtech.jgiven.report.model.Tag;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * crossing's NARRATION. In the engine's EXPORTED {@code .container} package because the graft runs
 * wherever a runbook is owned: host-side ({@code Main} folds the scions into the root runbook on
 * the flat classpath) AND in-container (a scion that itself consults a sub-scion — the incus
 * provisioning scion grafts the manifests scion under its own step — folds it in-world). Stateless,
 * and only flat JSON crosses the realm, so no live jGiven object is ever shared between loaders.
 *
 * <p>The vocabulary is horticultural, one register end to end: the host sows a seed toward the
 * other world (the seed-broker's {@code sow}), it grows there into a scion (played in-container),
 * and the scion is GRAFTED onto the host tree — onto the host's <em>rootstock step</em>, the step
 * that stands for that crossing and receives the graft.
 *
 * <p>The verdict travels IN the model: if the scion scenario is {@link ExecutionStatus#FAILED}, the
 * rootstock step is marked FAILED, every host step AFTER it is set {@link StepStatus#SKIPPED} (the
 * local fail-fast reproduced across the frontier), and the scion's failure text (the case
 * errorMessage + stackTrace — jGiven holds it at case level, never on a step) is carried onto the
 * host case so the reason survives the crossing. One serialized scenario suffices: it carries the
 * narration (its steps → the sub-tree), the verdict (its execution status → the propagation), and
 * the reason (its case failure → the host case).
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
   * <p>The host side is given as TWO live handles, not one: the crossing happens MID-run (inside a
   * host WHEN step), and jGiven does not append the current {@link ScenarioModel} to its {@link
   * ReportModel#getScenarios()} until the scenario FINISHES — so mid-run that list is still empty.
   * The steps therefore graft into {@code hostScenario} (the live current model, reached by the
   * caller via {@code getScenario().getScenarioModel()}), while the scion's tag map merges into
   * {@code hostTree} (the {@link ReportModel}, whose tag map IS live and which the host reads back
   * through {@link #graftedValue}). Fishing the host scenario out of {@code
   * hostTree.getScenarios()} was the "no scenario to graft" defect — it read empty every live run.
   *
   * @throws IllegalArgumentException if no top-level host step is named {@code rootstockStepName},
   *     or the scion model carries no scenario — a wiring bug, surfaced loudly rather than silently
   *     grafting nothing.
   */
  public void graftUnder(
      ScenarioModel hostScenario,
      ReportModel hostTree,
      String rootstockStepName,
      ReportModel scion) {
    final ScenarioModel scionScenario = firstScenarioOf(scion);
    final List<StepModel> hostSteps = topLevelStepsOf(hostScenario);
    final int rootstockIndex = indexOfStep(hostSteps, rootstockStepName);
    if (rootstockIndex < 0) {
      throw new IllegalArgumentException(
          "no host rootstock step named '" + rootstockStepName + "' to graft under");
    }
    final StepModel rootstock = hostSteps.get(rootstockIndex);

    scionScenario.getScenarioCases().get(0).getSteps().forEach(rootstock::addNestedStep);

    // The scion's tag map rides up with the graft — the ephemeral cellar (§ seed-broker-spec, two
    // cellars). addNestedStep carries only the STEPS, so a within-run fact the scion posed as a tag
    // (e.g. GraftTag.LIVE_ROOT) would be lost; merge it so the host reads it back via graftedValue.
    scion.getTagMap().values().forEach(hostTree::addTag);

    if (scionScenario.getExecutionStatus() == ExecutionStatus.FAILED) {
      rootstock.setStatus(StepStatus.FAILED);
      // jGiven stores the failure text (errorMessage + stackTrace) only on the CASE, never on a
      // step — so the FAILED status alone would reach the host runbook with no reason. Carry the
      // scion case's failure onto the host case so the operator descends from the seed to the
      // scion's actual error. ACCUMULATE, not first-wins: sibling crossings fail INDEPENDENTLY (the
      // graft does not abort the host — systemd AND cluster can both fail in one run), and jGiven's
      // case holds a single error slot, so each failed crossing's cause is APPENDED under a header
      // naming the crossing. First-wins would keep only systemd and silently drop cluster, leaving
      // its ❌ icon with no reason — the very "two conditions failed, one stacktrace" defect.
      final ScenarioCaseModel scionCase = scionScenario.getScenarioCases().get(0);
      final ScenarioCaseModel hostCase = hostScenario.getScenarioCases().get(0);
      if (scionCase.getErrorMessage() != null) {
        final String header = "═══ " + rootstockStepName + " ═══";
        final boolean firstFailure = hostCase.getErrorMessage() == null;
        hostCase.setErrorMessage(
            (firstFailure ? "" : hostCase.getErrorMessage() + "\n\n")
                + header
                + "\n"
                + scionCase.getErrorMessage());
        final List<String> mergedStack = new ArrayList<>();
        if (!firstFailure) {
          mergedStack.addAll(hostCase.getStackTrace());
          mergedStack.add("");
        }
        mergedStack.add(header);
        if (scionCase.getStackTrace() != null) {
          mergedStack.addAll(scionCase.getStackTrace());
        }
        hostCase.setStackTrace(mergedStack);
      }
      for (int i = rootstockIndex + 1; i < hostSteps.size(); i++) {
        hostSteps.get(i).setStatus(StepStatus.SKIPPED);
      }
    }
  }

  /**
   * Assert a reaped scion runbook PASSED, else throw its failure reason. For a NON-grafting reaper
   * — a CLI root that sows a scion and has no host tree to graft the verdict into (unlike {@link
   * #graftUnder}, which propagates a FAILED scion onto the host runbook and carries its error text
   * up). The scion's failure crossed the realm as JSON, so jGiven holds only the case {@code
   * errorMessage} + {@code stackTrace} STRINGS (no live Throwable to chain) — both are folded into
   * the thrown {@link AssertionError} so the operator sees the reason AND the scion's frames, not a
   * one-line summary.
   *
   * @param label how the operator names the sow (e.g. {@code "the manifests synthesis"})
   */
  public void assertPassed(String runbookJson, String label) {
    final ReportModel model = rebuild(runbookJson);
    if (model.getScenarios().isEmpty()) {
      throw new AssertionError(label + " reaped a runbook with no scenario");
    }
    final ScenarioModel scenario = model.getScenarios().get(0);
    if (scenario.getExecutionStatus() != ExecutionStatus.FAILED) {
      return;
    }
    final ScenarioCaseModel failed = scenario.getScenarioCases().get(0);
    final String stack =
        failed.getStackTrace() == null || failed.getStackTrace().isEmpty()
            ? ""
            : "\n" + String.join("\n", failed.getStackTrace());
    throw new AssertionError(label + " failed in-container: " + failed.getErrorMessage() + stack);
  }

  /**
   * Read a within-run fact a scion posed on its model and the graft merged into {@code hostTree} —
   * the ephemeral cellar's read side. Returns the single tag value of {@code kind}, or empty if the
   * scion posed none (a scion that did not run, or a probe with nothing to bring to the terrain).
   * The host asks the graft mechanism, never hand-filters a tag map — the tag structure stays the
   * mechanism's own.
   */
  public Optional<String> graftedValue(ReportModel hostTree, GraftTag kind) {
    return hostTree.getTagMap().values().stream()
        .filter(tag -> kind.type().equals(tag.getType()))
        .map(Tag::getValues)
        .filter(values -> values != null && !values.isEmpty())
        .map(values -> String.valueOf(values.get(0)))
        .findFirst();
  }

  private ScenarioModel firstScenarioOf(ReportModel model) {
    if (model.getScenarios().isEmpty()) {
      throw new IllegalArgumentException("the scion model carries no scenario to graft");
    }
    return model.getScenarios().get(0);
  }

  private List<StepModel> topLevelStepsOf(ScenarioModel scenario) {
    return scenario.getScenarioCases().get(0).getSteps();
  }

  private int indexOfStep(List<StepModel> steps, String name) {
    for (int i = 0; i < steps.size(); i++) {
      if (name.equals(steps.get(i).getName())) {
        return i;
      }
    }
    return -1;
  }
}
