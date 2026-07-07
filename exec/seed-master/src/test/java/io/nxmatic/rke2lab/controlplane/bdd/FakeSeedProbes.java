package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;
import java.util.Map;
import java.util.Optional;

/**
 * Canned, inert probe outcomes for the pure-phase scenario — the offline seam. Preflight enforces
 * nothing, bbox returns an empty reconciliation, incus defers (empty Outcome). None touches the
 * real world (git, bbox secrets, incus), so the scenario renders without infrastructure.
 */
final class FakeSeedProbes {

  private FakeSeedProbes() {}

  /** A set where every phase plays inert: gates pass, bbox is empty, incus defers. */
  static SeedProbes inert() {
    return new SeedProbes(
        (hostFacts, framework) -> {},
        hostFacts -> new ReconciliationResult("", Map.of()),
        (hostFacts, framework) -> Optional.empty());
  }
}
