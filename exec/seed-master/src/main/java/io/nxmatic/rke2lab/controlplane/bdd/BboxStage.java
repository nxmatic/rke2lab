package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;

/**
 * Bbox-reservation reconciliation, as a phase. Transposes {@code BboxTopic.reconcileReservations}.
 * The reconciliation runs through an injected {@link BboxProbe} (live delegates to the real
 * orchestrator; tests inject a fake), so the phase touches no bbox secrets when played offline.
 */
public class BboxStage extends Stage<BboxStage> {

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState BboxProbe probe;

  @ProvidedScenarioState ReconciliationResult bbox;

  @As("bbox reservations are reconciled")
  public BboxStage the_bbox_reservations_are_reconciled() {
    this.bbox = probe.reconcile(hostFacts);
    return self();
  }
}
