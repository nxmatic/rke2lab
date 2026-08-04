package io.nxmatic.rke2lab.incus.ingress;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The provisioning topology of the control node — the roots the bootstrap resolves once from the
 * worktree scalars and carries through the run. The NixOS {@code node-base} substrate bakes the
 * node's config, systemd units and scripts into the image, so the former {@code /srv/host} mount
 * catalog and the host-asset materialisation roots are gone; what remains is the worktree base, the
 * per-node state tree ({@code clusterNodeRoot} + {@code liveRoot} for the runbook), the synthesised
 * {@code manifestsRoot} (still produced for the manifests → server-manifests delivery), the {@code
 * assetsRoot} the staging rebase pivots on, and the {@code secretsFile}. Felix is embedded in the
 * host JVM, so the scion computes the whole topology OSGi-side, here. {@link #fromLocalWorktree}
 * builds the DARWIN-local view; {@link #asStagingView} rebases under a rotation slot; {@link
 * #asAutomountView} rebases onto the NFS-automount view the remote NixOS host reads through.
 */
public record BootstrapPaths(
    Path worktreeRoot,
    Path clusterNodeRoot,
    Path manifestsRoot,
    Path assetsRoot,
    Path secretsFile) {

  private static Builder builder() {
    return new Builder();
  }

  /**
   * The per-run state dir under the worktree root — {@code .local.d}, the DARWIN-local convention
   * this class lays out. Named once here (the single source) rather than spelled as a literal at
   * every site that reaches into the state tree (the host log file, the kubeconfig ref, …).
   */
  public static final String STATE_DIR = ".local.d";

  /**
   * The DARWIN-local layout: {@code .local.d/<cluster>/<node>/} owns everything per-node. One short
   * {@code cd} lands in the per-node tree — no {@code var/{run,lib}/incus/...} split to translate.
   */
  public static BootstrapPaths fromLocalWorktree(
      Path worktreeRoot, String clusterName, String nodeName) {
    final Path nodeRoot = worktreeRoot.resolve(STATE_DIR).resolve(clusterName).resolve(nodeName);
    final Path hostResourceRoot = nodeRoot.resolve("host");
    return builder()
        .worktreeRoot(worktreeRoot)
        .clusterNodeRoot(nodeRoot)
        .manifestsRoot(hostResourceRoot.resolve("rke2-manifests.d"))
        .assetsRoot(hostResourceRoot)
        .secretsFile(worktreeRoot.resolve(".secrets"))
        .build();
  }

  /**
   * Every materialisation root rebased under {@code stagingRoot} (the rotation slot) — the
   * manifests synthesis writes there before the promotion rsyncs it onto the live tree. Only {@code
   * manifestsRoot} moves under the slot (the pivot is {@code assetsRoot}); the worktree/state roots
   * and the secrets file pass through unchanged.
   */
  public BootstrapPaths asStagingView(Path stagingRoot) {
    return builder()
        .worktreeRoot(worktreeRoot)
        .clusterNodeRoot(clusterNodeRoot)
        .manifestsRoot(stagingRoot.resolve(assetsRoot.relativize(manifestsRoot)))
        .assetsRoot(stagingRoot)
        .secretsFile(secretsFile)
        .build();
  }

  /**
   * The deployed tree the instance's host reads — the fixed {@code host.live.d} the promotion
   * rsyncs a chosen staging slot into. The host writes the run's narration (the runbook) here
   * directly, post-run. A fixed path off {@code clusterNodeRoot}, so the host derives it from the
   * flat scalars alone — the scion need not return the slot it chose.
   */
  public Path liveRoot() {
    return clusterNodeRoot.resolve("host.live.d");
  }

  /**
   * The layout rebased onto the NFS automount view the remote NIXOS host mounts the assets from.
   * Provisioning is BI-MACHINE: Felix (on the Mac) WRITES the assets under the DARWIN-local paths,
   * the remote NIXOS host reads them over its NFS automount. When {@code nfsAutomount} is off the
   * paths are unchanged (same machine); otherwise each absolute path is rebased under {@code
   * netPrefix} (e.g. {@code /net/<cluster>.<tailnet>}, the tailscale MagicDNS FQDN).
   */
  public BootstrapPaths asAutomountView(boolean nfsAutomount, String netPrefix) {
    return builder()
        .worktreeRoot(automountPath(worktreeRoot, nfsAutomount, netPrefix))
        .clusterNodeRoot(automountPath(clusterNodeRoot, nfsAutomount, netPrefix))
        .manifestsRoot(automountPath(manifestsRoot, nfsAutomount, netPrefix))
        .assetsRoot(automountPath(assetsRoot, nfsAutomount, netPrefix))
        .secretsFile(automountPath(secretsFile, nfsAutomount, netPrefix))
        .build();
  }

  /**
   * Rebase one absolute path onto the NFS automount view. With automount off (or a path already
   * under {@code /net/}) the path is returned unchanged; any absolute path is rebased under {@code
   * netPrefix} (host-agnostic, the input root is already canonicalised at the source via {@link
   * io.nxmatic.rke2lab.worktree.Worktree}).
   */
  Path automountPath(Path rawPath, boolean nfsAutomount, String netPrefix) {
    final Path normalized = rawPath.toAbsolutePath().normalize();
    if (!nfsAutomount) {
      return normalized;
    }
    final String path = normalized.toString();
    if (path.startsWith("/net/")) {
      return normalized;
    }
    return Path.of(netPrefix + path).normalize();
  }

  private static final class Builder {
    @Nullable private Path worktreeRoot;
    @Nullable private Path clusterNodeRoot;
    @Nullable private Path manifestsRoot;
    @Nullable private Path assetsRoot;
    @Nullable private Path secretsFile;

    private Builder worktreeRoot(Path value) {
      this.worktreeRoot = value;
      return this;
    }

    private Builder clusterNodeRoot(Path value) {
      this.clusterNodeRoot = value;
      return this;
    }

    private Builder manifestsRoot(Path value) {
      this.manifestsRoot = value;
      return this;
    }

    private Builder assetsRoot(Path value) {
      this.assetsRoot = value;
      return this;
    }

    private Builder secretsFile(Path value) {
      this.secretsFile = value;
      return this;
    }

    private BootstrapPaths build() {
      return new BootstrapPaths(
          Objects.requireNonNull(worktreeRoot),
          Objects.requireNonNull(clusterNodeRoot),
          Objects.requireNonNull(manifestsRoot),
          Objects.requireNonNull(assetsRoot),
          Objects.requireNonNull(secretsFile));
    }
  }
}
