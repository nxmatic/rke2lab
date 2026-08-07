package io.nxmatic.rke2lab.incus.ingress;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The deterministic cluster CA set, as the sops-encrypted YAML the ceremony emits (the five leaf
 * CAs + service.key, sealed for the node's cluster age recipient). An opaque blob to everyone but
 * the node: the seal WHEN files it in the cellar {@link IncusGrowCoordinate#CLUSTER_CA_BUNDLE} in
 * the clear ({@code Sensitivity.PLAIN} — it is ALREADY sops-sealed, so the cellar need not re-seal
 * it), and the host GROW poses it on the instance's {@code user.rke2lab.cluster-ca-bundle} devlxd
 * key, to be decrypted on the node by {@code sops-install-secrets} with {@link ClusterAgeKey}.
 *
 * <p>A {@code type=dual-realm} record like {@link InstanceGrowPlan}: minted + stored host-side,
 * fetched host-side by the GROW. {@link SeedContract} binds it to the {@code cluster-ca-bundle}
 * coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("cluster-ca-bundle")
public record ClusterCaBundle(String sops) {}
