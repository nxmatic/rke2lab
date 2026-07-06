package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import java.util.Optional;

/**
 * Provisions the incus instance for the run. The result is an Outcome — {@link Optional} by nature:
 * present when the mutation actually ran (a live provisioning), empty when it did not (the mutation
 * was deferred). Live delegates to {@code IncusResourceBootstrap.apply} (which touches real incus
 * and needs a Pulumi deployment); tests inject a fake that returns a canned present-or-empty result
 * — the injection seam that lets the scenario render offline without provisioning anything.
 */
@FunctionalInterface
public interface IncusProbe {
  Optional<BootstrapResult> provision(HostFacts hostFacts, BootedFramework framework);
}
