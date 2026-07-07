package io.nxmatic.rke2lab.controlplane.incus;

import com.pulumi.deployment.InvokeOptions;
import com.pulumi.incus.Provider;
import com.pulumi.incus.ProviderArgs;
import com.pulumi.incus.inputs.ProviderRemoteArgs;
import io.nxmatic.rke2lab.config.port.BootstrapConfig;

/** Shared provider wiring for Incus resource operations and invoke calls. */
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
