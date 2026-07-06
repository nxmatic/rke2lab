package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.pipeline.stages.RuntimeCommandPreflight;
import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import java.util.List;

/**
 * The live preflight probe — transposes {@code PreflightTopic}'s three enforcement calls: the entry
 * gates (against the attached framework), the required local commands, and the remote command on
 * the image builder host. Reads the real git worktree, flake lock, and PATH.
 */
public final class LivePreflightProbe implements PreflightProbe {

  @Override
  public void enforce(HostFacts hostFacts, BootedFramework framework) {
    EntryGatePolicyEnforcer.enforceAll(
        hostFacts.config().localWorktreePath(),
        hostFacts.options().cleanWorktreeRequired(),
        framework);
    RuntimeCommandPreflight.enforceRequiredCommands(
        List.of("ssh", "kubectl"), hostFacts.readinessLogger());
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        hostFacts.config().imageBuilderHost(), "incus", hostFacts.readinessLogger());
  }
}
