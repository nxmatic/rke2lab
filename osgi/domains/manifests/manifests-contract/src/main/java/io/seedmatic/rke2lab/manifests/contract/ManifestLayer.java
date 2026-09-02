// @codebase
package io.seedmatic.rke2lab.manifests.contract;

import java.util.Optional;

/**
 * The reconcile LAYER a rendered resource belongs to — the closed, ordered vocabulary the exploder
 * routes on ({@code <layer>/<domain>/<package>/…}) and {@code FluxRootManifestsUnit} chains by
 * {@code dependsOn} so a CR's CRD (rendered OR registered at runtime by an operator/installer in an
 * earlier layer) exists before the CR is dry-run. Typed as an enum (was loose {@code String}
 * constants) so a layer can't be mistyped into a silent misroute. See {@code
 * docs/architecture/cluster-api/manifests-rendered-branches.adoc} §layers.
 */
public enum ManifestLayer {

  /** Layer 1 — rendered {@code CustomResourceDefinition}s (applied first). */
  CRDS("crds"),

  /**
   * Layer 2 — cluster-wide providers later operators depend on (cert-manager, whose HelmChart
   * registers the {@code cert-manager.io} CRDs + runs the issuer that signs operator webhook
   * certs). Before {@link #OPERATORS} because an operator's install bundles a {@code Certificate}
   * whose CRD must be registered — and controller running — before it dry-runs.
   */
  FOUNDATION("foundation"),

  /** Layer 3 — operator/installer resources that register CRDs / controllers at runtime. */
  OPERATORS("operators"),

  /** Layer 4 (default) — the CRs that depend on a CRD from an earlier layer. */
  WORKLOADS("workloads");

  private final String value;

  ManifestLayer(final String value) {
    this.value = value;
  }

  /** The annotation value written under {@link ManifestAnnotation#MANIFEST_LAYER}. */
  public String value() {
    return value;
  }

  /** Resolves a layer annotation value back to its enum, empty if none matches. */
  public static Optional<ManifestLayer> fromValue(final String value) {
    for (final ManifestLayer layer : values()) {
      if (layer.value.equals(value)) {
        return Optional.of(layer);
      }
    }
    return Optional.empty();
  }
}
