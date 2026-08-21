package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The manifests domain's DUAL-REALM seed coordinate — the one case the synthesis scion files and
 * the pure-host GROW fetches. It lives HERE, in the host face of the manifests contract (not the
 * bundle-only {@code manifests-contract}), precisely so the GROW — which runs outside Felix, in the
 * FLAT realm — can reference it without touching a bundle-wired package, the same lesson {@code
 * ClusterPkiCoordinate} / {@code IncusGrowCoordinate} apply.
 *
 * <p>The slug matches the {@code @SeedContract} of {@link ServerManifestsBundle}, which {@code
 * SeedCodec} verifies at decode. Domain is {@code "manifests"} — the synthesis scion (OSGi) stores,
 * the host GROW fetches and poses it on {@code user.rke2lab.server-manifests}, across the realm
 * boundary com.pulumi imposes. See docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc.
 */
public enum ServerManifestsCoordinate implements SeedCoordinate {

  /**
   * The node-side bootstrap set — the {@code NODE_BOOTSTRAP}-marked resources the exploder collects
   * into {@code .bootstrap/rke2lab-bootstrap.yaml} (Flux operator/instance/root, the bootstrap
   * Secrets, the cilium {@code HelmChartConfig}). SEALED in the cellar (it carries the App private
   * key and the cluster age identity).
   */
  SERVER_MANIFESTS("server-manifests");

  private static final String DOMAIN = "manifests";

  private final String slug;

  ServerManifestsCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
