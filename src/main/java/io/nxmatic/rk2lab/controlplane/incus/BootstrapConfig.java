package io.nxmatic.rk2lab.controlplane.incus;

import com.pulumi.Config;

import java.util.function.Consumer;

/**
 * Runtime configuration for provider-native Stage A bootstrap.
 */
public record BootstrapConfig(String workspaceDir, String clusterName, String nodeName, String incusProject,
    String incusDefaultRemote, String incusRemoteAddress, String incusConfigDir,
    String imageAlias, String imageDistrobuilderConfig, String imageSourceRemote,
        String imageSourceName, String networkProfile, String profileName, String instanceConfig, String machineClassRef,
        String loadBalancerMode, String lanBridgeParent, String vmnetNetworkName, String apiEndpoint,
        String kubeconfigRef) {

    private static final String WORKSPACE_REPO_PATH = "/private/var/lib/git/nxmatic/rke2lab-management-cluster";

    public static final class Builder {
        private String workspaceDir = defaultWorkspaceDir();

        private String clusterName = "bioskop";

        private String nodeName = "master";

        private String incusProject = "rke2lab";

        private String incusDefaultRemote = "bioskop-nixos";

        private String incusRemoteAddress = "https://bioskop-nixos.local:8443";

        private String incusConfigDir = defaultIncusConfigDir();

        private String imageAlias = "control-node";

        private String imageDistrobuilderConfig = "classpath:/incus/incus-distrobuilder.yaml";

        private String imageSourceRemote = "images";

        private String imageSourceName = "debian/12";

        private String networkProfile = "rke2lab";

        private String profileName;

        private String instanceConfig = "classpath:/incus/incus-instance-config.yaml";

        private String machineClassRef = "lxc-control-default-v1";

        private String loadBalancerMode = "kube-vip";

        private String lanBridgeParent = "lan-br";

        private String vmnetNetworkName = "vmnet-br";

        private String apiEndpoint = "https://TODO:6443";

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

        public Builder imageDistrobuilderConfig(String value) {
            this.imageDistrobuilderConfig = value;
            return this;
        }

        public Builder imageSourceRemote(String value) {
            this.imageSourceRemote = value;
            return this;
        }

        public Builder imageSourceName(String value) {
            this.imageSourceName = value;
            return this;
        }

        public Builder networkProfile(String value) {
            this.networkProfile = value;
            return this;
        }

        public Builder profileName(String value) {
            this.profileName = value;
            return this;
        }

        public Builder instanceConfig(String value) {
            this.instanceConfig = value;
            return this;
        }

        public Builder machineClassRef(String value) {
            this.machineClassRef = value;
            return this;
        }

        public Builder loadBalancerMode(String value) {
            this.loadBalancerMode = value;
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
            override(environment, "image.distrobuilderConfig", this::imageDistrobuilderConfig);
            override(environment, "image.sourceRemote", this::imageSourceRemote);
            override(environment, "image.sourceName", this::imageSourceName);
            override(environment, "network.profile", this::networkProfile);
            override(environment, "profile.name", this::profileName);
            override(environment, "instance.config", this::instanceConfig);
            override(environment, "machine.classRef", this::machineClassRef);
            override(environment, "loadBalancer.mode", this::loadBalancerMode);
            override(environment, "network.lanBridgeParent", this::lanBridgeParent);
            override(environment, "network.vmnetName", this::vmnetNetworkName);
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
            final String resolvedProfileName = profileName != null ? profileName : networkProfile;
            final String resolvedKubeconfigRef = kubeconfigRef != null ? kubeconfigRef
                    : "/srv/host/kubeconfig.d/rke2-" + clusterName + ".yaml";

            return new BootstrapConfig(workspaceDir, clusterName, nodeName, incusProject, incusDefaultRemote,
                incusRemoteAddress, incusConfigDir, imageAlias, imageDistrobuilderConfig, imageSourceRemote, imageSourceName,
                networkProfile, resolvedProfileName, instanceConfig, machineClassRef, loadBalancerMode, lanBridgeParent,
                vmnetNetworkName, apiEndpoint, resolvedKubeconfigRef);
        }

        private static String defaultIncusConfigDir() {
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

        private static String defaultWorkspaceDir() {
            final String limaHostname = System.getenv("LIMA_HOSTNAME");
            if (limaHostname != null && !limaHostname.isBlank()) {
                return "/nfs/" + limaHostname + ".local" + WORKSPACE_REPO_PATH;
            }

            return "/nfs/bioskop.local" + WORKSPACE_REPO_PATH;
        }
    }

    private record EnvironmentValues(Config config) {
        @SuppressWarnings("null")
        private String raw(String key) {
            return config.get(key).orElse("");
        }
    }
}
