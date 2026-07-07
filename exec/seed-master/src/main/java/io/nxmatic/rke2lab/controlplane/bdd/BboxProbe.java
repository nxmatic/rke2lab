package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.bbox.port.BboxReconciler;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;

/**
 * Reconciles the bbox DHCP reservations for the run, returning the {@link ReconciliationResult}
 * (URN + summary). Live delegates to the {@code BboxReconciliationOrchestrator}, driving the {@link
 * BboxReconciler} edge the stage resolved from the registry; tests inject a fake that returns a
 * canned result — the injection seam that lets the scenario render offline without the secrets
 * present or the edge running.
 */
@FunctionalInterface
public interface BboxProbe {
  ReconciliationResult reconcile(HostFacts hostFacts, BboxReconciler reconciler);
}
