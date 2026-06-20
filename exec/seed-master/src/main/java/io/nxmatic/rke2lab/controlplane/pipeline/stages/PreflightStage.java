package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class PreflightStage {

  private final Path localWorktreePath;
  private final String imageBuilderHost;
  private final boolean cleanWorktreeRequired;
  private final Consumer<String> readinessLogger;
  private final OsgiRuntime osgiRuntime;

  public PreflightStage(
      Path localWorktreePath,
      String imageBuilderHost,
      boolean cleanWorktreeRequired,
      Consumer<String> readinessLogger,
      OsgiRuntime osgiRuntime) {
    this.localWorktreePath = localWorktreePath;
    this.imageBuilderHost = imageBuilderHost;
    this.cleanWorktreeRequired = cleanWorktreeRequired;
    this.readinessLogger = readinessLogger;
    this.osgiRuntime = osgiRuntime;
  }

  public PreflightStage enforceEntryGates() {
    EntryGatePolicyEnforcer.enforceAll(localWorktreePath, cleanWorktreeRequired, osgiRuntime);
    return this;
  }

  public PreflightStage requireLocalCommands(String... commands) {
    RuntimeCommandPreflight.enforceRequiredCommands(List.of(commands), readinessLogger);
    return this;
  }

  public PreflightStage requireRemoteCommand(String command) {
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        imageBuilderHost, command, readinessLogger);
    return this;
  }
}
