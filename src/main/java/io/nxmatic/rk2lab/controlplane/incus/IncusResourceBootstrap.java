package io.nxmatic.rk2lab.controlplane.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pulumi.core.Output;
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
import io.nxmatic.rk2lab.controlplane.incus.image.PulumiIncusImageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider-native Stage A bootstrap resources for the Incus management seed node.
 */
public final class IncusResourceBootstrap {

    private final BootstrapConfig config;
    private final PulumiIncusImageProvider imageProvider;

    public IncusResourceBootstrap(BootstrapConfig config) {
        this.config = config;
        this.imageProvider = new PulumiIncusImageProvider(config);
    }

    /**
     * Materialize seed resources directly via the Incus provider.
     */
    public BootstrapResult apply() {
        final String workspace = Path.of(config.workspaceDir()).toAbsolutePath().normalize().toString();
        final String localRoot = workspace + "/.local.d";
        final String clusterNodeRoot = workspace + "/rke2.d/" + config.clusterName() + "/" + config.nodeName();
        final String gitRoot = Path.of(workspace).getParent().getParent().toString();

        ensureHostMountSources(workspace, localRoot, clusterNodeRoot);
        ensureLaunchSecretsToken(workspace + "/.secrets");

        final IncusProviderContext providerContext = IncusProviderContext.forBootstrap("seed-incus-provider", config);

        final Output<String> ensuredProjectName = ensureProject(providerContext);
        ensureNetwork(providerContext, config.lanBridgeParent());
        ensureNetwork(providerContext, config.vmnetNetworkName());
        final Output<String> ensuredProfileName = ensureProfile(providerContext);
        final Output<String> ensuredImageFingerprint = imageProvider.ensureSeedImageFingerprint(
                providerContext.invokeOptions(),
                providerContext.provider()
        );

        final Instance instance = new Instance(
                "seed-instance",
                InstanceArgs.builder()
                        .name(config.nodeName())
                        .project(ensuredProjectName)
                        .image(ensuredImageFingerprint)
                        .profiles(ensuredProfileName.applyValue(List::of))
                        .config(Map.of(
                                "raw.lxc", "lxc.mount.auto = proc:rw sys:rw",
                                "security.privileged", "true",
                                "security.nesting", "true"
                        ))
                        .running(true)
                        .devices(seedInstanceDevices(workspace, localRoot, clusterNodeRoot, gitRoot))
                        .build(),
                CustomResourceOptions.builder()
                        .provider(providerContext.provider())
                        .ignoreChanges(List.of("image", "config"))
                        .build()
        );

        return new BootstrapResult(
                "incus://" + config.incusProject() + "/" + config.nodeName(),
                ensuredImageFingerprint,
                instance.status()
        );
    }

    private Output<String> ensureProject(IncusProviderContext context) {
        try {
            IncusFunctions.getProjectPlain(
                    GetProjectPlainArgs.builder()
                            .name(config.incusProject())
                            .build(),
                    context.invokeOptions()
            ).join();
            return Output.of(config.incusProject());
        } catch (Exception ignored) {
            final Project project = new Project(
                    "seed-project",
                    ProjectArgs.builder()
                            .name(config.incusProject())
                            .build(),
                    CustomResourceOptions.builder()
                            .provider(context.provider())
                            .build()
            );
            return project.name();
        }
    }

        private Output<String> ensureProfile(IncusProviderContext context) {
        try {
            IncusFunctions.getProfilePlain(
                    GetProfilePlainArgs.builder()
                            .name(config.profileName())
                            .project(config.incusProject())
                            .build(),
                    context.invokeOptions()
            ).join();
            return Output.of(config.profileName());
        } catch (Exception ignored) {
            final Profile profile = new Profile(
                    "seed-profile",
                    ProfileArgs.builder()
                            .name(config.profileName())
                            .project(config.incusProject())
                            .devices(ProfileDeviceArgs.builder()
                                    .name("root")
                                    .type("disk")
                                    .properties(Map.of(
                                            "path", "/",
                                            "pool", "default"
                                    ))
                                    .build())
                            .build(),
                    CustomResourceOptions.builder()
                            .provider(context.provider())
                            .build()
            );
            return profile.name();
        }
    }

    private void ensureHostMountSources(String workspace, String localRoot, String clusterNodeRoot) {
        ensureDirectories(List.of(
                clusterNodeRoot,
                clusterNodeRoot + "/config.d",
                clusterNodeRoot + "/manifests.d",
                localRoot + "/share",
                localRoot + "/var/kube"
        ));

        final List<String> missingPaths = new ArrayList<>();

        requirePathExists(workspace + "/.secrets", "required secrets file", missingPaths);
        requirePathExists(workspace + "/assets/incus/scripts", "required scripts directory", missingPaths);
        requirePathExists(workspace + "/assets/incus/systemd", "required systemd directory", missingPaths);
        requirePathExists(clusterNodeRoot + "/environment", "required environment file", missingPaths);
        requirePathExists(clusterNodeRoot + "/meta-data", "required cloud-init meta-data file", missingPaths);
        requirePathExists(clusterNodeRoot + "/user-data", "required cloud-init user-data file", missingPaths);
        requirePathExists(clusterNodeRoot + "/network-config", "required cloud-init network-config file", missingPaths);

        if (!missingPaths.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required Stage A host source paths for Incus disk devices:\n- "
                            + String.join("\n- ", missingPaths)
            );
        }
    }

    private void ensureLaunchSecretsToken(String secretsFile) {
        final String githubToken = resolveGithubToken();
        if (githubToken.isBlank()) {
            return;
        }

        try {
            final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            final Path secretsPath = Path.of(secretsFile);
            final JsonNode rootNode = yaml.readTree(secretsPath.toFile());
            final ObjectNode root = rootNode instanceof ObjectNode
                    ? (ObjectNode) rootNode
                    : yaml.createObjectNode();

            final JsonNode githubNode = root.get("github");
            final ObjectNode github = githubNode instanceof ObjectNode
                    ? (ObjectNode) githubNode
                    : root.putObject("github");

            github.put("username", "x-access-token");
            github.put("token", githubToken);

            final String rendered = yaml.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(secretsPath, rendered, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to update launch secrets file with gh token", ex);
        }
    }

    private String resolveGithubToken() {
        final String envToken = firstNonBlank(
                System.getenv("GITHUB_TOKEN"),
                System.getenv("GH_TOKEN")
        );
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

    private void ensureDirectories(List<String> directories) {
        for (String directory : directories) {
            try {
                Files.createDirectories(Path.of(directory));
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
            }
        }
    }

    private void requirePathExists(String path, String purpose, List<String> missingPaths) {
        if (!pathExists(path)) {
            missingPaths.add(path + " (" + purpose + ")");
        }
    }

    private boolean pathExists(String path) {
        final Path primary = Path.of(path);
        if (Files.exists(primary)) {
            return true;
        }

        final String normalized = primary.toAbsolutePath().normalize().toString();
        if (!normalized.startsWith("/net/")) {
            return false;
        }

        final int privateIndex = normalized.indexOf("/private/");
        if (privateIndex < 0) {
            return false;
        }

        final String privatePath = normalized.substring(privateIndex);
        final Path privateCandidate = Path.of(privatePath).normalize();
        if (Files.exists(privateCandidate)) {
            return true;
        }

        final String withoutPrivatePrefix = privatePath.substring("/private".length());
        final Path directCandidate = Path.of(withoutPrivatePrefix).normalize();
        return Files.exists(directCandidate);
    }

    private List<InstanceDeviceArgs> seedInstanceDevices(String workspace, String localRoot,
            String clusterNodeRoot,
            String gitRoot) {

        final List<InstanceDeviceArgs> devices = new ArrayList<>();
        devices.add(device("lan0", "nic", Map.of(
                "hwaddr", "10:66:6a:4c:00:00",
                "name", "lan0",
                "nictype", "bridged",
                "parent", config.lanBridgeParent()
        )));
        devices.add(device("vmnet0", "nic", Map.of(
                "hwaddr", "52:54:00:00:00:00",
                "name", "vmnet0",
                "network", config.vmnetNetworkName()
        )));
        devices.add(device("kmsg.dev", "unix-char", Map.of(
                "source", "/dev/kmsg",
                "path", "/dev/kmsg"
        )));
        devices.add(device("zfs.dev", "unix-char", Map.of(
                "source", "/dev/zfs",
                "path", "/dev/zfs"
        )));
        devices.add(device("secrets.file", "disk", Map.of(
                "source", workspace + "/.secrets",
                "path", "/srv/host/.secrets"
        )));
        devices.add(device("rke2lab.env.file", "disk", Map.of(
                "source", clusterNodeRoot + "/environment",
                "path", "/srv/host/environment"
        )));
        devices.add(device("rke2lab.scripts.dir", "disk", Map.of(
                "source", workspace + "/assets/incus/scripts",
                "path", "/srv/host/scripts.d"
        )));
        devices.add(device("git.dir", "disk", Map.of(
                "source", gitRoot,
                "path", "/srv/host/git"
        )));
        devices.add(device("rke2lab.system.dir", "disk", Map.of(
                "source", workspace + "/assets/incus/systemd",
                "path", "/srv/host/system.d"
        )));
        devices.add(device("manifests.dir", "disk", Map.of(
                "source", clusterNodeRoot + "/manifests.d",
                "path", "/srv/host/manifests.d"
        )));
        devices.add(device("rke2.config.dir", "disk", Map.of(
                "source", clusterNodeRoot + "/config.d",
                "path", "/srv/host/config.d"
        )));
        devices.add(device("shared.dir", "disk", Map.of(
                "source", localRoot + "/share",
                "path", "/srv/host/share.d"
        )));
        devices.add(device("kubeconfig.dir", "disk", Map.of(
                "source", localRoot + "/var/kube",
                "path", "/srv/host/kubeconfig.d"
        )));

        devices.add(device("user.metadata", "disk", Map.of(
                "source", clusterNodeRoot + "/meta-data",
                "path", "/var/lib/cloud/seed/nocloud/meta-data"
        )));
        devices.add(device("user.user-data", "disk", Map.of(
                "source", clusterNodeRoot + "/user-data",
                "path", "/var/lib/cloud/seed/nocloud/user-data"
        )));
        devices.add(device("user.network-config", "disk", Map.of(
                "source", clusterNodeRoot + "/network-config",
                "path", "/var/lib/cloud/seed/nocloud/network-config"
        )));
        return List.copyOf(devices);
    }

    private static InstanceDeviceArgs device(String name, String type, Map<String, String> properties) {
        return InstanceDeviceArgs.builder()
                .name(name)
                .type(type)
                .properties(properties)
                .build();
    }

        private void ensureNetwork(IncusProviderContext context, String networkName) {
        try {
            IncusFunctions.getNetworkPlain(
                    GetNetworkPlainArgs.builder()
                            .name(networkName)
                            .project(config.incusProject())
                            .build(),
                    context.invokeOptions()
            ).join();
        } catch (Exception ex) {
            new Network(
                    "seed-network-" + networkName,
                    NetworkArgs.builder()
                            .name(networkName)
                            .type("bridge")
                            .project(config.incusProject())
                            .build(),
                    CustomResourceOptions.builder()
                            .provider(context.provider())
                            .build()
            );
        }
    }

    public record BootstrapResult(String seedNodeId, Object imageFingerprint, Object instanceStatus) {
    }
}
