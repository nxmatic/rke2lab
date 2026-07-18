package io.nxmatic.rke2lab.controlplane.incus;

import com.pulumi.deployment.InvokeOptions;
import com.pulumi.incus.Provider;
import com.pulumi.incus.ProviderArgs;
import com.pulumi.incus.inputs.ProviderRemoteArgs;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;

/**
 * Shared provider wiring for the host GROW's Incus resource operations and invoke calls — the
 * {@code com.pulumi.incus.Provider} bound to the run's remote, plus the {@link InvokeOptions} every
 * {@code *Plain} import-lookup rides. Host-pure (lives outside Felix, in the {@code com.pulumi}
 * realm the GROW actualises); the scion computes nothing of this.
 */
public record IncusProviderContext(Provider provider, InvokeOptions invokeOptions) {

  public static IncusProviderContext forBootstrap(String providerName, BootstrapConfig config) {
    final ProviderRemoteArgs.Builder remoteArgsBuilder =
        ProviderRemoteArgs.builder()
            .name(config.incusDefaultRemote())
            .address(config.incusRemoteAddress().toString())
            .protocol("incus");

    final ProviderArgs.Builder providerArgsBuilder =
        ProviderArgs.builder()
            .defaultRemote(config.incusDefaultRemote())
            .acceptRemoteCertificate(false)
            .generateClientCertificates(false)
            .remotes(remoteArgsBuilder.build());
    if (config.incusConfigDir() != null && !config.incusConfigDir().toString().isBlank()) {
      providerArgsBuilder.configDir(config.incusConfigDir().toString());
    }

    final Provider provider = new Provider(providerName, providerArgsBuilder.build());

    final InvokeOptions invokeOptions = new InvokeOptions(null, provider, null);

    return new IncusProviderContext(provider, invokeOptions);
  }
}
