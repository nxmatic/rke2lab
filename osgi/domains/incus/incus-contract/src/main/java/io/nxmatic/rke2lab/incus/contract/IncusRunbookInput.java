package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the incus {@code runbook} trigger — the activation payload a sower supplies
 * to play the incus scion. Like {@code ManifestsRunbookInput} it is the INPUT twin of a reaped
 * wire-record; the {@code shape} meta-coordinate projects its JSON Schema so a sower learns the
 * shape from the broker door rather than compiling the class.
 *
 * <p>It carries one {@link Amendment}: {@link #worktree} is the {@link Amendment#WORKTREE} — the
 * flat provisioning scalars the host holds (its {@code BootstrapConfig}) and the incus scion needs
 * to RECONSTRUCT the whole provisioning topology in-world (§ host-cellar-realisation, the whole
 * topology is computed OSGi-side). The host fills it by role — a blind subtree the schema guides,
 * naming no incus field — and the scion computes its own {@code BootstrapPaths} from it: it picks
 * the rotation slot, derives the staging tree, and forwards THAT as the manifests SOIL (the plot
 * the tree the instance mounts is materialised under). So the SOIL is no longer an input the host
 * pre-computes — the scion resolves it. Blank scalars (unamended — a bare {@code shape} probe or an
 * offline scenario) make the scion fall back to a temp dir the way the manifests scion does.
 */
@SeedContract("runbook")
public record IncusRunbookInput(@Amendment(Amendment.WORKTREE) Worktree worktree) {

  /**
   * The default trigger — UNAMENDED worktree scalars (a bare survey; the scion uses a temp dir).
   */
  public static IncusRunbookInput defaults() {
    return new IncusRunbookInput(Worktree.unset());
  }

  /**
   * The flat provisioning scalars the host fills from its {@code BootstrapConfig} — the worktree
   * root the provisioner writes under, the cluster/node identity, and whether the remote host
   * mounts over an NFS automount. The scion feeds them to {@code
   * BootstrapPaths.fromLocalWorktree(root, cluster, node)} and (for the mount sources) {@code
   * asAutomountView(nfsAutomount, netPrefix)}. A sub-record filled blind by role, mirroring the
   * {@code LinkFacet}/{@code DebugFacet} pattern — the host names no path vocabulary.
   */
  public record Worktree(
      String worktreeRoot, String clusterName, String nodeName, boolean nfsAutomount) {

    /**
     * The unset scalars — a blank worktree root marks a bare survey (no topology to reconstruct).
     */
    public static Worktree unset() {
      return new Worktree("", "", "", false);
    }
  }
}
