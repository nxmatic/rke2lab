package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;

/**
 * The live preflight probe — enforces the entry gates (git worktree + flake lock) against the
 * attached framework. The former command-availability checks (local {@code ssh}/{@code kubectl} on
 * PATH, remote {@code incus} over ssh) are gone: the hosts are provisioned by nix, so those tools
 * are present by construction — the checks guarded an invariant nix already guarantees.
 */
public final class LivePreflightProbe implements PreflightProbe {

  @Override
  public void enforce(HostFacts hostFacts, BootedFramework framework) {
    EntryGatePolicyEnforcer.enforceAll(
        hostFacts.config().localWorktreePath(),
        hostFacts.options().cleanWorktreeRequired(),
        framework);
  }
}
