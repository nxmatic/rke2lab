package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.pipeline.Topic;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Preflight gate — enforces entry gates and required commands. Produces no output. */
public final class PreflightTopic implements Topic.Execution {

  private final Path localWorktreePath;
  private final String imageBuilderHost;
  private final boolean cleanWorktreeRequired;
  private final Consumer<String> readinessLogger;
  private final BootedFramework bootedFramework;

  public PreflightTopic(
      Path localWorktreePath,
      String imageBuilderHost,
      boolean cleanWorktreeRequired,
      Consumer<String> readinessLogger,
      BootedFramework bootedFramework) {
    this.localWorktreePath = localWorktreePath;
    this.imageBuilderHost = imageBuilderHost;
    this.cleanWorktreeRequired = cleanWorktreeRequired;
    this.readinessLogger = readinessLogger;
    this.bootedFramework = bootedFramework;
  }

  @Override
  public String role() {
    return "preflight";
  }

  public PreflightTopic enforceEntryGates() {
    EntryGatePolicyEnforcer.enforceAll(localWorktreePath, cleanWorktreeRequired, bootedFramework);
    return this;
  }

  public PreflightTopic requireLocalCommands(String... commands) {
    RuntimeCommandPreflight.enforceRequiredCommands(List.of(commands), readinessLogger);
    return this;
  }

  public PreflightTopic requireRemoteCommand(String command) {
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        imageBuilderHost, command, readinessLogger);
    return this;
  }
}
