package io.nxmatic.rk2lab.controlplane.incus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Network;
import com.pulumi.incus.NetworkArgs;
import com.pulumi.incus.Profile;
import com.pulumi.incus.ProfileArgs;
import com.pulumi.incus.Project;
import com.pulumi.incus.ProjectArgs;
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProfilePlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProfileDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig.WorktreeHost;
import io.nxmatic.rk2lab.controlplane.incus.image.PulumiIncusImageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Provider-native Stage A bootstrap resources for the Incus management seed node.
 */
public final class IncusResourceBootstrap {

    private static final List<String> CLUSTER_NODE_NAMES = List.of("master", "peer1", "peer2", "peer3", "worker1",
            "worker2");

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final BootstrapConfig config;

    private final PulumiIncusImageProvider imageProvider;

    private final HostMountSourceVerifier hostMountSourceVerifier;

    private final NodeConfigRegenerator nodeConfigRegenerator;

    private final IncusImportLookup incusImportLookup;

    private final LaunchSecretsUpdater launchSecretsUpdater;

    public IncusResourceBootstrap(BootstrapConfig config) {
        this.config = config;
        this.imageProvider = new PulumiIncusImageProvider(config);
        this.hostMountSourceVerifier = HostMountSourceVerifier.INSTANCE;
        this.nodeConfigRegenerator = new NodeConfigRegenerator(CloudConfigSecretRenderer.INSTANCE);
        this.incusImportLookup = IncusImportLookup.INSTANCE;
        this.launchSecretsUpdater = LaunchSecretsUpdater.INSTANCE;
    }

    /**
     * Materialize seed resources directly via the Incus provider.
     */
    public BootstrapResult apply() {
        return new ApplyPipeline().resolvePaths()
                                  .prepareHostState()
                                  .prepareProviderResources()
                                  .createInstance()
                                  .toResult();
    }

    private final class ApplyPipeline {

        private BootstrapPaths localPaths;

        private BootstrapPaths nixosPaths;

        private IncusProviderContext providerContext;

        private Output<String> ensuredProjectName;

        private Output<String> ensuredProfileName;

        private Output<String> ensuredImageFingerprint;

        private Instance instance;

        private ApplyPipeline resolvePaths() {
            final Path localWorktreeRoot = config.worktreeDirOn(WorktreeHost.DARWIN);
            this.localPaths = BootstrapPaths.fromLocalWorktree(localWorktreeRoot, config.clusterName(),
                config.nodeName());
            this.nixosPaths = localPaths.asHostView(config, WorktreeHost.NIXOS);
            return this;
        }

        private ApplyPipeline prepareHostState() {
            hostMountSourceVerifier.ensureSources(localPaths);
            nodeConfigRegenerator.regenerateCloudConfigDir(localPaths.runtimeCloudConfigRoot(),
                localPaths.cloudSeedRoot());
            ensureLaunchSecretsToken(localPaths.secretsFile());
            return this;
        }

        private ApplyPipeline prepareProviderResources() {
            this.providerContext = IncusProviderContext.forBootstrap("seed-incus-provider", config);
            this.ensuredProjectName = ensureProject(providerContext);
            ensureNetwork(providerContext, config.lanBridgeParent());
            ensureNetwork(providerContext, config.vmnetNetworkName());
            this.ensuredProfileName = ensureProfile(providerContext);
            this.ensuredImageFingerprint = imageProvider.ensureSeedImageFingerprint(providerContext.invokeOptions(),
                    providerContext.provider());
            return this;
        }

        private ApplyPipeline createInstance() {
            this.instance = new Instance("seed-instance",
                    InstanceArgs.builder()
                                .name(config.nodeName())
                                .project(ensuredProjectName)
                                .image(ensuredImageFingerprint)
                                .profiles(ensuredProfileName.applyValue(List::of))
                                .config(Map.of("raw.lxc", String.join("\n",
                                    "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
                                    "lxc.apparmor.profile = unconfined",
                                    "lxc.cap.drop ="),
                                    "security.privileged", "true",
                                    "security.nesting", "true",
                                    "security.syscalls.intercept.bpf", "true",
                                    "security.syscalls.intercept.bpf.devices", "true"))
                                .running(true)
                                .devices(seedInstanceDevices(nixosPaths))
                                .build(),
                    CustomResourceOptions.builder()
                                         .provider(providerContext.provider())
                                     .ignoreChanges(List.of("image"))
                                         .build());
            return this;
        }

        private BootstrapResult toResult() {
            return new BootstrapResult("incus://" + config.incusProject() + "/" + config.nodeName(),
                    ensuredImageFingerprint, instance.status(), instance.urn(), providerContext.provider().urn());
        }
    }

    private Output<String> ensureProject(IncusProviderContext context) {
        final String existingProjectId = incusImportLookup.normalizeImportId(
                incusImportLookup.existingProjectId(context, config.incusProject()));

        final CustomResourceOptions.Builder optionsBuilder = CustomResourceOptions.builder()
                                                                                  .provider(context.provider());
        if (!existingProjectId.isBlank()) {
            optionsBuilder.importId(existingProjectId);
        }

        final Project project = new Project("seed-project", ProjectArgs.builder().name(config.incusProject()).build(),
                optionsBuilder.build());

        return project.name();
    }

    private Output<String> ensureProfile(IncusProviderContext context) {
        final String existingProfileId = incusImportLookup.normalizeImportId(
                incusImportLookup.existingProfileId(context, config.profileName(), config.incusProject()));
        final boolean profileExists = !existingProfileId.isBlank();

        final CustomResourceOptions.Builder optionsBuilder = CustomResourceOptions.builder()
                                                                                  .provider(context.provider())
                                              .retainOnDelete(true)
                                                                                  .ignoreChanges(List.of("name",
                                                                                          "project", "devices",
                                                                                          "config", "description"));

        final ProfileArgs.Builder profileArgsBuilder = ProfileArgs.builder().name(config.profileName());
        if (!profileExists) {
            profileArgsBuilder.project(config.incusProject())
                      .devices(ProfileDeviceArgs.builder()
                                .name("root")
                                .type("disk")
                                .properties(Map.of("path", "/", "pool", "default"))
                                .build());
        }

        final Profile profile = new Profile("seed-profile",
            profileArgsBuilder.build(),
                optionsBuilder.build());

        return profile.name();
    }

    private void ensureLaunchSecretsToken(Path secretsFile) {
        if (Deployment.getInstance().isDryRun()) {
            return;
        }
        launchSecretsUpdater.ensureGithubTokenPresent(secretsFile);
    }

        private List<InstanceDeviceArgs> seedInstanceDevices(BootstrapPaths hostPaths) {
        return DeviceMountPipeline.builder()
                                  .lanNic(config.lanBridgeParent())
                                  .vmnetNic(config.vmnetNetworkName())
                                  .kmsgDevice()
                                  .zfsDevice()
                      .disk("worktree.dir", hostPaths.worktreeRoot(), "/srv/host/worktree")
                      .disk("rke2lab.env.dir", hostPaths.runtimeEnvConfigRoot(),
                                          "/srv/host/environment.d")
                      .disk("rke2lab.scripts.dir", hostPaths.scriptsRoot(),
                                          "/srv/host/scripts.d")
                      .disk("git.dir", hostPaths.gitRoot(), "/srv/host/git")
                      .disk("rke2lab.system.dir", hostPaths.systemdRoot(),
                                          "/srv/host/system.d")
                      .disk("manifests.dir", hostPaths.manifestsRoot(), "/srv/host/manifests.d")
                      .disk("rke2.config.dir", hostPaths.runtimeRke2ConfigRoot(),
                                          "/srv/host/config.d")
                      .disk("shared.dir", hostPaths.shareRoot(), "/srv/host/share.d")
                      .disk("kubeconfig.dir", hostPaths.kubeconfigRoot(),
                                          "/srv/host/kubeconfig.d")
                      .disk("nocloud.dir", hostPaths.cloudSeedRoot(), "/var/lib/cloud/seed/nocloud")
                                  .build();
    }

    private record BootstrapPaths(Path worktreeRoot, Path stateRoot, Path clusterNodeRoot, Path manifestsRoot,
            Path runtimeRke2ConfigRoot, Path runtimeCloudConfigRoot, Path runtimeEnvConfigRoot, Path secretsFile,
            Path scriptsRoot, Path systemdRoot, Path gitRoot, Path shareRoot, Path kubeconfigRoot,
            Path cloudSeedRoot) {

        private static Builder builder() {
            return new Builder();
        }

        private static BootstrapPaths fromLocalWorktree(Path worktreeRoot, String clusterName, String nodeName) {
            final Path stateRoot = worktreeRoot.resolve(".local.d");
            final Path clusterNodeRoot = stateRoot.resolve("var")
                    .resolve("lib")
                    .resolve("rke2lab")
                    .resolve(clusterName)
                    .resolve(nodeName);
            final Path manifestsRoot = worktreeRoot.resolve("manifests").resolve("target").resolve("manifests.d");
            final Path runtimeRoot = manifestsRoot.resolve("runtime");

            return BootstrapPaths.builder()
                    .worktreeRoot(worktreeRoot)
                    .stateRoot(stateRoot)
                    .clusterNodeRoot(clusterNodeRoot)
                    .manifestsRoot(manifestsRoot)
                    .runtimeRke2ConfigRoot(runtimeRoot.resolve("rke2-config"))
                    .runtimeCloudConfigRoot(runtimeRoot.resolve("cloud-config"))
                    .runtimeEnvConfigRoot(runtimeRoot.resolve("env-config"))
                    .secretsFile(worktreeRoot.resolve(".secrets"))
                    .scriptsRoot(worktreeRoot.resolve("assets").resolve("incus").resolve("scripts"))
                    .systemdRoot(worktreeRoot.resolve("assets").resolve("incus").resolve("systemd"))
                    .gitRoot(worktreeRoot.getParent().getParent())
                    .shareRoot(stateRoot.resolve("share"))
                    .kubeconfigRoot(stateRoot.resolve("var").resolve("kube"))
                    .cloudSeedRoot(clusterNodeRoot.resolve("cloud.d"))
                    .build();
        }

        private BootstrapPaths asHostView(BootstrapConfig config, WorktreeHost host) {
            return BootstrapPaths.builder()
                .worktreeRoot(config.pathOn(host, worktreeRoot))
                .stateRoot(config.pathOn(host, stateRoot))
                .clusterNodeRoot(config.pathOn(host, clusterNodeRoot))
                .manifestsRoot(config.pathOn(host, manifestsRoot))
                .runtimeRke2ConfigRoot(config.pathOn(host, runtimeRke2ConfigRoot))
                .runtimeCloudConfigRoot(config.pathOn(host, runtimeCloudConfigRoot))
                .runtimeEnvConfigRoot(config.pathOn(host, runtimeEnvConfigRoot))
                .secretsFile(config.pathOn(host, secretsFile))
                .scriptsRoot(config.pathOn(host, scriptsRoot))
                .systemdRoot(config.pathOn(host, systemdRoot))
                .gitRoot(config.pathOn(host, gitRoot))
                .shareRoot(config.pathOn(host, shareRoot))
                .kubeconfigRoot(config.pathOn(host, kubeconfigRoot))
                .cloudSeedRoot(config.pathOn(host, cloudSeedRoot))
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
            private Path scriptsRoot;
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

            private Builder scriptsRoot(Path value) {
                this.scriptsRoot = value;
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
                return new BootstrapPaths(worktreeRoot, stateRoot, clusterNodeRoot, manifestsRoot,
                        runtimeRke2ConfigRoot, runtimeCloudConfigRoot, runtimeEnvConfigRoot, secretsFile, scriptsRoot,
                        systemdRoot, gitRoot, shareRoot, kubeconfigRoot, cloudSeedRoot);
            }
        }
    }

    private void ensureNetwork(IncusProviderContext context, String networkName) {
        final String existingNetworkId = incusImportLookup.normalizeImportId(
                incusImportLookup.existingNetworkId(context, networkName, config.incusProject()));

        final NetworkArgs.Builder builder = NetworkArgs.builder().name(networkName).type("bridge");

        if (networkName.equals(config.vmnetNetworkName())) {
            builder.project(config.incusProject());
        } else if (existingNetworkId.isBlank()) {
            builder.project(config.incusProject());
        }

        if (networkName.equals(config.vmnetNetworkName())) {
            builder.config(vmnetBridgeConfig());
        }

        final CustomResourceOptions.Builder optionsBuilder = CustomResourceOptions.builder()
                                                                                  .provider(context.provider())
                                                                                  .retainOnDelete(true)
                                                                                  .ignoreChanges(List.of("project"));
        if (!existingNetworkId.isBlank()) {
            optionsBuilder.importId(existingNetworkId);
        }

        new Network("seed-network-" + networkName, builder.build(), optionsBuilder.build());
    }

    private Map<String, String> vmnetBridgeConfig() {
        final ClusterNetworkBlueprint managementNodeBlueprint = ClusterNetworkBlueprint.builder()
                                                                                       .cluster(config.clusterName())
                                                                                       .node(config.nodeName())
                                                                                       .deriveRecipeModel()
                                                                                       .build();

        final String clusterGatewayWithPrefix = managementNodeBlueprint.host().clusterGatewayInetaddr().getHostAddress()
                + "/" + managementNodeBlueprint.host().clusterCidr().prefixLength();

        final String dhcpRange = managementNodeBlueprint.wan().dhcpRange();

        final String rawDnsmasq = CLUSTER_NODE_NAMES.stream()
                                                    .map(nodeName -> ClusterNetworkBlueprint.builder()
                                                                                            .cluster(
                                                                                                    config.clusterName())
                                                                                            .node(nodeName)
                                                                                            .deriveRecipeModel()
                                                                                            .build())
                                                    .map(blueprint -> "dhcp-host=" + blueprint.wan().hostMacaddr() + ","
                                                            + blueprint.nodeNetwork()
                                                                       .nodeHostInetaddr()
                                                                       .getHostAddress()
                                                            + "," + blueprint.node().name())
                                                    .reduce((left, right) -> left + "\n" + right)
                                                    .orElse("");

        return Map.of("ipv4.address", clusterGatewayWithPrefix, "ipv4.nat", "false", "ipv4.routing", "false",
                "ipv4.dhcp", "true", "ipv4.dhcp.ranges", dhcpRange, "dns.mode", "none", "bridge.driver", "native",
                "raw.dnsmasq", rawDnsmasq);
    }

    private static final class DeviceMountPipeline {

        private final List<InstanceDeviceArgs> devices = new ArrayList<>();

        private DeviceMountPipeline() {
        }

        private static DeviceMountPipeline builder() {
            return new DeviceMountPipeline();
        }

        private DeviceMountPipeline lanNic(String parentBridge) {
            return nic("lan0", Map.of("hwaddr", "10:66:6a:4c:00:00", "name", "lan0", "nictype", "bridged", "parent",
                    parentBridge));
        }

        private DeviceMountPipeline vmnetNic(String networkName) {
            return nic("vmnet0", Map.of("hwaddr", "52:54:00:00:00:00", "name", "vmnet0", "network", networkName));
        }

        private DeviceMountPipeline kmsgDevice() {
            return unixChar("kmsg.dev", "/dev/kmsg", "/dev/kmsg");
        }

        private DeviceMountPipeline zfsDevice() {
            return unixChar("zfs.dev", "/dev/zfs", "/dev/zfs");
        }

        private DeviceMountPipeline nic(String name, Map<String, String> properties) {
            devices.add(device(name, "nic", properties));
            return this;
        }

        private DeviceMountPipeline unixChar(String name, String source, String path) {
            devices.add(device(name, "unix-char", Map.of("source", source, "path", path)));
            return this;
        }

        private DeviceMountPipeline disk(String name, Path source, String path) {
            devices.add(device(name, "disk", Map.of("source", source.toString(), "path", path)));
            return this;
        }

        private List<InstanceDeviceArgs> build() {
            return List.copyOf(devices);
        }

        private static InstanceDeviceArgs device(String name, String type, Map<String, String> properties) {
            return InstanceDeviceArgs.builder().name(name).type(type).properties(properties).build();
        }
    }

    private static final class HostMountSourceVerifier {

        private static final HostMountSourceVerifier INSTANCE = new HostMountSourceVerifier();

        private HostMountSourceVerifier() {
        }

        private void ensureSources(BootstrapPaths paths) {
            ensureDirectories(List.of(paths.clusterNodeRoot(), paths.cloudSeedRoot(), paths.shareRoot(),
                paths.kubeconfigRoot()));

            final List<String> missingPaths = new ArrayList<>();

            requirePathExists(paths.secretsFile(), "required secrets file", missingPaths);
            requirePathExists(paths.scriptsRoot(), "required scripts directory", missingPaths);
            requirePathExists(paths.systemdRoot(), "required systemd directory", missingPaths);
            requirePathExists(paths.manifestsRoot(), "required generated manifests directory", missingPaths);
            requirePathExists(paths.runtimeRke2ConfigRoot(), "required runtime rke2-config directory", missingPaths);
            requirePathExists(paths.runtimeCloudConfigRoot(), "required runtime cloud-config directory", missingPaths);
            requirePathExists(paths.runtimeEnvConfigRoot(), "required runtime env-config directory", missingPaths);

            if (!missingPaths.isEmpty()) {
                throw new IllegalStateException("Missing required Stage A host source paths for Incus disk devices:\n- "
                        + String.join("\n- ", missingPaths));
            }
        }

        private void ensureDirectories(List<Path> directories) {
            for (Path directory : directories) {
                try {
                    Files.createDirectories(directory);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
                }
            }
        }

        private void requirePathExists(Path path, String purpose, List<String> missingPaths) {
            if (!Files.exists(path)) {
                missingPaths.add(path + " (" + purpose + ")");
            }
        }
    }

    private static final class NodeConfigRegenerator {

        private final CloudConfigSecretRenderer cloudConfigSecretRenderer;

        private NodeConfigRegenerator(CloudConfigSecretRenderer cloudConfigSecretRenderer) {
            this.cloudConfigSecretRenderer = cloudConfigSecretRenderer;
        }

        private void regenerateCloudConfigDir(Path sourceRoot, Path targetDir) {
            ensureDirectories(List.of(targetDir));
            clearRegularFiles(targetDir, "Failed to clear node cloud-config directory before regeneration");

            final CloudConfigSecretRenderer.CloudConfigPayload payload = cloudConfigSecretRenderer.renderFromManifestSecrets(
                    sourceRoot);
            writeCloudConfigFiles(targetDir, payload);
        }

        private void clearRegularFiles(Path directory, String failurePrefix) {
            try (Stream<Path> existing = Files.list(directory)) {
                existing.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ex) {
                        throw new IllegalStateException(failurePrefix + ": " + directory, ex);
                    }
                });
            } catch (IOException ex) {
                throw new IllegalStateException(failurePrefix + ": " + directory, ex);
            }
        }

        private void writeCloudConfigFiles(Path targetDir, CloudConfigSecretRenderer.CloudConfigPayload payload) {
            try {
                Files.writeString(targetDir.resolve("user-data"), payload.userData(), StandardCharsets.UTF_8);
                Files.writeString(targetDir.resolve("meta-data"), payload.metaData(), StandardCharsets.UTF_8);
                Files.writeString(targetDir.resolve("network-config"), payload.networkData(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to write rendered cloud-init files in: " + targetDir, ex);
            }
        }

        private void ensureDirectories(List<Path> directories) {
            for (Path directory : directories) {
                try {
                    Files.createDirectories(directory);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
                }
            }
        }
    }

    private static final class IncusImportLookup {

        private static final IncusImportLookup INSTANCE = new IncusImportLookup();

        private IncusImportLookup() {
        }

        private String existingProjectId(IncusProviderContext context, String projectName) {
            try {
                final var project = IncusFunctions.getProjectPlain(
                        GetProjectPlainArgs.builder().name(projectName).build(), context.invokeOptions()).join();
                if (project == null) {
                    return "";
                }

                final String providerId = normalizeImportId(project.id());
                if (!providerId.isBlank()) {
                    return providerId;
                }

                return normalizeImportId(project.name());
            } catch (Exception ignored) {
                return "";
            }
        }

        private String existingNetworkId(IncusProviderContext context, String networkName, String incusProject) {
            final String projectScoped = resolveNetworkImportId(context,
                    GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
            if (!projectScoped.isBlank()) {
                return projectScoped;
            }

            return resolveNetworkImportId(context, GetNetworkPlainArgs.builder().name(networkName).build());
        }

        private String existingProfileId(IncusProviderContext context, String profileName, String incusProject) {
            final String projectScoped = resolveProfileImportId(context,
                    GetProfilePlainArgs.builder().name(profileName).project(incusProject).build());
            if (!projectScoped.isBlank()) {
                return projectScoped;
            }

            return resolveProfileImportId(context, GetProfilePlainArgs.builder().name(profileName).build());
        }

        private String resolveNetworkImportId(IncusProviderContext context, GetNetworkPlainArgs args) {
            try {
                final var network = IncusFunctions.getNetworkPlain(args, context.invokeOptions()).join();
                if (network == null) {
                    return "";
                }

                final String providerId = normalizeImportId(network.id());
                if (!providerId.isBlank()) {
                    return providerId;
                }

                return normalizeImportId(network.name());
            } catch (Exception ignored) {
                return "";
            }
        }

        private String resolveProfileImportId(IncusProviderContext context, GetProfilePlainArgs args) {
            try {
                final var profile = IncusFunctions.getProfilePlain(args, context.invokeOptions()).join();
                if (profile == null) {
                    return "";
                }

                final String providerId = normalizeImportId(profile.id());
                if (!providerId.isBlank()) {
                    return providerId;
                }

                return normalizeImportId(profile.name());
            } catch (Exception ignored) {
                return "";
            }
        }

        private String normalizeImportId(String value) {
            if (value == null) {
                return "";
            }
            final String trimmed = value.trim();
            return trimmed.isBlank() ? "" : trimmed;
        }
    }

    private static final class CloudConfigSecretRenderer {

        private static final CloudConfigSecretRenderer INSTANCE = new CloudConfigSecretRenderer();

        private CloudConfigSecretRenderer() {
        }

        private CloudConfigPayload renderFromManifestSecrets(Path sourceRoot) {
            String userData = null;
            String metaData = null;
            String networkData = null;

            final List<Path> yamlSources = listYamlSources(sourceRoot);
            for (Path yamlSource : yamlSources) {
                final Map<String, Object> document = parseYamlDocument(yamlSource);
                final String kind = asString(document.get("kind"));
                final Map<String, String> payload = extractManifestPayload(kind, document);

                if (payload.containsKey("userData")) {
                    userData = payload.get("userData");
                }
                if (payload.containsKey("metaData")) {
                    metaData = payload.get("metaData");
                }
                if (payload.containsKey("networkData")) {
                    networkData = payload.get("networkData");
                }
            }

            if (userData == null || metaData == null || networkData == null) {
                throw new IllegalStateException("Runtime cloud-config source did not include all required payloads"
                        + " (userData, metaData, networkData): " + sourceRoot);
            }

            return new CloudConfigPayload(userData, metaData, networkData);
        }

        private List<Path> listYamlSources(Path sourceRoot) {
            try (Stream<Path> sourceEntries = Files.list(sourceRoot)) {
                return sourceEntries.filter(Files::isRegularFile).filter(path -> {
                    final String name = path.getFileName().toString();
                    return name.endsWith(".yml") || name.endsWith(".yaml");
                }).sorted().toList();
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Failed to regenerate node cloud-config directory from runtime cloud-config", ex);
            }
        }

        private Map<String, Object> parseYamlDocument(Path yamlSource) {
            try {
                @SuppressWarnings("unchecked")
                final Map<String, Object> parsed = YAML_MAPPER.readValue(
                        Files.readString(yamlSource, StandardCharsets.UTF_8), Map.class);
                return parsed;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to parse YAML manifest: " + yamlSource, ex);
            }
        }

        private Map<String, String> extractManifestPayload(String kind, Map<String, Object> document) {
            final Map<String, String> payload = new LinkedHashMap<>();

            if ("ConfigMap".equals(kind)) {
                payload.putAll(extractStringMap(document.get("data")));
                return payload;
            }

            if (!"Secret".equals(kind)) {
                return payload;
            }

            payload.putAll(extractStringMap(document.get("stringData")));

            final Map<String, String> data = extractStringMap(document.get("data"));
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (payload.containsKey(entry.getKey())) {
                    continue;
                }
                try {
                    payload.put(entry.getKey(),
                            new String(Base64.getDecoder().decode(entry.getValue()), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("Failed to decode Secret data key '" + entry.getKey() + "'", ex);
                }
            }

            return payload;
        }

        private Map<String, String> extractStringMap(Object value) {
            if (!(value instanceof Map<?, ?> mapValue)) {
                return Map.of();
            }

            final LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                final String key = entry.getKey() == null ? "" : entry.getKey().toString();
                final String mapValueString = entry.getValue() == null ? "" : entry.getValue().toString();
                if (!key.isBlank()) {
                    result.put(key, mapValueString);
                }
            }
            return result;
        }

        private String asString(Object value) {
            return value == null ? "" : value.toString();
        }

        private record CloudConfigPayload(String userData, String metaData, String networkData) {
        }
    }

    private static final class LaunchSecretsUpdater {

        private static final LaunchSecretsUpdater INSTANCE = new LaunchSecretsUpdater();

        private LaunchSecretsUpdater() {
        }

        private void ensureGithubTokenPresent(Path secretsFile) {
            final String githubToken = resolveGithubToken();
            if (githubToken.isBlank()) {
                return;
            }

            try {
                final String original = Files.readString(secretsFile, StandardCharsets.UTF_8);
                final String updated = upsertGithubCredentialsPreservingComments(original, githubToken);
                if (!original.equals(updated)) {
                    Files.writeString(secretsFile, updated, StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to update launch secrets file with gh token", ex);
            }
        }

        private String upsertGithubCredentialsPreservingComments(String content, String githubToken) {
            final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
            final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));

            final Pattern githubHeaderPattern = Pattern.compile("^([\\t ]*)github:\\s*(#.*)?$");
            final Pattern usernamePattern = Pattern.compile("^([\\t ]*username\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");
            final Pattern tokenPattern = Pattern.compile("^([\\t ]*token\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");

            int githubIndex = -1;
            String githubIndent = "";
            for (int i = 0; i < lines.size(); i++) {
                final Matcher matcher = githubHeaderPattern.matcher(lines.get(i));
                if (matcher.matches()) {
                    githubIndex = i;
                    githubIndent = matcher.group(1);
                    break;
                }
            }

            final String usernameValue = yamlSingleQuoted("x-access-token");
            final String tokenValue = yamlSingleQuoted(githubToken);

            if (githubIndex < 0) {
                final String childIndent = "  ";
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                    lines.add("");
                }
                lines.add("github:");
                lines.add(childIndent + "username: " + usernameValue);
                lines.add(childIndent + "token: " + tokenValue);
                return String.join(lineSeparator, lines);
            }

            final int githubIndentWidth = indentationWidth(githubIndent);
            final String childIndent = githubIndent + "  ";

            int blockStart = githubIndex + 1;
            int blockEndExclusive = lines.size();
            for (int i = blockStart; i < lines.size(); i++) {
                final String line = lines.get(i);
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    continue;
                }
                if (indentationWidth(line) <= githubIndentWidth) {
                    blockEndExclusive = i;
                    break;
                }
            }

            int usernameIndex = -1;
            int tokenIndex = -1;
            for (int i = blockStart; i < blockEndExclusive; i++) {
                final String line = lines.get(i);
                final Matcher usernameMatcher = usernamePattern.matcher(line);
                if (usernameMatcher.matches()) {
                    final String suffix = usernameMatcher.group(3) == null ? "" : usernameMatcher.group(3);
                    lines.set(i, usernameMatcher.group(1) + usernameValue + suffix);
                    usernameIndex = i;
                    continue;
                }

                final Matcher tokenMatcher = tokenPattern.matcher(line);
                if (tokenMatcher.matches()) {
                    final String suffix = tokenMatcher.group(3) == null ? "" : tokenMatcher.group(3);
                    lines.set(i, tokenMatcher.group(1) + tokenValue + suffix);
                    tokenIndex = i;
                }
            }

            int insertIndex = blockEndExclusive;
            if (usernameIndex < 0) {
                lines.add(insertIndex, childIndent + "username: " + usernameValue);
                usernameIndex = insertIndex;
                insertIndex++;
                if (tokenIndex >= insertIndex) {
                    tokenIndex++;
                }
            }

            if (tokenIndex < 0) {
                lines.add(insertIndex, childIndent + "token: " + tokenValue);
            }

            return String.join(lineSeparator, lines);
        }

        private int indentationWidth(String line) {
            int width = 0;
            while (width < line.length()) {
                final char c = line.charAt(width);
                if (c != ' ' && c != '\t') {
                    break;
                }
                width++;
            }
            return width;
        }

        private String yamlSingleQuoted(String value) {
            return "'" + value.replace("'", "''") + "'";
        }

        private String resolveGithubToken() {
            final String envToken = firstNonBlank(System.getenv("GITHUB_TOKEN"), System.getenv("GH_TOKEN"));
            if (!envToken.isBlank()) {
                return envToken;
            }
            return captureCommandOutput("gh", "auth", "token");
        }

        private String firstNonBlank(String... candidates) {
            for (String value : candidates) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return "";
        }

        private String captureCommandOutput(String... command) {
            final ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            try {
                final Process process = pb.start();
                final String output = new String(process.getInputStream().readAllBytes()).trim();
                final int exit = process.waitFor();
                return exit == 0 ? output : "";
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        }
    }

    public record BootstrapResult(String seedNodeId, Object imageFingerprint, Object instanceStatus, Object instanceUrn,
            Object providerUrn) {
    }
}
