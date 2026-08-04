package io.nxmatic.rke2lab.incus.ingress;

/**
 * The flat IDENTITY view the GROW poses on the instance as {@code user.rke2lab.node-*} config keys
 * — the per-node facts the guest reads back over devlxd ({@code /dev/incus/sock}) to resolve its
 * hostname, its zfs dataset and its rke2 role at boot. There is NO cloud-init and NO host file
 * mount: the NixOS {@code node-base} substrate is homogeneous, and the only per-node difference is
 * these four scalars, delivered declaratively on the Instance resource.
 *
 * <p>Like {@link GrowNetworkView}, every field ORIGINATES in the {@code ClusterNetworkBlueprint}
 * ({@code netplan-contract}, OSGi-only): the {@code nodeName}/{@code nodeId} are the blueprint's
 * node ref, the {@code nodeKind} is {@code NodeType.kind()} (server/agent — the same single source
 * the manifests node-env identity projects), and the {@code nodeHostname} is {@code
 * <cluster>-<node>} (the OS hostname the node sets so mDNS resolves {@code
 * <cluster>-<node>.local}). The host cannot read the blueprint typed, so the scion resolves it and
 * projects these flat values here; the host only poses them.
 */
public record GrowIdentityView(String nodeName, String nodeHostname, String nodeKind, int nodeId) {}
