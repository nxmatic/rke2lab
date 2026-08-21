package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The node-side bootstrap manifest set as one multi-document YAML string — the {@code
 * NODE_BOOTSTRAP}-marked resources the exploder collected into {@code .bootstrap/rke2lab-bootstrap
 * .yaml} (Flux operator/instance/root, the bootstrap {@code sops-age} + {@code githubapp} Secrets,
 * the cilium {@code HelmChartConfig}).
 *
 * <p>A {@code type=dual-realm} record: the synthesis scion (OSGi) reads the exploded file and files
 * it in the cellar under {@link ServerManifestsCoordinate#SERVER_MANIFESTS} — SEALED, since it
 * carries the App private key and the cluster age identity — and the pure-host GROW fetches it
 * (revealed) and poses it on the instance's {@code user.rke2lab.server-manifests} devlxd key, where
 * the node's {@code rke2lab-server-manifests.service} writes it into {@code
 * /var/lib/rancher/rke2/server/manifests} before rke2-server. {@link SeedContract} binds it to the
 * {@code server-manifests} coordinate for the codec's decode guard.
 */
@SeedContract("server-manifests")
public record ServerManifestsBundle(String manifests) {}
