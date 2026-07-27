package io.nxmatic.rke2lab.incus.ingress;

/**
 * One flat DISK mount the GROW poses on the Pulumi graph — the {@code source} the NixOS host mounts
 * FROM (a {@link BootstrapPaths} root already rebased onto the live + NFS-automount view) paired
 * with the {@code target} the instance sees ({@link BootstrapPaths.HostPathCatalog}), under a
 * stable device name.
 *
 * <p>The 13 disk mounts are resolved OSGi-side by the scion (the scion-projects/host-actualises
 * rule, § host-cellar-realisation): Felix is embedded in the host JVM, so the scion sees the same
 * filesystem, reconstructs the topology from the worktree root it reads off its {@code Worktree}
 * component, and projects the resolved source→target pairs here. The host GROW only poses them as
 * {@code disk} devices — it derives NO path, holds no {@code BootstrapPaths}, no worktree root. The
 * pairing lives on {@link BootstrapPaths#instanceMounts()} (the topology owner derives its own
 * mounts), so this is a pure value record.
 */
public record GrowMountView(String deviceName, String source, String target) {}
