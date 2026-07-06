package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;

/**
 * Enforces the preflight entry gates + required commands for the run. Live delegates to {@code
 * EntryGatePolicyEnforcer} and {@code RuntimeCommandPreflight} (which read the real git worktree,
 * flake lock, and PATH); tests inject a fake that enforces nothing — the injection seam that lets
 * the scenario render offline without a clean worktree or the tools installed. Throws when a gate
 * fails, so the failing step is marked FAILED in the runbook.
 */
@FunctionalInterface
public interface PreflightProbe {
  void enforce(HostFacts hostFacts, BootedFramework framework);
}
