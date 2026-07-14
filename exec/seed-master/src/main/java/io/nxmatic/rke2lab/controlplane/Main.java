package io.nxmatic.rke2lab.controlplane;

import com.pulumi.Pulumi;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSeedScenario;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.bdd.SeedRun;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The Pulumi entry point — Layer 1 of the amorce (see
 * docs/architecture/osgi/seed-bdd-module-spec.adoc § the amorce). The whole program runs inside
 * {@code Pulumi.run}: the {@code com.pulumi} graph cannot enter Felix, and the run's facts — the
 * {@link RunMode} (from {@code isDryRun}), the {@link Parcel} (project/stack), the derived {@link
 * BootstrapConfig} (from the Pulumi {@code Config}) — are only knowable here. {@code Main} captures
 * them into a {@link SeedRun} and plays {@link ClusterSeedScenario} on the launcher, seeding the
 * {@code SeedRun} through the session store; the scenario's GIVEN (Layer 2) bootstraps the open
 * gardening from it.
 *
 * <p>Nothing else lives here: opening the gardening, publishing the RunGate, building the Cellar
 * are the GIVEN's narrated work. {@code Main} is the thin Pulumi envelope that captures what only
 * it can know and hands it across.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    Pulumi.run(
        context -> {
          final RunMode runMode = RunMode.detect(true);
          final Rke2labConfig config = Rke2labConfig.from(ConfigLoader.of(context.config()));
          final BootstrapConfig bootstrap = BootstrapConfig.from(config);
          final Parcel parcel = new Parcel(context.projectName(), context.stackName());
          final SeedRun run =
              new SeedRun(
                  runMode,
                  parcel,
                  bootstrap,
                  config.entryGate().cleanWorktreeRequired().orElse(true));

          try {
            new JUnitLauncherCore<Boolean>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
                    (launcher, request) -> {
                      launcher.execute(request);
                      return Boolean.TRUE;
                    },
                    ClusterSeedScenario.SEED.into(run));
            // Render the runbook (adoc + json) into the run's staging slot AFTER the play, from the
            // model the scenario stashed — the two-channel rule: the runbook is narration,
            // materialised
            // post-run. Best-effort inside the renderer, so it never fails the provisioning.
            new RunbookRenderer(
                    ClusterSeedScenario.lastStagingRoot(), line -> context.log().info(line))
                .render(ClusterSeedScenario.lastRunbook());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the cluster-seed run was interrupted", interrupted);
          }
        });
  }
}
