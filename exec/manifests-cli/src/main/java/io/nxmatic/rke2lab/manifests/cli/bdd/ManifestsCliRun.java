package io.nxmatic.rke2lab.manifests.cli.bdd;

import java.util.Optional;

/**
 * The one driver-captured fact the manifests CLI seeds into {@link ManifestsCliScenario}: the plot
 * to materialise into (the {@code SOIL} amendment the sow carries). Everything else the manifests
 * scion needs — which layers publish, which debug — falls to {@code
 * ManifestsRunbookInput.defaults()} at the reconcile door when this run supplies no facet, so the
 * CLI stays a pure "synthesise here" verb. {@link Optional#empty()} materialises into a temp dir (a
 * bare survey), never a blank string.
 */
public record ManifestsCliRun(Optional<String> materializationRoot) {

  public static ManifestsCliRun of(Optional<String> materializationRoot) {
    return new ManifestsCliRun(materializationRoot);
  }
}
