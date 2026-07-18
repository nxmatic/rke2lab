package io.nxmatic.rke2lab.incus.contract.host;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The provisioning topology of the control node — every root the bootstrap materialises into,
 * resolved once from the worktree and carried through the run as the provisioning state. The tree
 * incus mounts is incus's (§ CORRECTION 2026-07-14), and Felix is embedded in the host JVM, so the
 * scion sees the same filesystem — the whole topology is computed OSGi-side, here. Two projections
 * of one layout: {@link #fromLocalWorktree} builds the DARWIN-local view (where the provisioner
 * writes under {@code .local.d/<cluster>/<node>/host}); {@link #asStagingView} rebases every root
 * under a rotation slot so a run materialises there before rsyncing onto the final tree. The
 * container paths the node itself sees ({@code /srv/host/...}) are the {@link HostPathCatalog}, the
 * single source of truth distinct from these materialisation roots — the mount plan's targets.
 */
public record BootstrapPaths(
    Path worktreeRoot,
    Path stateRoot,
    Path clusterNodeRoot,
    Path manifestsRoot,
    Path runtimeRke2ConfigRoot,
    Path runtimeCloudConfigRoot,
    Path runtimeEnvConfigRoot,
    Path secretsFile,
    Path assetsRoot,
    Path daemonsetRoot,
    Path scriptsRoot,
    Path systemdLibexecRoot,
    Path systemdRoot,
    Path gitRoot,
    Path shareRoot,
    Path kubeconfigRoot,
    Path cloudSeedRoot) {

  /** Catalog of host-mounted container paths (single source of truth for the node's view). */
  public enum HostPathCatalog {
    ROOT("/srv/host"),
    WORKTREE("/srv/host/rke2lab-worktree.d"),
    ENV("/srv/host/rke2lab-environment.d"),
    SCRIPTS("/srv/host/systemd-scripts.d"),
    GIT_WORKTREE("/srv/host/git-worktree.d"),
    SYSTEMD_LIBEXEC("/srv/host/systemd-libexec.d"),
    SYSTEMD_UNITS("/srv/host/systemd-units.d"),
    MANIFESTS("/srv/host/rke2-manifests.d"),
    RKE2_CONFIG("/srv/host/rke2-config.d"),
    CLOUDCONFIG_NOCLOUD("/srv/host/cloudconfig-nocloud.d"),
    SHARE("/srv/host/rke2lab-share.d"),
    KUBECONFIG("/srv/host/rke2lab-kube.d"),
    // The daemonset device's guest mount target. In main this lived apart (a nested
    // DaemonsetLogPolicy in the host monolith, deleted with it); now that HostPathCatalog is the
    // dual-realm single source of the instance's mount targets, this guest path belongs here with
    // the others. (manifests owns its OWN /srv/host/k8s-daemonset.d/runtime/... literals — a
    // different domain, a different realm; they cannot share this constant and don't need to.)
    DAEMONSET("/srv/host/k8s-daemonset.d"),
    // The NoCloud seed's guest mount target — cloud-init's fixed convention (where the agent reads
    // at first boot), not a /srv/host root, but still a mount TARGET the instance takes, so it is
    // single-sourced here with the rest rather than left a literal at the grow site.
    NOCLOUD_SEED("/var/lib/cloud/seed/nocloud");

    private final String containerPath;

    HostPathCatalog(String containerPath) {
      this.containerPath = containerPath;
    }

    /** The absolute container path (e.g. {@code /srv/host/rke2-manifests.d}). */
    public String path() {
      return containerPath;
    }

    /** The directory name only (e.g. {@code rke2-manifests.d}). */
    public String dirName() {
      return Path.of(containerPath).getFileName().toString();
    }

    /** The container path as a {@link Path}. */
    public Path asPath() {
      return Path.of(containerPath);
    }
  }

  private static Builder builder() {
    return new Builder();
  }

  /**
   * The DARWIN-local layout: {@code .local.d/<cluster>/<node>/} owns everything per-node, with the
   * cluster-scoped kubeconfig at {@code .local.d/<cluster>/kubeconfig.yaml}. One short {@code cd}
   * lands in the per-node tree — no {@code var/{run,lib}/incus/...} split to mentally translate.
   */
  public static BootstrapPaths fromLocalWorktree(
      Path worktreeRoot, String clusterName, String nodeName) {
    final Path stateRoot = worktreeRoot.resolve(".local.d");
    final Path clusterRoot = stateRoot.resolve(clusterName);
    final Path nodeRoot = clusterRoot.resolve(nodeName);
    final Path hostResourceRoot = nodeRoot.resolve("host");
    final Path manifestsRoot = hostResourceRoot.resolve(HostPathCatalog.MANIFESTS.dirName());
    final Path runtimeRoot = manifestsRoot.resolve("runtime");
    final Path systemdStagingRoot = hostResourceRoot.resolve("systemd.d");
    final Path scriptsRoot = systemdStagingRoot.resolve(HostPathCatalog.SCRIPTS.dirName());
    final Path systemdLibexecRoot =
        systemdStagingRoot.resolve(HostPathCatalog.SYSTEMD_LIBEXEC.dirName());
    final Path systemdRoot = systemdStagingRoot.resolve(HostPathCatalog.SYSTEMD_UNITS.dirName());

    return builder()
        .worktreeRoot(worktreeRoot)
        .stateRoot(stateRoot)
        .clusterNodeRoot(nodeRoot)
        .manifestsRoot(manifestsRoot)
        .runtimeRke2ConfigRoot(runtimeRoot.resolve("rke2-config"))
        .runtimeCloudConfigRoot(runtimeRoot.resolve("cloud-config"))
        .runtimeEnvConfigRoot(runtimeRoot.resolve("env-config"))
        .secretsFile(worktreeRoot.resolve(".secrets"))
        .assetsRoot(hostResourceRoot)
        .daemonsetRoot(hostResourceRoot.resolve("k8s-daemonset.d"))
        .scriptsRoot(scriptsRoot)
        .systemdLibexecRoot(systemdLibexecRoot)
        .systemdRoot(systemdRoot)
        .gitRoot(
            Objects.requireNonNull(
                Objects.requireNonNull(
                        worktreeRoot.getParent(), "worktree has no parent: " + worktreeRoot)
                    .getParent(),
                "worktree has no grandparent (git root): " + worktreeRoot))
        .shareRoot(stateRoot.resolve("share"))
        .kubeconfigRoot(clusterRoot)
        .cloudSeedRoot(nodeRoot.resolve("cloud.d"))
        .build();
  }

  /** Every materialisation root rebased under {@code stagingRoot} (the rotation slot). */
  public BootstrapPaths asStagingView(Path stagingRoot) {
    final Path originalRoot = assetsRoot;
    return builder()
        .worktreeRoot(worktreeRoot)
        .stateRoot(stateRoot)
        .clusterNodeRoot(clusterNodeRoot)
        .manifestsRoot(stagingRoot.resolve(originalRoot.relativize(manifestsRoot)))
        .runtimeRke2ConfigRoot(stagingRoot.resolve(originalRoot.relativize(runtimeRke2ConfigRoot)))
        .runtimeCloudConfigRoot(
            stagingRoot.resolve(originalRoot.relativize(runtimeCloudConfigRoot)))
        .runtimeEnvConfigRoot(stagingRoot.resolve(originalRoot.relativize(runtimeEnvConfigRoot)))
        .secretsFile(secretsFile)
        .assetsRoot(stagingRoot)
        .daemonsetRoot(stagingRoot.resolve(originalRoot.relativize(daemonsetRoot)))
        .scriptsRoot(stagingRoot.resolve(originalRoot.relativize(scriptsRoot)))
        .systemdLibexecRoot(stagingRoot.resolve(originalRoot.relativize(systemdLibexecRoot)))
        .systemdRoot(stagingRoot.resolve(originalRoot.relativize(systemdRoot)))
        .gitRoot(gitRoot)
        .shareRoot(shareRoot)
        .kubeconfigRoot(kubeconfigRoot)
        .cloudSeedRoot(stagingRoot.resolve(originalRoot.relativize(cloudSeedRoot)))
        .build();
  }

  /**
   * The deployed tree the instance mounts — the fixed {@code host.live.d} the promotion rsyncs a
   * chosen staging slot into (singular, not a rotation slot). The host writes the run's narration
   * (the runbook) here DIRECTLY, post-run: the runbook is rendered from the complete played model,
   * so it cannot travel through the promotion (which runs mid-scenario) — it is a LIVE mutation,
   * the twin of the instance mutating its mounted content at runtime, seen as drift at the next
   * rotation (§ host-cellar-realisation, the two deltas). A fixed path off {@code clusterNodeRoot},
   * so the host derives it from the flat scalars alone — the scion need not return the slot it
   * chose.
   */
  public Path liveRoot() {
    return clusterNodeRoot.resolve("host.live.d");
  }

  /**
   * The layout rebased onto the NFS automount view the remote NIXOS host mounts the assets from.
   * Provisioning is BI-MACHINE: Felix (on the Mac) WRITES the assets under the DARWIN-local paths,
   * the remote NIXOS host MOUNTS them over its NFS automount to grow the instance. This view yields
   * the SOURCES of the instance's disk mounts (each paired with its {@link HostPathCatalog}
   * target). When {@code nfsAutomount} is off the paths are unchanged (same machine); otherwise
   * each absolute path is rebased under {@code netPrefix} (e.g. {@code /net/<cluster>.local}).
   */
  public BootstrapPaths asAutomountView(boolean nfsAutomount, String netPrefix) {
    return builder()
        .worktreeRoot(automountPath(worktreeRoot, nfsAutomount, netPrefix))
        .stateRoot(automountPath(stateRoot, nfsAutomount, netPrefix))
        .clusterNodeRoot(automountPath(clusterNodeRoot, nfsAutomount, netPrefix))
        .manifestsRoot(automountPath(manifestsRoot, nfsAutomount, netPrefix))
        .runtimeRke2ConfigRoot(automountPath(runtimeRke2ConfigRoot, nfsAutomount, netPrefix))
        .runtimeCloudConfigRoot(automountPath(runtimeCloudConfigRoot, nfsAutomount, netPrefix))
        .runtimeEnvConfigRoot(automountPath(runtimeEnvConfigRoot, nfsAutomount, netPrefix))
        .secretsFile(automountPath(secretsFile, nfsAutomount, netPrefix))
        .assetsRoot(automountPath(assetsRoot, nfsAutomount, netPrefix))
        .daemonsetRoot(automountPath(daemonsetRoot, nfsAutomount, netPrefix))
        .scriptsRoot(automountPath(scriptsRoot, nfsAutomount, netPrefix))
        .systemdLibexecRoot(automountPath(systemdLibexecRoot, nfsAutomount, netPrefix))
        .systemdRoot(automountPath(systemdRoot, nfsAutomount, netPrefix))
        .gitRoot(automountPath(gitRoot, nfsAutomount, netPrefix))
        .shareRoot(automountPath(shareRoot, nfsAutomount, netPrefix))
        .kubeconfigRoot(automountPath(kubeconfigRoot, nfsAutomount, netPrefix))
        .cloudSeedRoot(automountPath(cloudSeedRoot, nfsAutomount, netPrefix))
        .build();
  }

  /**
   * Rebase one absolute path onto the NFS automount view — the pure translation formerly {@code
   * BootstrapConfig.pathOn}. With automount off (or a path already under {@code /net/}) the path is
   * returned unchanged; a {@code /private/...} path gets {@code netPrefix} prepended, any other
   * absolute path gets {@code netPrefix + /private} prepended (the automount root the NIXOS host
   * exports the Mac's {@code /private} tree under).
   */
  static Path automountPath(Path rawPath, boolean nfsAutomount, String netPrefix) {
    final Path normalized = rawPath.toAbsolutePath().normalize();
    if (!nfsAutomount) {
      return normalized;
    }
    final String path = normalized.toString();
    if (path.startsWith("/net/")) {
      return normalized;
    }
    if (path.startsWith("/private/")) {
      return Path.of(netPrefix + path).normalize();
    }
    if (path.startsWith("/")) {
      return Path.of(netPrefix + "/private" + path).normalize();
    }
    return Path.of(netPrefix + "/private/" + path).normalize();
  }

  private static final class Builder {
    // Set-once by the fluent setters, all read together in build(); never re-nulled — the builder
    // is
    // fully populated by fromLocalWorktree / asStagingView before build().
    @Nullable private Path worktreeRoot;
    @Nullable private Path stateRoot;
    @Nullable private Path clusterNodeRoot;
    @Nullable private Path manifestsRoot;
    @Nullable private Path runtimeRke2ConfigRoot;
    @Nullable private Path runtimeCloudConfigRoot;
    @Nullable private Path runtimeEnvConfigRoot;
    @Nullable private Path secretsFile;
    @Nullable private Path assetsRoot;
    @Nullable private Path daemonsetRoot;
    @Nullable private Path scriptsRoot;
    @Nullable private Path systemdLibexecRoot;
    @Nullable private Path systemdRoot;
    @Nullable private Path gitRoot;
    @Nullable private Path shareRoot;
    @Nullable private Path kubeconfigRoot;
    @Nullable private Path cloudSeedRoot;

    private Builder worktreeRoot(Path value) {
      this.worktreeRoot = value;
      return this;
    }

    private Builder stateRoot(Path value) {
      this.stateRoot = value;
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

    private Builder runtimeRke2ConfigRoot(Path value) {
      this.runtimeRke2ConfigRoot = value;
      return this;
    }

    private Builder runtimeCloudConfigRoot(Path value) {
      this.runtimeCloudConfigRoot = value;
      return this;
    }

    private Builder runtimeEnvConfigRoot(Path value) {
      this.runtimeEnvConfigRoot = value;
      return this;
    }

    private Builder secretsFile(Path value) {
      this.secretsFile = value;
      return this;
    }

    private Builder assetsRoot(Path value) {
      this.assetsRoot = value;
      return this;
    }

    private Builder daemonsetRoot(Path value) {
      this.daemonsetRoot = value;
      return this;
    }

    private Builder scriptsRoot(Path value) {
      this.scriptsRoot = value;
      return this;
    }

    private Builder systemdLibexecRoot(Path value) {
      this.systemdLibexecRoot = value;
      return this;
    }

    private Builder systemdRoot(Path value) {
      this.systemdRoot = value;
      return this;
    }

    private Builder gitRoot(Path value) {
      this.gitRoot = value;
      return this;
    }

    private Builder shareRoot(Path value) {
      this.shareRoot = value;
      return this;
    }

    private Builder kubeconfigRoot(Path value) {
      this.kubeconfigRoot = value;
      return this;
    }

    private Builder cloudSeedRoot(Path value) {
      this.cloudSeedRoot = value;
      return this;
    }

    private BootstrapPaths build() {
      // The factories (fromLocalWorktree / asHostView / asStagingView) set every root before
      // build();
      // requireNonNull affirms the monotone→non-null transition NullAway cannot prove across
      // setters.
      return new BootstrapPaths(
          Objects.requireNonNull(worktreeRoot),
          Objects.requireNonNull(stateRoot),
          Objects.requireNonNull(clusterNodeRoot),
          Objects.requireNonNull(manifestsRoot),
          Objects.requireNonNull(runtimeRke2ConfigRoot),
          Objects.requireNonNull(runtimeCloudConfigRoot),
          Objects.requireNonNull(runtimeEnvConfigRoot),
          Objects.requireNonNull(secretsFile),
          Objects.requireNonNull(assetsRoot),
          Objects.requireNonNull(daemonsetRoot),
          Objects.requireNonNull(scriptsRoot),
          Objects.requireNonNull(systemdLibexecRoot),
          Objects.requireNonNull(systemdRoot),
          Objects.requireNonNull(gitRoot),
          Objects.requireNonNull(shareRoot),
          Objects.requireNonNull(kubeconfigRoot),
          Objects.requireNonNull(cloudSeedRoot));
    }
  }
}
