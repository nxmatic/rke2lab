package io.nxmatic.rke2lab.clusterpki.core;

import io.nxmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.nxmatic.rke2lab.clusterpki.contract.ClusterCaBundle;

/**
 * What {@link ClusterSeal} produces in one act: the sops-sealed CA {@link ClusterCaBundle} (filed
 * PLAIN by the scion — it is already sealed) and the {@link ClusterAgeKey} identity that decrypts
 * it on the node (filed SEALED). The scion stores each at its {@code ClusterPkiCoordinate} case.
 */
public record SealedClusterPki(ClusterCaBundle bundle, ClusterAgeKey ageKey) {}
