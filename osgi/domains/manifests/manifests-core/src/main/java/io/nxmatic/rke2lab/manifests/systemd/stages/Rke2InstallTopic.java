package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Bootstrap stage: environment setup, config installation, RKE2 installation, and systemd linking.
 * Reads the tools topic's flox-install service through a {@code Supplier} read-face (never by
 * holding the tools topic); pushes its own bootstrap-env and install services through its {@link
 * Sink}.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class Rke2InstallTopic implements Topic.Execution {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final Supplier<SystemdService> nixInstall;
  private final Supplier<SystemdService> floxInstall;
  private final Sink sink;

  // bootstrapEnv/install are read back by later verbs of THIS topic (same-topic dependency), so
  // they are kept as local working fields in addition to being pushed through the sink.
  private @Nullable SystemdService bootstrapEnvService;
  private @Nullable SystemdService installService;

  public Rke2InstallTopic(
      SystemdChart systemdChart,
      SystemdSynthesisContext context,
      Supplier<SystemdService> nixInstall,
      Supplier<SystemdService> floxInstall,
      Sink sink) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.nixInstall = nixInstall;
    this.floxInstall = floxInstall;
    this.sink = sink;
  }

  /** The write-face of the rke2-install topic — the services later topics depend on. */
  public interface Sink extends Topic.Sink {
    void bootstrapEnv(SystemdService service);

    void install(SystemdService service);
  }

  @Override
  public String role() {
    return "rke2 install";
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
                floxInstall.get().getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                floxInstall.get().getUnitFileName())
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
    sink.bootstrapEnv(bootstrapEnvService);
    return this;
  }

  public Rke2InstallTopic configInstall() {
    final SystemdService bootstrapEnv = getBootstrapEnvService();
    new SystemdService(systemdChart, "rke2lab-config-install")
        .description("Install RKE2 config fragments before server start")
        .after(
            "local-fs.target", bootstrapEnv.getUnitFileName(), floxInstall.get().getUnitFileName())
        .requires(bootstrapEnv.getUnitFileName(), floxInstall.get().getUnitFileName())
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
    sink.install(installService);
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
    final SystemdService nix = nixInstall.get();
    new SystemdService(systemdChart, "rke2lab-cachix-watch-store")
        .description("Watch Nix store and push to Cachix")
        .after(nix.getUnitFileName(), install.getUnitFileName())
        .requires(nix.getUnitFileName(), install.getUnitFileName())
        .type(ServiceType.SIMPLE)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-cachix-watch-store.sh")
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  /** Same-topic accessor: later verbs of this topic read the bootstrap-env service. */
  private SystemdService getBootstrapEnvService() {
    return Objects.requireNonNull(bootstrapEnvService, "bootstrapEnv() not yet run");
  }

  /** Same-topic accessor: {@code cachixWatchStore()} reads the install service. */
  private SystemdService getInstallService() {
    return Objects.requireNonNull(installService, "install() not yet run");
  }
}
