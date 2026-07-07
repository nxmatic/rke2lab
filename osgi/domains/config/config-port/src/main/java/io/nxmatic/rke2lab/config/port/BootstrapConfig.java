package io.nxmatic.rke2lab.config.port;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The flat, Pulumi-free record of derived Stage-A bootstrap settings — the config vocabulary the
 * OSGi world reasons with. The host derives it from the Pulumi-backed {@code Rke2labConfig} (that
 * derivation stays host-side, since it touches {@code com.pulumi}); this record is the frontier
 * value both worlds carry.
 *
 * <p>It also owns the pure NFS-automount path rewriting ({@link #pathOn}): a deterministic {@code
 * (host, path) → path} transformation, no filesystem or git access, so it belongs on the record
 * rather than behind a host edge.
 */
public record BootstrapConfig(
    Path worktreeDir,
    String clusterName,
    String nodeName,
    String incusProject,
    String incusDefaultRemote,
    URI incusRemoteAddress,
    Path incusConfigDir,
    String imageAlias,
    String imageBuilderHost,
    URI imageDistrobuilderConfig,
    Path imageSharedFolder,
    String profileName,
    String lanBridgeParent,
    String vmnetNetworkName,
    URI apiEndpoint,
    Path kubeconfigRef,
    boolean nfsAutomount,
    String systemdAdapterDbusHost,
    int systemdAdapterDbusPort,
    int hostAssetRotationRetentionCount,
    Duration readinessTimeout) {

  public String imageBuilderBinary() {
    return "distrobuilder";
  }

  public enum WorktreeHost {
    DARWIN,
    NIXOS
  }

  public Path worktreeDirOn(WorktreeHost host) {
    return pathOn(host, worktreeDir);
  }

  public Path pathOn(WorktreeHost host, Path rawPath) {
    final Path normalizedPath = normalizeAbsolutePath(rawPath);
    if (host == WorktreeHost.DARWIN || !nfsAutomount) {
      return normalizedPath;
    }

    final String netPrefix = netPrefix();
    final String normalized = normalizedPath.toString();

    if (normalized.startsWith("/net/")) {
      return normalizedPath;
    }
    if (normalized.startsWith("/private/")) {
      return Path.of(netPrefix + normalized).normalize();
    }
    if (normalized.startsWith("/")) {
      return Path.of(netPrefix + "/private" + normalized).normalize();
    }
    return Path.of(netPrefix + "/private/" + normalized).normalize();
  }

  public BootstrapConfig asIncusConfig() {
    return new BootstrapConfig(
        worktreeDirOn(WorktreeHost.NIXOS),
        clusterName,
        nodeName,
        incusProject,
        incusDefaultRemote,
        incusRemoteAddress,
        incusConfigDir,
        imageAlias,
        imageBuilderHost,
        imageDistrobuilderConfig,
        imageSharedFolder,
        profileName,
        lanBridgeParent,
        vmnetNetworkName,
        apiEndpoint,
        kubeconfigRef,
        nfsAutomount,
        systemdAdapterDbusHost,
        systemdAdapterDbusPort,
        hostAssetRotationRetentionCount,
        readinessTimeout);
  }

  public Path localWorktreePath() {
    return worktreeDirOn(WorktreeHost.DARWIN);
  }

  public String netPrefix() {
    return "/net/" + clusterName + ".local";
  }

  private static Path normalizeAbsolutePath(Path rawPath) {
    return rawPath.toAbsolutePath().normalize();
  }
}
