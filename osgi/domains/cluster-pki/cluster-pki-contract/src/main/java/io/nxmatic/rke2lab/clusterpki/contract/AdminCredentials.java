package io.nxmatic.rke2lab.clusterpki.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The operator's admin credentials, endpoint-INDEPENDENT: an admin client certificate ({@code
 * CN=rke2lab-admin, O=system:masters}) minted from the deterministic cluster {@code client-ca}, its
 * private key, and the {@code server-ca} chain that verifies kube-apiserver — the chain ends at the
 * ndh {@code mammoth-skate-tls} root, so the operator trusts it natively. Three PEM blocks, no
 * endpoint: the server URL is a per-consumer fact (the operator reaches the node over its mDNS name
 * {@code <cluster>-<node>.local}; in-cluster consumers reach it over the kube-vip VIP), so the
 * kubeconfig is rendered downstream, around a supplied endpoint. The seal only mints what is stable
 * across re-grows.
 *
 * <p>Secret (it carries the admin private key): the seal WHEN files it in the cellar {@link
 * ClusterPkiCoordinate#ADMIN_CREDENTIALS} SEALED ({@code Sensitivity.SEALED}, CellarCipher at
 * rest). The manifests scion reveals it in-container and translates it to the manifests-side {@code
 * OperatorPkiMaterial}, which renders both the operator kubeconfig (mDNS) and the in-cluster {@code
 * <cluster>-kubeconfig} Secret (VIP) — the render lives manifests-side, so no cluster-pki type
 * crosses into that domain. A {@code type=dual-realm} record: minted + filed OSGi-side by the seal
 * scion, revealed by the manifests scion. {@link SeedContract} binds it to the {@code
 * admin-credentials} coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("admin-credentials")
public record AdminCredentials(String clientCertPem, String clientKeyPem, String caCertPem) {}
