package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import java.util.Map;

/**
 * Then stage: asserts every cluster-readiness phase passed. Plain {@code AssertionError} (not
 * JUnit) keeps this runnable from production when the checkpoint plays the scenario — JGiven marks
 * a throwing step failed. The failing phase's dossier (with its symptom) is captured by the stage
 * via the probe-holder seam, not read back through a stage getter (JGiven intercepts public stage
 * methods as steps), exactly as {@code SystemdAdapterStage} does.
 */
public class ThenClusterReadiness extends Stage<ThenClusterReadiness> {

  @ExpectedScenarioState Map<ClusterReadinessPhase, Dossier> phaseDossiers;
  @ExpectedScenarioState ClusterReadinessPhase failingPhase;
  @ExpectedScenarioState Dossier failingDossier;

  public ThenClusterReadiness the_cluster_is_ready() {
    if (failingPhase != null) {
      throw new AssertionError(
          "cluster readiness failed at phase \""
              + failingPhase.label()
              + "\": "
              + (failingDossier == null ? "no dossier" : failingDossier.summary()));
    }
    return self();
  }
}
