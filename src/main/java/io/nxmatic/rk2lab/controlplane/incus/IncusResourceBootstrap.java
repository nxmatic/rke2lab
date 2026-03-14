package io.nxmatic.rk2lab.controlplane.incus;

import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Provider;
import com.pulumi.incus.ProviderArgs;
import com.pulumi.incus.inputs.GetImagePlainArgs;
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProfilePlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProviderRemoteArgs;
import com.pulumi.incus.outputs.GetImageResult;
import com.pulumi.deployment.InvokeOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider-native Stage A bootstrap resources for the Incus management seed node.
 */
public final class IncusResourceBootstrap {

    private final BootstrapConfig config;

    public IncusResourceBootstrap(BootstrapConfig config) {
        this.config = config;
    }

    /**
     * Materialize seed resources directly via the Incus provider.
     */
    public BootstrapResult apply() {
                final ClasspathAsset distrobuilderAsset = ClasspathAsset.load(config.imageDistrobuilderConfig());
                final ClasspathAsset instanceConfigAsset = ClasspathAsset.load(config.instanceConfig());

                final String workspace = Path.of(config.workspaceDir()).toAbsolutePath().normalize().toString();
                final String localRoot = workspace + "/.local.d";
                final String clusterNodeRoot = workspace + "/rke2.d/" + config.clusterName() + "/" + config.nodeName();
                final String gitRoot = Path.of(workspace).getParent().getParent().toString();

        final ProviderRemoteArgs.Builder remoteArgsBuilder = ProviderRemoteArgs.builder()
                .name(config.incusDefaultRemote())
                .address(config.incusRemoteAddress())
                .protocol("incus");

        final ProviderArgs.Builder providerArgsBuilder = ProviderArgs.builder()
                .defaultRemote(config.incusDefaultRemote())
                .acceptRemoteCertificate(false)
                .generateClientCertificates(false)
                .remotes(remoteArgsBuilder.build());
        if (config.incusConfigDir() != null && !config.incusConfigDir().isBlank()) {
            providerArgsBuilder.configDir(config.incusConfigDir());
        }

        final Provider incusProvider = new Provider(
                "seed-incus-provider",
                providerArgsBuilder.build()
        );

        final InvokeOptions invokeOptions = new InvokeOptions(
                null,
                incusProvider,
                null
        );

        requireProject(invokeOptions);
        requireNetwork(invokeOptions);
        requireProfile(invokeOptions);
        final GetImageResult image = requireImage(invokeOptions);

        final Instance instance = new Instance(
                "seed-instance",
                InstanceArgs.builder()
                        .name(config.nodeName())
                        .project(config.incusProject())
                        .image(image.fingerprint())
                        .profiles(List.of(config.profileName()))
                        .config(Map.of(
                                "user.rke2lab.asset.distrobuilder.resource", distrobuilderAsset.resourcePath(),
                                "user.rke2lab.asset.distrobuilder.sha256", distrobuilderAsset.sha256(),
                                "user.rke2lab.asset.instance.resource", instanceConfigAsset.resourcePath(),
                                "user.rke2lab.asset.instance.sha256", instanceConfigAsset.sha256()
                        ))
                        .running(true)
                        .devices(seedInstanceDevices(workspace, localRoot, clusterNodeRoot, gitRoot))
                        .build(),
                CustomResourceOptions.builder()
                        .provider(incusProvider)
                        .ignoreChanges(List.of("image", "config"))
                        .build()
        );

        return new BootstrapResult(
                "incus://" + config.incusProject() + "/" + config.nodeName(),
                image.fingerprint(),
                instance.status(),
                distrobuilderAsset.uri(),
                distrobuilderAsset.sha256(),
                instanceConfigAsset.uri(),
                instanceConfigAsset.sha256()
        );
    }

                private List<InstanceDeviceArgs> seedInstanceDevices(String workspace, String localRoot,
                                                                                                                                                                                                                                  String clusterNodeRoot,
                                                                                                                                                                                                                                  String gitRoot) {

        final List<InstanceDeviceArgs> devices = new ArrayList<>();
        devices.add(device("vmnet0", "nic", Map.of(
                "nictype", "bridged",
                "parent", config.lanBridgeParent()
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

        private void requireProject(InvokeOptions invokeOptions) {
                try {
                        IncusFunctions.getProjectPlain(
                                        GetProjectPlainArgs.builder()
                                                        .name(config.incusProject())
                                                        .build(),
                                        invokeOptions
                        ).join();
                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Required Incus project not found: " + config.incusProject(),
                                        ex
                        );
                }
        }

        private void requireNetwork(InvokeOptions invokeOptions) {
                try {
                        IncusFunctions.getNetworkPlain(
                                        GetNetworkPlainArgs.builder()
                                                        .name(config.lanBridgeParent())
                                                        .build(),
                                        invokeOptions
                        ).join();
                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Required Incus bridge/network not found: " + config.lanBridgeParent(),
                                        ex
                        );
                }
        }

        private void requireProfile(InvokeOptions invokeOptions) {
                try {
                        IncusFunctions.getProfilePlain(
                                        GetProfilePlainArgs.builder()
                                                        .name(config.profileName())
                                                        .project(config.incusProject())
                                                        .build(),
                                        invokeOptions
                        ).join();
                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Required Incus profile not found: " + config.profileName() + " in project " + config.incusProject(),
                                        ex
                        );
                }
        }

        private GetImageResult requireImage(InvokeOptions invokeOptions) {
                try {
                        return IncusFunctions.getImagePlain(
                                        GetImagePlainArgs.builder()
                                                        .name(config.imageAlias())
                                                        .project(config.incusProject())
                                                        .build(),
                                        invokeOptions
                        ).join();
                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Required Incus image alias not found: " + config.imageAlias() + " in project " + config.incusProject(),
                                        ex
                        );
                }
        }

        public record BootstrapResult(String seedNodeId, Object imageFingerprint, Object instanceStatus,
                                                                  String distrobuilderAssetUri, String distrobuilderAssetSha256,
                                                                  String instanceConfigAssetUri, String instanceConfigAssetSha256) {
    }
}
