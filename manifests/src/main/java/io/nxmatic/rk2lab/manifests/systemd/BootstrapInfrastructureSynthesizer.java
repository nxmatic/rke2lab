// @codebase
package io.nxmatic.rk2lab.manifests.systemd;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthesizes bootstrap and infrastructure systemd units via a fluent pipeline.
 *
 * <p>These units are cross-cutting infrastructure that don't belong to any specific domain:
 * bootstrap environment, Nix/Flox installation, networking setup, storage configuration, etc.
 *
 * <p>Uses the fluent pipeline grammar to enforce proper ordering across four topics: tools
 * installation, bootstrap environment, network infrastructure, and storage/system configuration.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the pattern this follows.
 */
public final class BootstrapInfrastructureSynthesizer {

  private static final Logger LOG =
      LoggerFactory.getLogger(BootstrapInfrastructureSynthesizer.class);

  private final SystemdChart systemdChart;
  private final io.nxmatic.rk2lab.manifests.SystemdSynthesisContext context;

  public BootstrapInfrastructureSynthesizer(
      SystemdChart systemdChart, io.nxmatic.rk2lab.manifests.SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.context = context;
  }

  /**
   * Synthesizes all bootstrap and infrastructure units via the fluent pipeline.
   *
   * <p>Enforces proper ordering: tools → bootstrap → network → storage/system.
   */
  public void synthesizeAll() {
    SynthesisPipeline.forChart(systemdChart, context)
        .onFailure((topic, cause) -> LOG.error("Synthesis failed at topic: {}", topic, cause))
        .during("tools installation", tools -> tools.nixInstall().floxInstall())
        .then()
        .during(
            "bootstrap",
            bootstrap ->
                bootstrap.bootstrapEnv().configInstall().install().systemdLink().cachixWatchStore())
        .then()
        .during(
            "network infrastructure",
            network -> network.routeCleanup().networkConfig().networkWait().networkDebug())
        .then()
        .during(
            "storage and system",
            storage ->
                storage
                    .remountShared()
                    .containerdZfsMountConfig()
                    .dbusTcpSystemBus()
                    .zfsEarlyUmount()
                    .vipKubeconfig())
        .complete();

    // Note: rke2lab.target dependencies are set in DefaultManifestSynthesisService
    // after all targets and services are created
  }
}
