package io.nxmatic.rke2lab.incus.ingress;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The cluster's age IDENTITY (the private key derived from the {@code rke2-cluster} ssh key via
 * ssh-to-age) — what the node needs to decrypt {@link ClusterCaBundle}. Secret: the seal WHEN files
 * it in the cellar {@link IncusGrowCoordinate#CLUSTER_AGE_KEY} SEALED ({@code Sensitivity.SEALED},
 * CellarCipher at rest — so the bundle's decryption key never sits in the clear beside it), and the
 * host GROW reveals it and poses it on the instance's {@code user.rke2lab.sops-age-key} devlxd key.
 *
 * <p>A {@code type=dual-realm} record like {@link InstanceGrowPlan}. {@link SeedContract} binds it
 * to the {@code cluster-age-key} coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("cluster-age-key")
public record ClusterAgeKey(String identity) {}
