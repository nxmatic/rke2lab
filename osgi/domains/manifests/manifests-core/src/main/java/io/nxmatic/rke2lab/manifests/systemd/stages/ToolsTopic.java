package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Tools installation stage: Nix and Flox package managers. Pushes the two installed services
 * through its {@link Sink}; downstream topics read them as {@code Supplier}s (the read-face), never
 * by holding this topic.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class ToolsTopic implements Topic.Execution {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final Sink sink;

  // The nix-install service is read back by floxInstall() within this topic (a same-topic verb
  // dependency), so it is kept as a local working field; both services are pushed through the sink.
  private @Nullable SystemdService nixInstallService;

  public ToolsTopic(SystemdChart systemdChart, SystemdSynthesisContext context, Sink sink) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.sink = sink;
  }

  /** The write-face of the tools topic — the two package-manager install services. */
  public interface Sink extends Topic.Sink {
    void nixInstall(SystemdService service);

    void floxInstall(SystemdService service);
  }

  @Override
  public String role() {
    return "tools installation";
  }

  public ToolsTopic nixInstall() {
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
    sink.nixInstall(nixInstallService);
    return this;
  }

  public ToolsTopic floxInstall() {
    final SystemdService nixInstall =
        Objects.requireNonNull(nixInstallService, "nixInstall() not yet run");
    final SystemdService floxInstallService =
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
    sink.floxInstall(floxInstallService);
    return this;
  }
}
