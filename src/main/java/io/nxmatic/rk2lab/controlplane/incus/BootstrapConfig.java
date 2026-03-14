package io.nxmatic.rk2lab.controlplane.incus;

import com.pulumi.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runtime configuration for provider-native Stage A bootstrap.
 */
public record BootstrapConfig(String workspaceDir, String clusterName, String nodeName, String incusProject,
    String incusDefaultRemote, String incusRemoteAddress, String incusConfigDir,
    String imageAlias, String imageBuilderBinary, String imageBuilderHost, String imageDistrobuilderConfig,
    String imageSharedFolder,
    String profileName, String lanBridgeParent, String vmnetNetworkName,
        String apiEndpoint,
        String kubeconfigRef) {

    private static final String WORKSPACE_REPO_PATH_FALLBACK = "/private/var/lib/git/nxmatic/rke2lab";

    public static final class Builder {
        private final Defaults defaults = new Defaults();

        private String workspaceDir = defaults.workspaceDir();

        private String clusterName = "bioskop";

        private String nodeName = "master";

        private String incusProject = "rke2lab";

        private String incusDefaultRemote = "bioskop-nixos";

        private String incusRemoteAddress = "https://bioskop-nixos.local:8443";

        private String incusConfigDir = defaults.incusConfigDir();

        private String imageAlias = "control-node";

        private String imageBuilderBinary = "distrobuilder";

        private String imageBuilderHost = "bioskop-nixos.local";

        private String imageDistrobuilderConfig = "classpath:/incus/incus-distrobuilder.yaml";

        private String imageSharedFolder;

        private String profileName = "rke2lab";

        private String lanBridgeParent = "lan-br";

        private String vmnetNetworkName = "vmnet-br";

        private String apiEndpoint = "https://10.66.106.10:6443";

        private String kubeconfigRef;

        public Builder workspaceDir(String value) {
            this.workspaceDir = value;
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

        public Builder incusRemoteAddress(String value) {
            this.incusRemoteAddress = value;
            return this;
        }

        public Builder incusConfigDir(String value) {
            this.incusConfigDir = value;
            return this;
        }

        public Builder imageAlias(String value) {
            this.imageAlias = value;
            return this;
        }

        public Builder imageBuilderBinary(String value) {
            this.imageBuilderBinary = value;
            return this;
        }

        public Builder imageBuilderHost(String value) {
            this.imageBuilderHost = value;
            return this;
        }

        public Builder imageDistrobuilderConfig(String value) {
            this.imageDistrobuilderConfig = value;
            return this;
        }

        public Builder imageSharedFolder(String value) {
            this.imageSharedFolder = value;
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

        public Builder apiEndpoint(String value) {
            this.apiEndpoint = value;
            return this;
        }

        public Builder kubeconfigRef(String value) {
            this.kubeconfigRef = value;
            return this;
        }

        public Builder applyConfig(Config config) {
            final EnvironmentValues environment = new EnvironmentValues(config);
            override(environment, "workspace.dir", this::workspaceDir);
            override(environment, "cluster.name", this::clusterName);
            override(environment, "node.name", this::nodeName);
            override(environment, "incus.project", this::incusProject);
            override(environment, "incus.defaultRemote", this::incusDefaultRemote);
            override(environment, "incus.remoteAddress", this::incusRemoteAddress);
            override(environment, "incus.configDir", this::incusConfigDir);
            override(environment, "image.alias", this::imageAlias);
            override(environment, "image.builderBinary", this::imageBuilderBinary);
            override(environment, "image.builderHost", this::imageBuilderHost);
            override(environment, "image.distrobuilderConfig", this::imageDistrobuilderConfig);
            override(environment, "image.sharedFolder", this::imageSharedFolder);
            override(environment, "profile.name", this::profileName);
            override(environment, "network.lanBridgeParent", this::lanBridgeParent);
            override(environment, "network.vmnetNetworkName", this::vmnetNetworkName);
            override(environment, "api.endpoint", this::apiEndpoint);
            override(environment, "kubeconfig.ref", this::kubeconfigRef);
            return this;
        }

        private void override(EnvironmentValues environment, String key, Consumer<String> consumer) {
            final String value = environment.raw(key);
            if (!value.isBlank()) {
                consumer.accept(value);
            }
        }

        public BootstrapConfig build() {
            final String resolvedKubeconfigRef = kubeconfigRef != null ? kubeconfigRef
                    : "/srv/host/kubeconfig.d/rke2-" + clusterName + ".yaml";

            if (imageSharedFolder == null || imageSharedFolder.isBlank()) {
                throw new IllegalStateException(
                        "Missing required configuration: image.sharedFolder"
                );
            }

            return new BootstrapConfig(workspaceDir, clusterName, nodeName, incusProject, incusDefaultRemote,
                incusRemoteAddress, incusConfigDir, imageAlias, imageBuilderBinary, imageBuilderHost,
                imageDistrobuilderConfig, imageSharedFolder,
            profileName, lanBridgeParent, vmnetNetworkName,
            apiEndpoint,
            resolvedKubeconfigRef);
        }

    }

    private static final class Defaults {
        String incusConfigDir() {
            final String env = System.getenv("INCUS_CONFIG_DIR");
            if (env != null && !env.isBlank()) {
                return env;
            }

            final String home = System.getProperty("user.home", "");
            if (!home.isBlank()) {
                return home + "/.config/incus";
            }

            return "";
        }

        String workspaceDir() {
            final String workspaceRepoPath = detectWorkspaceRepoPath();
            final String limaHostname = System.getenv("LIMA_HOSTNAME");
            if (limaHostname != null && !limaHostname.isBlank()) {
                return "/net/" + limaHostname + ".local" + workspaceRepoPath;
            }

            return "/net/bioskop.local" + workspaceRepoPath;
        }

        String detectWorkspaceRepoPath() {
            final String gitWorktree = normalizePath(System.getenv("GIT_WORKTREE"));
            if (!gitWorktree.isBlank()) {
                return gitWorktree;
            }

            final String fromUserDir = gitTopLevel(System.getProperty("user.dir", ""));
            if (!fromUserDir.isBlank()) {
                return fromUserDir;
            }

            return WORKSPACE_REPO_PATH_FALLBACK;
        }

        String gitTopLevel(String workingDirectory) {
            final String normalizedWorkingDirectory = normalizePath(workingDirectory);
            if (normalizedWorkingDirectory.isBlank()) {
                return "";
            }

            try {
                final FileRepositoryBuilder builder = new FileRepositoryBuilder()
                        .findGitDir(Path.of(normalizedWorkingDirectory).toFile());

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
