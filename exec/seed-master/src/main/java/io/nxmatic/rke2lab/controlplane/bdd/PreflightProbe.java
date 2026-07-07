package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;

/**
 * Enforces the preflight entry gates for the run. Live delegates to {@code EntryGatePolicyEnforcer}
 * (which reads the real git worktree + flake lock); tests inject a fake that enforces nothing — the
 * injection seam that lets the scenario render offline without a clean worktree. Throws when a gate
 * fails, so the failing step is marked FAILED in the runbook.
 */
@FunctionalInterface
public interface PreflightProbe {
  void enforce(HostFacts hostFacts, BootedFramework framework);
}
