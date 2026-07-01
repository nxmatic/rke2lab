package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Tools installation stage: Nix and Flox package managers.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class ToolsStage {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;

  // Store construct references for dependency resolution
  private @Nullable SystemdService nixInstallService;
  private @Nullable SystemdService floxInstallService;

  public ToolsStage(SystemdChart systemdChart, SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.context = context;
  }

  public ToolsStage nixInstall() {
    nixInstallService =
        new SystemdService(systemdChart, "rke2lab-nix-install")
            .description("Install Nix Package Manager for RKE2 Lab")
            .after(context.networkTarget().getUnitFileName())
            .requires(context.networkTarget().getUnitFileName())
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-nix-install.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.toolsTarget().getUnitFileName())
            .wantedBy(context.toolsTarget().getUnitFileName());
    return this;
  }

  public ToolsStage floxInstall() {
    final SystemdService nixInstall = getNixInstallService();
    floxInstallService =
        new SystemdService(systemdChart, "rke2lab-flox-install")
            .description("Install Flox Package Manager for RKE2 Lab")
            .after(nixInstall.getUnitFileName())
            .requires(nixInstall.getUnitFileName())
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-flox-install.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.toolsTarget().getUnitFileName())
            .wantedBy(context.toolsTarget().getUnitFileName());
    return this;
  }

  /** Package-private accessor for bootstrap stage dependency. */
  public SystemdService getFloxInstallService() {
    return Objects.requireNonNull(floxInstallService, "floxInstall() not yet run");
  }

  /** Package-private accessor for bootstrap stage dependency. */
  public SystemdService getNixInstallService() {
    return Objects.requireNonNull(nixInstallService, "nixInstall() not yet run");
  }
}
