package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the incus {@code runbook} trigger — the activation payload a sower supplies
 * to play the incus scion. Like {@code ManifestsRunbookInput} it is the INPUT twin of a reaped
 * wire-record; the {@code shape} meta-coordinate projects its JSON Schema so a sower learns the
 * shape from the broker door rather than compiling the class.
 *
 * <p>It carries two {@link Amendment}s. {@link #worktree} is the {@link Amendment#WORKTREE} — the
 * flat provisioning scalars the host holds (its {@code BootstrapConfig}) and the incus scion needs
 * to RECONSTRUCT the whole provisioning topology in-world (§ host-cellar-realisation, the whole
 * topology is computed OSGi-side). The host fills it by role — a blind subtree the schema guides,
 * naming no incus field — and the scion computes its own {@code BootstrapPaths} from it: it picks
 * the rotation slot, derives the staging tree, and forwards THAT as the manifests SOIL (the plot
 * the tree the instance mounts is materialised under). So the SOIL is no longer an input the host
 * pre-computes — the scion resolves it. {@link #image} is the {@link Amendment#IMAGE} — the seed-
 * image build scalars the scion folds into the {@code buildChecksum} and the artifact paths it
 * projects for the host GROW. Both are {@link Optional}: an EMPTY amendment is the honest model of
 * "unamended" (a bare {@code shape} probe or an offline scenario) — the scion falls back to a temp
 * dir / skips the grow-plan — rather than a record carried with blank-string sentinel fields.
 */
@SeedContract("runbook")
public record IncusRunbookInput(
    @Amendment(Amendment.WORKTREE) Optional<Worktree> worktree,
    @Amendment(Amendment.IMAGE) Optional<Image> image) {

  /**
   * The default trigger — UNAMENDED (a bare survey; the scion uses a temp dir, projects no plan).
   */
  public static IncusRunbookInput defaults() {
    return new IncusRunbookInput(Optional.empty(), Optional.empty());
  }

  /**
   * The flat provisioning scalars the host fills from its {@code BootstrapConfig} — the worktree
   * root the provisioner writes under, the cluster/node identity, and whether the remote host
   * mounts over an NFS automount. The scion feeds them to {@code
   * BootstrapPaths.fromLocalWorktree(root, cluster, node)} and (for the mount sources) {@code
   * asAutomountView(nfsAutomount, netPrefix)}. A sub-record filled blind by role, mirroring the
   * {@code PublishFacet}/{@code DebugFacet} pattern — the host names no path vocabulary.
   */
  public record Worktree(
      String worktreeRoot, String clusterName, String nodeName, boolean nfsAutomount) {}

  /**
   * The flat seed-image build scalars the host fills from its {@code BootstrapConfig} — the image
   * {@code alias} (the reuse-lookup key and the {@code new Image} alias), the {@code builderBinary}
   * and {@code builderHost} the edge {@code ImageBuilder} needs, and the {@code sharedFolder} the
   * artifacts land under (the base the scion probes for the readable artifact dir). The scion folds
   * {@code alias}/{@code builderBinary}/{@code builderHost} with the edge's {@code recipeDigest}
   * into the {@code buildChecksum}, and resolves {@code sharedFolder}/{@code alias} to the readable
   * {@code metadataPath}/{@code dataPath} it projects into the grow plan. A sub-record filled blind
   * by role — the host names no incus field.
   */
  public record Image(
      String alias, String builderBinary, String builderHost, String sharedFolder) {}
}
