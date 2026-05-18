package io.nxmatic.rk2lab.controlplane.incus;

import com.pulumi.Config;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Runtime configuration for provider-native Stage A bootstrap. */
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
    Path kubeconfigRef) {

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
    if (host == WorktreeHost.DARWIN) {
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
        kubeconfigRef);
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

  private static final String WORKTREE_REPO_PATH_FALLBACK = "/private/var/lib/git/nxmatic/rke2lab";

  public static final class Builder {
    private final Defaults defaults = new Defaults();

    private Path worktree = defaults.worktree();

    private String clusterName = "bioskop";

    private String nodeName = "master";

    private String incusProject = "rke2lab";

    private String incusDefaultRemote = defaults.incusDefaultRemote();

    private URI incusRemoteAddress = defaults.incusRemoteAddress();

    private Path incusConfigDir = defaults.incusConfigDir();

    private String imageAlias = "control-node";

    private String imageBuilderHost = defaults.imageBuilderHost();

    private URI imageDistrobuilderConfig =
        URI.create(
            "classpath:/META-INF/io.nxmatic/rk2lab/controlplane/incus/incus-distrobuilder.yaml");

    private Path imageSharedFolder;

    private String profileName = "rke2lab";

    private String lanBridgeParent = "lan-br";

    private String vmnetNetworkName = "vmnet-br";

    private URI apiEndpoint = URI.create("https://10.66.106.10:6443");

    private Path kubeconfigRef;

    public Builder worktree(Path value) {
      this.worktree = normalizeAbsolutePath(value);
      return this;
    }

    public Builder clusterName(String value) {
      this.clusterName = value;
      return this;
    }

    public Builder nodeName(String value) {
      this.nodeName = value;
      return this;
    }

    public Builder incusProject(String value) {
      this.incusProject = value;
      return this;
    }

    public Builder incusDefaultRemote(String value) {
      this.incusDefaultRemote = value;
      return this;
    }

    public Builder incusRemoteAddress(URI value) {
      this.incusRemoteAddress = value;
      return this;
    }

    public Builder incusConfigDir(Path value) {
      this.incusConfigDir = value == null ? null : normalizeAbsolutePath(value);
      return this;
    }

    public Builder imageAlias(String value) {
      this.imageAlias = value;
      return this;
    }

    public Builder imageBuilderHost(String value) {
      this.imageBuilderHost = value;
      return this;
    }

    public Builder imageDistrobuilderConfig(URI value) {
      this.imageDistrobuilderConfig = value;
      return this;
    }

    public Builder imageSharedFolder(Path value) {
      this.imageSharedFolder = value == null ? null : value.normalize();
      return this;
    }

    public Builder profileName(String value) {
      this.profileName = value;
      return this;
    }

    public Builder lanBridgeParent(String value) {
      this.lanBridgeParent = value;
      return this;
    }

    public Builder vmnetNetworkName(String value) {
      this.vmnetNetworkName = value;
      return this;
    }

    public Builder apiEndpoint(URI value) {
      this.apiEndpoint = value;
      return this;
    }

    public Builder kubeconfigRef(Path value) {
      this.kubeconfigRef = value == null ? null : value.normalize();
      return this;
    }

    public Builder applyConfig(Config config) {
      final EnvironmentValues environment = new EnvironmentValues(config);
      override(environment, "worktree.dir", value -> this.worktree(parsePath(value)));
      override(environment, "cluster.name", this::clusterName);
      override(environment, "node.name", this::nodeName);
      override(environment, "incus.project", this::incusProject);
      override(environment, "incus.defaultRemote", this::incusDefaultRemote);
      override(
          environment, "incus.remoteAddress", value -> this.incusRemoteAddress(parseUri(value)));
      override(environment, "incus.configDir", value -> this.incusConfigDir(parsePath(value)));
      override(environment, "image.alias", this::imageAlias);
      override(environment, "image.builderHost", this::imageBuilderHost);
      override(
          environment,
          "image.distrobuilderConfig",
          value -> this.imageDistrobuilderConfig(parseUri(value)));
      override(
          environment, "image.sharedFolder", value -> this.imageSharedFolder(parsePath(value)));
      override(environment, "profile.name", this::profileName);
      override(environment, "network.lanBridgeParent", this::lanBridgeParent);
      override(environment, "network.vmnetNetworkName", this::vmnetNetworkName);
      override(environment, "api.endpoint", value -> this.apiEndpoint(parseUri(value)));
      override(environment, "kubeconfig.ref", value -> this.kubeconfigRef(parsePath(value)));
      return this;
    }

    private void override(EnvironmentValues environment, String key, Consumer<String> consumer) {
      final String value = environment.raw(key);
      if (!value.isBlank()) {
        consumer.accept(value);
      }
    }

    public BootstrapConfig build() {
      final Path resolvedKubeconfigRef =
          kubeconfigRef != null
              ? kubeconfigRef
              : Path.of(".local.d", "var", "kube", "rke2-" + clusterName + ".yaml").normalize();

      if (imageSharedFolder == null || imageSharedFolder.toString().isBlank()) {
        throw new IllegalStateException("Missing required configuration: image.sharedFolder");
      }

      return new BootstrapConfig(
          worktree,
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
          resolvedKubeconfigRef);
    }

    private Path parsePath(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      return Path.of(value).normalize();
    }

    private URI parseUri(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      return URI.create(value.trim());
    }
  }

  private static final class Defaults {
    private static final String ACCESS_HOST_ENV = "RKE2LAB_ACCESS_HOST";

    private static final String DEFAULT_ACCESS_HOST = "bioskop-nixos.local";

    private static final int INCUS_REMOTE_PORT = 8443;

    Path incusConfigDir() {
      final String env = System.getenv("INCUS_CONFIG_DIR");
      if (env != null && !env.isBlank()) {
        return Path.of(env).toAbsolutePath().normalize();
      }

      final String home = System.getProperty("user.home", "");
      if (!home.isBlank()) {
        return Path.of(home + "/.config/incus").toAbsolutePath().normalize();
      }

      return null;
    }

    Path worktree() {
      return detectWorktreeRepoPath();
    }

    Path detectWorktreeRepoPath() {
      final String gitWorktree = normalizePath(System.getenv("GIT_WORKTREE"));
      if (!gitWorktree.isBlank()) {
        return Path.of(gitWorktree).toAbsolutePath().normalize();
      }

      final String fromUserDir = gitTopLevel(System.getProperty("user.dir", ""));
      if (!fromUserDir.isBlank()) {
        return Path.of(fromUserDir).toAbsolutePath().normalize();
      }

      return Path.of(WORKTREE_REPO_PATH_FALLBACK).toAbsolutePath().normalize();
    }

    String imageBuilderHost() {
      return accessHost();
    }

    String incusDefaultRemote() {
      final String hostname = accessHost();
      final int dotIndex = hostname.indexOf('.');
      if (dotIndex <= 0) {
        return hostname;
      }
      return hostname.substring(0, dotIndex);
    }

    URI incusRemoteAddress() {
      return URI.create("https://" + accessHost() + ":" + INCUS_REMOTE_PORT);
    }

    String accessHost() {
      final String env = System.getenv(ACCESS_HOST_ENV);
      if (env == null || env.isBlank()) {
        return DEFAULT_ACCESS_HOST;
      }
      return env.trim();
    }

    String gitTopLevel(String workingDirectory) {
      final String normalizedWorkingDirectory = normalizePath(workingDirectory);
      if (normalizedWorkingDirectory.isBlank()) {
        return "";
      }

      try {
        final FileRepositoryBuilder builder =
            new FileRepositoryBuilder().findGitDir(Path.of(normalizedWorkingDirectory).toFile());

        if (builder.getGitDir() == null) {
          return "";
        }

        try (Repository repository = builder.build()) {
          final java.io.File workTree = repository.getWorkTree();
          if (workTree != null) {
            return normalizePath(workTree.getPath());
          }
        }
      } catch (Exception ignored) {
        // Fallback is handled by caller.
      }

      return "";
    }

    String normalizePath(String value) {
      if (value == null || value.isBlank()) {
        return "";
      }
      return Path.of(value).toAbsolutePath().normalize().toString();
    }
  }

  private record EnvironmentValues(Config config) {
    @SuppressWarnings("null")
    String raw(String key) {
      return config.get(key).orElse("");
    }
  }
}
