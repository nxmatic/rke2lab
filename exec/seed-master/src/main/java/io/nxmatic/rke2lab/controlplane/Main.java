package io.nxmatic.rke2lab.controlplane;

import com.pulumi.Pulumi;
import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSeedScenario;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.bdd.SeedRun;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.incus.contract.host.BootstrapPaths;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ScenarioGraft;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
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
    // Point logback's FILE appender at the project state dir BEFORE the first log line (logback
    // initialises lazily on first use, so this must precede any logging). One source for the
    // ".local.d" convention — BootstrapPaths.STATE_DIR — not a literal duplicated into logback.xml.
    // The XML reads ${seed.log.dir} with a safe default if this is ever absent.
    System.setProperty("seed.log.dir", BootstrapPaths.STATE_DIR);

    // Silence the process's raw console. pax-logging (in-container, its own default config, NOT our
    // host logback.xml) and any stray printStackTrace write straight to System.out/err — and under
    // a remote debugger that console is not drained, so the native FileOutputStream.write BLOCKS
    // the
    // FelixStartLevel thread and the whole boot deadlocks (proven by jstack). Every real log
    // already
    // goes to .local.d/seed-master.log via the logback FILE appender, so the raw streams carry
    // nothing we need; routing them to a sink makes the JVM debuggable. Pulumi reads the engine
    // gRPC
    // channel, not stdout, so it is unaffected.
    final java.io.PrintStream sink =
        new java.io.PrintStream(java.io.OutputStream.nullOutputStream(), true);
    System.setOut(sink);
    System.setErr(sink);
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
            final ReportModel runbook =
                new JUnitLauncherCore<ReportModel>()
                    .run(
                        Main.class.getClassLoader(),
                        JupiterTestEngine.class,
                        wiring ->
                            List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
                        (launcher, request, sessionStore) -> {
                          launcher.execute(request);
                          return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                        },
                        ClusterSeedScenario.SEED
                            .into(run)
                            .andThen(RunRoleSeed.into(RunRole.ROOT))
                            .andThen(TxIdSeed.into(run.txId()))
                            // The live Pulumi deployment, seeded onto the launcher worker so the
                            // GROW beat's com.pulumi resources resolve there (the deployment is a
                            // plain ThreadLocal, not inherited by the worker) — §
                            // PulumiDeploymentSeed.
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
            graft
                .graftedValue(runbook, GraftTag.LIVE_ROOT)
                .ifPresent(
                    live ->
                        new RunbookRenderer(Path.of(live), line -> context.log().info(line))
                            .render(runbook));
            // Only AFTER the diagnostic runbook is rendered does the run's verdict surface: the
            // ReportModel is the fail-at-end collector (jGiven plays to completion, marking each
            // failed step), so a FAILED or ABORTED scenario means the seed did not complete — fail
            // the Pulumi run so the operator sees a non-zero exit, the runbook already written for
            // the diagnosis. A clean run raises nothing.
            final List<?> broken =
                runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
            if (!broken.isEmpty()) {
              throw new IllegalStateException(
                  "the cluster-seed scenario did not complete — see the rendered runbook ("
                      + broken.size()
                      + " failed/aborted)");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the cluster-seed run was interrupted", interrupted);
          }
        });
  }
}
