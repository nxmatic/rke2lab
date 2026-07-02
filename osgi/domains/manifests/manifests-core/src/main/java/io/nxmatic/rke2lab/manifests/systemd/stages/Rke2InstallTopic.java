package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Bootstrap stage: environment setup, config installation, RKE2 installation, and systemd linking.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class Rke2InstallTopic {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final ToolsTopic toolsStage;

  // Store construct references for dependency resolution
  private @Nullable SystemdService bootstrapEnvService;
  private @Nullable SystemdService installService;

  public Rke2InstallTopic(
      SystemdChart systemdChart, SystemdSynthesisContext context, ToolsTopic toolsStage) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.toolsStage = toolsStage;
  }

  public Rke2InstallTopic bootstrapEnv() {
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

  public Rke2InstallTopic configInstall() {
    final SystemdService bootstrapEnv = getBootstrapEnvService();
    new SystemdService(systemdChart, "rke2lab-config-install")
        .description("Install RKE2 config fragments before server start")
        .after(
            "local-fs.target",
            bootstrapEnv.getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName())
        .requires(
            bootstrapEnv.getUnitFileName(), toolsStage.getFloxInstallService().getUnitFileName())
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

  public Rke2InstallTopic install() {
    installService =
        new SystemdService(systemdChart, "rke2lab-install")
            .description("Run RKE2Lab Installation Script")
            .after(
                "network-online.target",
                "systemd-networkd.service",
                "local-fs.target",
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                getBootstrapEnvService().getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                getBootstrapEnvService().getUnitFileName())
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

  public Rke2InstallTopic systemdLink() {
    new SystemdService(systemdChart, "rke2lab-systemd-link")
        .description("Link RKE2Lab systemd service files from host share")
        .documentation("https://github.com/nxmatic/rke2lab")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", getBootstrapEnvService().getUnitFileName())
        .requires(getBootstrapEnvService().getUnitFileName())
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

  public Rke2InstallTopic cachixWatchStore() {
    final SystemdService install = getInstallService();
    new SystemdService(systemdChart, "rke2lab-cachix-watch-store")
        .description("Watch Nix store and push to Cachix")
        .after(toolsStage.getNixInstallService().getUnitFileName(), install.getUnitFileName())
        .requires(toolsStage.getNixInstallService().getUnitFileName(), install.getUnitFileName())
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
    return Objects.requireNonNull(bootstrapEnvService, "bootstrapEnv() not yet run");
  }

  /** Package-private accessor for storage stage dependency. */
  public SystemdService getInstallService() {
    return Objects.requireNonNull(installService, "install() not yet run");
  }
}
