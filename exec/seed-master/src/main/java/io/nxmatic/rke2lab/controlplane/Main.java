package io.nxmatic.rke2lab.controlplane;

import com.pulumi.Pulumi;
import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSeedScenario;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.bdd.SeedRun;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ScenarioGraft;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.nxmatic.rke2lab.pulumi.edge.PulumiDeploymentSeed;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
                  config.entryGate().cleanWorktreeRequired().orElse(true),
                  UUID.randomUUID().toString());

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
                    ClusterSeedScenario.SEED
                        .into(run)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(run.txId()))
                        // The live Pulumi deployment, seeded onto the launcher worker so the GROW
                        // beat's com.pulumi resources resolve there (the deployment is a plain
                        // ThreadLocal, not inherited by the worker) — § PulumiDeploymentSeed.
                        .andThen(PulumiDeploymentSeed.into(Deployment.getInstance())));
            // Render the runbook (adoc + json) into host.live.d AFTER the play — the two-channel
            // rule: the runbook is narration, materialised post-run. It cannot travel through the
            // promotion (a mid-scenario beat), so the host writes it into the live tree directly, a
            // live mutation seen as drift at the next rotation (§ host-cellar-realisation). The
            // live
            // root is a WITHIN-RUN fact the incus scion posed on the runbook (the ephemeral cellar,
            // § seed-broker-spec two cellars): the host reads it back through the graft mechanism,
            // NOT by re-deriving the layout convention (which lives only in incus-core).
            // Best-effort
            // inside the renderer, so it never fails the provisioning; a run with no live root (a
            // scion that did not pose it) simply renders nothing.
            final var graft = new ScenarioGraft();
            final ReportModel runbook = ClusterSeedScenario.lastRunbook();
            graft
                .graftedValue(runbook, GraftTag.LIVE_ROOT)
                .ifPresent(
                    live ->
                        new RunbookRenderer(Path.of(live), line -> context.log().info(line))
                            .render(runbook));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the cluster-seed run was interrupted", interrupted);
          }
        });
  }
}
