package io.nxmatic.rk2lab.manifests.systemd.stages;

import io.nxmatic.rk2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;

/**
 * Bootstrap stage: environment setup, config installation, RKE2 installation, and systemd linking.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class BootstrapStage {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final ToolsStage toolsStage;

  // Store construct references for dependency resolution
  private SystemdService bootstrapEnvService;
  private SystemdService installService;

  public BootstrapStage(
      SystemdChart systemdChart, SystemdSynthesisContext context, ToolsStage toolsStage) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.toolsStage = toolsStage;
  }

  public BootstrapStage bootstrapEnv() {
    bootstrapEnvService =
        new SystemdService(systemdChart, "rke2lab-bootstrap-env")
            .description("RKE2Lab bootstrap environment (Nix + Flox + nocloud)")
            .documentation("https://github.com/nxmatic/rke2lab")
            .after(
                "network-online.target",
                "systemd-networkd.service",
                "local-fs.target",
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                toolsStage.getFloxInstallService().getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                toolsStage.getFloxInstallService().getUnitFileName())
            .requiresMountsFor(
                "/srv/host/systemd-scripts.d",
                "/srv/host/rke2lab-environment.d",
                "/srv/host/rke2lab-worktree.d")
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-bootstrap-env.sh",
                "/srv/host/systemd-scripts.d/rke2lab-env-load.sh")
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-bootstrap-env.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.bootstrapTarget().getUnitFileName())
            .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  public BootstrapStage configInstall() {
    new SystemdService(systemdChart, "rke2lab-config-install")
        .description("Install RKE2 config fragments before server start")
        .after(
            "local-fs.target",
            bootstrapEnvService.getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName())
        .requires(
            bootstrapEnvService.getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .conditionPathExists("/srv/host/systemd-scripts.d/rke2lab-config-install.sh")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-config-install.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  public BootstrapStage install() {
    installService =
        new SystemdService(systemdChart, "rke2lab-install")
            .description("Run RKE2Lab Installation Script")
            .after(
                "network-online.target",
                "systemd-networkd.service",
                "local-fs.target",
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                bootstrapEnvService.getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                bootstrapEnvService.getUnitFileName())
            .requiresMountsFor("/srv/host/systemd-scripts.d")
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-install.sh",
                "!/etc/systemd/system/rke2-server.service",
                "!/etc/systemd/system/rke2-agent.service")
            .type(ServiceType.ONESHOT)
            .execStartPre("/srv/host/systemd-scripts.d/rke2lab-install-pre.sh")
            .execStart("/srv/host/systemd-scripts.d/rke2lab-install.sh")
            .execStartPost("/srv/host/systemd-scripts.d/rke2lab-install-post.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.bootstrapTarget().getUnitFileName())
            .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  public BootstrapStage systemdLink() {
    new SystemdService(systemdChart, "rke2lab-systemd-link")
        .description("Link RKE2Lab systemd service files from host share")
        .documentation("https://github.com/nxmatic/rke2lab")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", bootstrapEnvService.getUnitFileName())
        .requires(bootstrapEnvService.getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh", "/srv/host/systemd-units.d")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  public BootstrapStage cachixWatchStore() {
    new SystemdService(systemdChart, "rke2lab-cachix-watch-store")
        .description("Watch Nix store and push to Cachix")
        .after(
            toolsStage.getNixInstallService().getUnitFileName(), installService.getUnitFileName())
        .requires(
            toolsStage.getNixInstallService().getUnitFileName(), installService.getUnitFileName())
        .type(ServiceType.SIMPLE)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-cachix-watch-store.sh")
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  /** Package-private accessor for storage stage dependency. */
  public SystemdService getBootstrapEnvService() {
    return bootstrapEnvService;
  }

  /** Package-private accessor for storage stage dependency. */
  public SystemdService getInstallService() {
    return installService;
  }
}
