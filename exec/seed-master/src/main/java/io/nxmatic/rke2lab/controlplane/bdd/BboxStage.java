package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.bbox.port.BboxReconciler;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;

/**
 * Bbox-reservation reconciliation, as a phase. The reconciliation runs through an injected {@link
 * BboxProbe} (live delegates to the real orchestrator, driving the {@link BboxReconciler} edge this
 * stage resolves from the registry; tests inject a fake), so the phase touches no bbox secrets nor
 * the edge when played offline.
 */
public class BboxStage extends Stage<BboxStage> {

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState BboxProbe probe;
  @ExpectedScenarioState OsgiConnection connection;

  @ProvidedScenarioState ReconciliationResult bbox;

  @As("bbox reservations are reconciled")
  public BboxStage the_bbox_reservations_are_reconciled() {
    this.bbox = probe.reconcile(hostFacts, connection.awaitService(BboxReconciler.class, 5000));
    return self();
  }
}
