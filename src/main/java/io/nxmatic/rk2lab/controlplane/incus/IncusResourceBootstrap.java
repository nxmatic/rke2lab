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
                        .devices(InstanceDeviceArgs.builder()
                                .name("vmnet0")
                                .type("nic")
                                .properties(Map.of(
                                        "nictype", "bridged",
                                        "parent", config.lanBridgeParent()
                                ))
                                .build())
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
