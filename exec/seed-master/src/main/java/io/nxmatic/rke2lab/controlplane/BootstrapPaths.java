package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig.WorktreeHost;
import java.nio.file.Path;

/**
 * The provisioning topology of the control node — every root the bootstrap materialises into,
 * resolved once from the worktree and carried through the run as the provisioning state. Three
 * projections of one layout: {@link #fromLocalWorktree} builds the DARWIN-local view (where the
 * provisioner writes under {@code .local.d/<cluster>/<node>/host}); {@link #asHostView} rebases it
 * onto a {@link WorktreeHost} (the NIXOS host that mounts the assets); {@link #asStagingView}
 * rebases every root under a rotation slot so a run materialises there before rsyncing onto the
 * final tree. The container paths the node itself sees ({@code /srv/host/...}) are the {@link
 * HostPathCatalog}, the single source of truth distinct from these materialisation roots.
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
    KUBECONFIG("/srv/host/rke2lab-kube.d");

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
        .gitRoot(worktreeRoot.getParent().getParent())
        .shareRoot(stateRoot.resolve("share"))
        .kubeconfigRoot(clusterRoot)
        .cloudSeedRoot(nodeRoot.resolve("cloud.d"))
        .build();
  }

  /** The same layout rebased onto {@code host} (e.g. the NIXOS host that mounts the assets). */
  public BootstrapPaths asHostView(BootstrapConfig config, WorktreeHost host) {
    return builder()
        .worktreeRoot(config.pathOn(host, worktreeRoot))
        .stateRoot(config.pathOn(host, stateRoot))
        .clusterNodeRoot(config.pathOn(host, clusterNodeRoot))
        .manifestsRoot(config.pathOn(host, manifestsRoot))
        .runtimeRke2ConfigRoot(config.pathOn(host, runtimeRke2ConfigRoot))
        .runtimeCloudConfigRoot(config.pathOn(host, runtimeCloudConfigRoot))
        .runtimeEnvConfigRoot(config.pathOn(host, runtimeEnvConfigRoot))
        .secretsFile(config.pathOn(host, secretsFile))
        .assetsRoot(config.pathOn(host, assetsRoot))
        .daemonsetRoot(config.pathOn(host, daemonsetRoot))
        .scriptsRoot(config.pathOn(host, scriptsRoot))
        .systemdLibexecRoot(config.pathOn(host, systemdLibexecRoot))
        .systemdRoot(config.pathOn(host, systemdRoot))
        .gitRoot(config.pathOn(host, gitRoot))
        .shareRoot(config.pathOn(host, shareRoot))
        .kubeconfigRoot(config.pathOn(host, kubeconfigRoot))
        .cloudSeedRoot(config.pathOn(host, cloudSeedRoot))
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

  private static final class Builder {
    private Path worktreeRoot;
    private Path stateRoot;
    private Path clusterNodeRoot;
    private Path manifestsRoot;
    private Path runtimeRke2ConfigRoot;
    private Path runtimeCloudConfigRoot;
    private Path runtimeEnvConfigRoot;
    private Path secretsFile;
    private Path assetsRoot;
    private Path daemonsetRoot;
    private Path scriptsRoot;
    private Path systemdLibexecRoot;
    private Path systemdRoot;
    private Path gitRoot;
    private Path shareRoot;
    private Path kubeconfigRoot;
    private Path cloudSeedRoot;

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
      return new BootstrapPaths(
          worktreeRoot,
          stateRoot,
          clusterNodeRoot,
          manifestsRoot,
          runtimeRke2ConfigRoot,
          runtimeCloudConfigRoot,
          runtimeEnvConfigRoot,
          secretsFile,
          assetsRoot,
          daemonsetRoot,
          scriptsRoot,
          systemdLibexecRoot,
          systemdRoot,
          gitRoot,
          shareRoot,
          kubeconfigRoot,
          cloudSeedRoot);
    }
  }
}
