package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import java.util.Optional;

/**
 * The live incus probe — transposes {@code IncusTopic.provisionInstance}: constructs {@code
 * IncusResourceBootstrap} against the attached framework and applies it, touching real incus. The
 * result is always present here (a live provisioning produced a {@link BootstrapResult}); the empty
 * case is the deferred path, carried by a fake, never by this live probe.
 */
public final class LiveIncusProbe implements IncusProbe {

  @Override
  public Optional<BootstrapResult> provision(HostFacts hostFacts, BootedFramework framework) {
    return Optional.of(
        new IncusResourceBootstrap(hostFacts.config(), framework).apply(hostFacts.policy()));
  }
}
