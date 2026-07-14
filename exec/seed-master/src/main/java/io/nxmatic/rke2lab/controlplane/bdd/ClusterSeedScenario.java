package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.BootstrapPaths;
import io.nxmatic.rke2lab.controlplane.HostSlotSelector;
import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.pulumi.edge.PulumiCellar;
import io.nxmatic.rke2lab.seed.bdd.CellarStage;
import io.nxmatic.rke2lab.seed.bdd.PreflightGate;
import io.nxmatic.rke2lab.seed.bdd.PreflightStage;
import io.nxmatic.rke2lab.seed.bdd.SeedReceiver;
import io.nxmatic.rke2lab.seed.bdd.SowAndGraftStage;
import io.nxmatic.rke2lab.seed.bdd.sow.Gardening;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The ClusterSeed root scenario — the host runbook, spoken in the gardening register, composed on
 * the common {@code seed-bdd} stages (link:docs/architecture/osgi/seed-bdd-module-spec.adoc). It is
 * the concrete instance of link:docs/architecture/bdd/bdd.adoc#clusterseed-scenario-map[the
 * ClusterSeed scenario map]: a GIVEN that bootstraps the open gardening, then the seven WHENs —
 * preflight → {@code Cellar.fetch} → four sow-and-graft callers (bbox · incus · systemd · cluster)
 * → {@code Cellar.store}.
 *
 * <p>The amorce is two-layered (§ the amorce): {@code Main} — inside {@code Pulumi.run} — captures
 * the {@link RunMode} (the one fact only it can know) and seeds it through the launcher session
 * store; this scenario {@link SeedReceiver receives} it before the GIVEN, which bootstraps
 * everything else from it. Opening the gardening is the GIVEN's work, never a WHEN.
 *
 * <p>Each sow-and-graft WHEN is a {@code @NestedSteps} step named for its crossing; its body sows
 * the soil's runbook through the gardening and grafts the reaped scion under THIS step, into the
 * live root {@link ReportModel} ({@code getScenario().getModel()} — the single trunk). jGiven adds
 * a top-level step to the model at invocation time, so the rootstock step already exists when its
 * body grafts under it.
 */
@ExtendWith(JGivenExtension.class)
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
    implements SeedReceiver<SeedRun> {

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The driver (Main) renders the runbook AFTER the run from the played model — the launcher
  // instantiates the scenario, so Main cannot reach `this`; the run stashes the model and the
  // staging root it materialised into here (the same holder discipline as the scions'
  // LAST_RUNBOOK).
  // The staging root is set by the GIVEN (which resolved the slot) so the rotation is read once,
  // not
  // recomputed (a second HostSlotSelector call could pick a different N). Never null once played.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();
  private static final AtomicReference<Path> LAST_STAGING_ROOT = new AtomicReference<>();

  /** The played runbook model — for the driver to render after the run. */
  public static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the cluster-seed scenario has not played yet — no runbook to render");
  }

  /** The staging slot the run materialised into — where the driver renders the runbook. */
  public static Path lastStagingRoot() {
    return Objects.requireNonNull(
        LAST_STAGING_ROOT.get(), "the cluster-seed scenario has not played yet — no staging root");
  }

  /** Set once by {@link #receiveSeed} (the {@code SessionSeed} post-processor) before the GIVEN. */
  @MonotonicNonNull private SeedRun run;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(SeedRun run) {
    this.run = run;
  }

  @Test
  void the_cluster_seed_grows_to_a_ready_cluster() {
    final SeedRun seedRun =
        Objects.requireNonNull(
            run, "the SeedRun was not seeded before the scenario ran (the driver must seed it)");
    final ReportModel hostTree = getScenario().getModel();
    given().i_have_access_to_the_open_gardening(seedRun);
    when()
        .the_entry_gates_are_enforced()
        .and()
        .the_parcels_state_is_fetched()
        .and()
        .the_network_reservations_are_settled(hostTree)
        .and()
        .the_instance_is_provisioned(hostTree)
        .and()
        .the_systemd_adapter_is_launched(hostTree)
        .and()
        .the_cluster_becomes_ready(hostTree);
    then().the_harvest_is_stored();
    // Stash the played model for the driver to render the runbook (adoc + json) into the staging
    // slot after the run — the two-channel rule: the runbook is narration, rendered post-run.
    LAST_RUNBOOK.set(hostTree);
  }

  /**
   * The GIVEN bootstraps the open gardening from the received {@link RunMode}: open the {@link
   * Gardening} (boot Felix, find the gardener), publish the {@link RunGate} into the registry so
   * the scions resolve it, and build the host realisations the WHENs use — the {@link
   * PulumiCellar}, the {@link PreflightGate}, the {@link Parcel}. All of it is narration; opening
   * the gardening is a precondition, not a step.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState PulumiCellar cellar;
    @ProvidedScenarioState PreflightGate preflightGate;
    @ProvidedScenarioState Parcel parcel;
    @ProvidedScenarioState BootstrapPaths paths;

    public Given i_have_access_to_the_open_gardening(@Hidden SeedRun run) {
      this.gardening = Gardening.open();
      // The provisioning topology, resolved once from the worktree — the state every materialising
      // WHEN reads (the manifests scion's outdir, the systemd/rke2-config roots). The DARWIN-local
      // view is where the provisioner writes; asHostView(NIXOS) is the mounted-assets view. This is
      // PathStage.resolve() transposed onto the scenario's GIVEN. The run materialises into a fresh
      // host.staging.N replica slot (§ host-cellar-realisation, the three fixed places), never the
      // live host/ directly — the slot is later rsynced into host.live at the grow. The FS is the
      // rotation state: HostSlotSelector reads the present slots and picks (max+1) mod 3.
      final BootstrapPaths worktreePaths =
          BootstrapPaths.fromLocalWorktree(
              run.config().localWorktreePath(),
              run.config().clusterName(),
              run.config().nodeName());
      final Path stagingSlot = new HostSlotSelector(worktreePaths.clusterNodeRoot()).nextStaging();
      this.paths = worktreePaths.asStagingView(stagingSlot);
      // The slot the run materialises into — stashed for the driver to render the runbook here
      // after
      // the run (read once, from the GIVEN's chosen slot, not recomputed).
      LAST_STAGING_ROOT.set(stagingSlot);
      // Publish the ambient RunGate the scions resolve — projected from the run mode.
      // registerService,
      // not a handler: the run-condition is a service the whole run shares (§ RunGate).
      final RunGate runGate = run.runMode()::playsLive;
      gardening.connection().context().registerService(RunGate.class, runGate, new Hashtable<>());

      // The two ambient facts a scion needs to STORE its own harvest (§ host-cellar-realisation,
      // every-scion-contributes): the Cellar (the neutral furniture the host lays into Felix) and
      // the
      // current Parcel (the one plot this run cultivates). Both published like the RunGate — a
      // scion
      // resolves them via ScenarioRegistry.require and stores itself; the host never round-trips a
      // harvest back to re-store it. The Cellar stays NEUTRAL (store(Parcel, …)); the current
      // parcel
      // lives BESIDE it as an ambient fact, never inside it (the doctor addresses N parcels).
      this.parcel = run.parcel();
      final Consumer<String> log = line -> {};
      this.cellar = PulumiCellar.fromEnvironment(runGate, log);
      gardening.connection().context().registerService(Cellar.class, cellar, new Hashtable<>());
      gardening.connection().context().registerService(Parcel.class, parcel, new Hashtable<>());
      final BootedFramework framework =
          BootedFramework.attached(gardening.connection().framework());
      this.preflightGate =
          () ->
              EntryGatePolicyEnforcer.enforceAll(
                  run.config().localWorktreePath(), run.cleanWorktreeRequired(), framework);
      return self();
    }
  }

  /** The seven WHENs — preflight, the cellar bookends, and the four sow-and-graft crossings. */
  public static class When extends Stage<When> {

    @ScenarioStage PreflightStage preflight;
    @ScenarioStage CellarStage cellar;
    @ScenarioStage SowAndGraftStage sowAndGraft;

    @ScenarioState Gardening gardening;
    @ScenarioState PulumiCellar cellarRealisation;
    @ScenarioState PreflightGate preflightGate;
    @ScenarioState Parcel parcel;

    @NestedSteps
    @As("the entry gates are enforced")
    public When the_entry_gates_are_enforced() {
      preflight.gatedBy(preflightGate).the_entry_gates_are_enforced();
      return self();
    }

    @NestedSteps
    @As("the parcel's state is fetched")
    public When the_parcels_state_is_fetched() {
      cellar.conserving(cellarRealisation, parcel).the_parcels_state_is_fetched();
      return self();
    }

    @NestedSteps
    @As("the network reservations are settled")
    public When the_network_reservations_are_settled(@Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("bbox", gardening, hostTree)
          .the_scion_is_sown_and_grafted("the network reservations are settled");
      return self();
    }

    @NestedSteps
    @As("the instance is provisioned")
    public When the_instance_is_provisioned(@Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("incus", gardening, hostTree)
          .the_scion_is_sown_and_grafted("the instance is provisioned");
      return self();
    }

    @NestedSteps
    @As("the systemd adapter is launched")
    public When the_systemd_adapter_is_launched(@Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("systemd", gardening, hostTree)
          .the_scion_is_sown_and_grafted("the systemd adapter is launched");
      return self();
    }

    @NestedSteps
    @As("the cluster becomes ready")
    public When the_cluster_becomes_ready(@Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("cluster", gardening, hostTree)
          .the_scion_is_sown_and_grafted("the cluster becomes ready");
      return self();
    }
  }

  /** The closing THEN — the harvest is filed to its parcel's cellar. */
  public static class Then extends Stage<Then> {

    @ScenarioStage CellarStage cellar;

    @As("the harvest is stored")
    public Then the_harvest_is_stored() {
      // The cellar bookend closes the run; the végétaux cultivated fresh this run are filed to the
      // parcel. (What exactly is stored is the harvest-shaping task; the bookend is here.)
      return self();
    }
  }
}
