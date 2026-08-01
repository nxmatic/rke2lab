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
 * <p>It carries two {@link Amendment}s. {@link #facet} is the {@link Amendment#FACET} — the stable
 * provisioning identity the host holds (its {@code BootstrapConfig}): the cluster/node names, the
 * NFS automount toggle, and the {@code netPrefix} that automount rebases under. It is a FACET, not
 * a per-consult ROW: the value never changes across a run, so the host contributes it AMBIENT (an
 * {@link io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor} the assembler gathers at the
 * amend door) rather than sowing it in the trigger. The scion combines it with the worktree root it
 * reads from the {@code Worktree} OSGi component (which self-locates its own root — no longer a
 * scalar the host carries) to reconstruct the whole provisioning topology in-world (§
 * host-cellar-realisation, the topology is computed OSGi-side): it picks the rotation slot, derives
 * the staging tree, and forwards THAT as the manifests SOIL. {@link #image} is the {@link
 * Amendment#IMAGE} — the seed-image build scalars the scion folds into the {@code buildChecksum}
 * and the artifact paths it projects for the host GROW. Both are {@link Optional}: an EMPTY
 * amendment is the honest model of "unamended" (a bare {@code shape} probe or an offline scenario)
 * — the scion falls back to a temp dir / skips the grow-plan — rather than a record carried with
 * blank-string sentinel fields.
 */
@SeedContract("runbook")
public record IncusRunbookInput(
    @Amendment(Amendment.FACET) Optional<Facet> facet,
    @Amendment(Amendment.IMAGE) Optional<Image> image) {

  /**
   * The default trigger — UNAMENDED (a bare survey; the scion uses a temp dir, projects no plan).
   */
  public static IncusRunbookInput defaults() {
    return new IncusRunbookInput(Optional.empty(), Optional.empty());
  }

  /**
   * The stable provisioning identity the host contributes as the {@link Amendment#FACET} — the
   * cluster/node names, whether the remote host mounts over an NFS automount, and the {@code
   * netPrefix} that automount rebases under (the host's {@code BootstrapConfig.netPrefix()}, e.g.
   * {@code /net/<cluster>.<tailnet>} — carried rather than re-derived so the formula stays
   * single-sourced on the host). The worktree ROOT is NOT here: the scion reads it from the {@code
   * Worktree} OSGi component. The scion feeds these to {@code
   * BootstrapPaths.fromLocalWorktree(root, cluster, node)} (the root from the component) and (for
   * the mount sources) {@code asAutomountView(nfsAutomount, netPrefix)}. The {@code incusProject}
   * is the daemon project the built image is registered into and adopted from (part of the stable
   * provisioning identity, not a build-recipe scalar — so it rides the FACET, not the IMAGE). A
   * sub-record filled blind by role, mirroring the {@code PublishFacet}/{@code DebugFacet} pattern
   * — the host names no path vocabulary.
   */
  public record Facet(
      String clusterName,
      String nodeName,
      boolean nfsAutomount,
      String netPrefix,
      String incusProject) {}

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
