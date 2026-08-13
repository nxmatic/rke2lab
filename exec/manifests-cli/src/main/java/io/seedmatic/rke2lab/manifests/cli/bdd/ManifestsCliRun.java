package io.seedmatic.rke2lab.manifests.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The driver-captured facts the manifests CLI seeds into {@link ManifestsCliScenario}, so it sows a
 * COMPLETE manifests runbook input — the sower honours the contract, the amend door supplies no
 * default:
 *
 * <ul>
 *   <li>{@link #materializationRoot} — the plot to materialise into (the {@code SOIL} amendment).
 *       {@link Optional#empty()} materialises into a temp dir (a bare survey), never a blank
 *       string.
 *   <li>{@link #identity} — the cluster/node the render is keyed on (the {@code IDENTITY}
 *       amendment). {@link Optional#empty()} renders the clearly-blank {@code unknown} cluster.
 *   <li>{@link #facet} — the mandatory {@code FACET} (the {@code {publish, debug}} concern the seed
 *       flow reads from Pulumi). Here the CLI IS the sower, so it builds the posture itself
 *       (operator defaults + {@code -Drke2lab.manifests.publish.*} / {@code .debug.*} overrides)
 *       rather than leaning on a door default. Carried as opaque JSON so the host speaks only
 *       through the {@code seed.broker.port} membrane.
 * </ul>
 */
public record ManifestsCliRun(
    Optional<String> materializationRoot, Optional<Identity> identity, JsonNode facet) {

  /**
   * The cluster/node identity the render is keyed on — the CLI twin of {@code
   * ManifestsRunbookInput.Identity}, kept as a local plain-string record so the host speaks only
   * JSON through the {@code seed.broker.port} membrane and never imports the manifests contract.
   * The scenario encodes it as the {@code IDENTITY} amendment exactly as the incus scion does.
   */
  public record Identity(String clusterName, String nodeName) {}

  public static ManifestsCliRun of(
      Optional<String> materializationRoot, Optional<Identity> identity, JsonNode facet) {
    return new ManifestsCliRun(materializationRoot, identity, facet);
  }
}
