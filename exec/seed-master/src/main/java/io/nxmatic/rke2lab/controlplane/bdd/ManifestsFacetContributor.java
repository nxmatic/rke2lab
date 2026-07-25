package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor;
import java.util.Map;

/**
 * The host's standing contribution of the manifests {@code FACET} — the operator config subtree
 * ({@code rke2lab:manifests:}) the root read from Pulumi and no bundle can see. It fills the one
 * role a sower cannot: the incus scion that consults the manifests amend holds the per-consult
 * {@code SOIL} and {@code WORKTREE} it derives in-world, but never the operator's publish/debug
 * config; the host owns that and contributes it at the door, so a consult carries only what it owns
 * and the {@code AmendmentAssembler} merges the ambient FACET in.
 *
 * <p>The value is carried verbatim as a serialized JSON {@code String} (read by {@code
 * ConfigLoader.subtreeJson} inside {@code Pulumi.run}, before this flat-classpath host crosses into
 * OSGi) — the seam rule: no {@code JsonNode} crosses, the manifests scion decodes it with its own
 * jackson. Blank (the config section absent) means nothing to contribute — a legal no-op, the FACET
 * then falls to the runbook input's defaults.
 */
public final class ManifestsFacetContributor implements AmendmentContributor {

  private static final AmendCoordinate MANIFESTS = new AmendCoordinate("manifests");

  private final String facetJson;

  public ManifestsFacetContributor(String facetJson) {
    this.facetJson = facetJson;
  }

  @Override
  public AmendCoordinate coordinate() {
    return MANIFESTS;
  }

  @Override
  public Map<String, String> roles() {
    return facetJson.isBlank() ? Map.of() : Map.of(Amendment.FACET, facetJson);
  }
}
