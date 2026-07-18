package io.nxmatic.rke2lab.controlplane.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.InstanceGrow;
import io.nxmatic.rke2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rke2lab.incus.contract.host.IncusGrowCoordinate;
import io.nxmatic.rke2lab.incus.contract.host.InstanceGrowPlan;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.pulumi.edge.PulumiCellar;
import io.nxmatic.rke2lab.pulumi.edge.PulumiDeploymentSeed;
import io.nxmatic.rke2lab.seed.bdd.CellarStage;
import io.nxmatic.rke2lab.seed.bdd.PreflightGate;
import io.nxmatic.rke2lab.seed.bdd.PreflightStage;
import io.nxmatic.rke2lab.seed.bdd.SeedReceiver;
import io.nxmatic.rke2lab.seed.bdd.SessionSeed;
import io.nxmatic.rke2lab.seed.bdd.SowAndGraftStage;
import io.nxmatic.rke2lab.seed.bdd.sow.Gardening;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
@SeedScenario
@SeedRuntime
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
    implements SeedReceiver<SeedRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /**
   * The inbound channel the driver ({@code Main}) seeds the {@link SeedRun} through and this
   * scenario receives it from (§ the amorce). It is single-sourced here — the receiver owns the key
   * + type — and referenced by {@code Main} for the seeding end. Registered as a {@link
   * RegisterExtension} so its {@code TestInstancePostProcessor} fires before the test body reads
   * {@link #run}; a field-based registration is needed because the channel carries constructor
   * state (type + key) that {@code @ExtendWith} cannot supply.
   */
  @RegisterExtension
  public static final SessionSeed<SeedRun> SEED = new SessionSeed<>(SeedRun.class, "seed-run");

  /**
   * Installs the live Pulumi deployment on the launcher worker thread before the body, so the GROW
   * beat's {@code com.pulumi} resources resolve (the deployment is a plain ThreadLocal the worker
   * does not inherit). Registered like {@link #SEED} — its {@code beforeEach} fires before the
   * WHEN.
   */
  @RegisterExtension
  public static final PulumiDeploymentSeed DEPLOYMENT = new PulumiDeploymentSeed();

  private final Scenario<Given, When, Then> scenario = createScenario();

  /** Set once by {@link #receiveSeed} (the {@code SessionSeed} post-processor) before the GIVEN. */
  @MonotonicNonNull private SeedRun run;

  /** Set once by {@link #receiveConnection} ({@code BaseWorldExtension}) before the GIVEN. */
  @MonotonicNonNull private OsgiConnection connection;

  /**
   * The run's transactional cellar — injected by {@code ScenarioCellarExtension} before the body
   * (the root's own, carrying the run txId; every scion's crossing inherits its in-flight entries).
   * Threaded into each sow-and-graft crossing as the ambient transaction.
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(SeedRun run) {
    this.run = run;
  }

  @Override
  public void receiveConnection(OsgiConnection connection) {
    this.connection = connection;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_cluster_seed_grows_to_a_ready_cluster() {
    final SeedRun seedRun =
        Objects.requireNonNull(
            run, "the SeedRun was not seeded before the scenario ran (the driver must seed it)");
    final ReportModel hostTree = getScenario().getModel();
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when()
        .the_entry_gates_are_enforced()
        .and()
        .the_parcels_state_is_fetched()
        .and()
        .the_network_reservations_are_settled(hostTree)
        .and()
        .the_instance_is_provisioned(hostTree)
        .and()
        .the_live_tree_is_reconciled(hostTree)
        .and()
        .the_instance_grows()
        .and()
        .the_systemd_adapter_is_launched(hostTree)
        .and()
        .the_cluster_becomes_ready(hostTree);
    then().the_harvest_is_stored();
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
    // The durable backend realisation (the Pulumi store the working cellar drains to at the
    // boundary). Named for its role, distinct from the working `cellar` below — the When already
    // consumes it as `cellarRealisation`.
    @ProvidedScenarioState PulumiCellar cellarRealisation;
    @ProvidedScenarioState PreflightGate preflightGate;
    @ProvidedScenarioState Parcel parcel;

    // Two JsonNode subtrees in one stage: jGiven shares state BY TYPE by default, so name-resolve
    // both to avoid an AmbiguousResolutionException (the When picks each back by field name).
    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode worktreeScalars;

    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode imageScalars;

    // The run's provisioning config — the host GROW derives the instance mounts from it (via the
    // dual-realm BootstrapPaths) and builds the provider context from it.
    @ProvidedScenarioState BootstrapConfig config;
    // The run's working cellar — the root's own ScenarioCellar (the seam type Cellar here),
    // injected
    // onto the scenario instance and published for SowAndGraftStage; every sow carries it, so a
    // launched scion inherits its txId + in-flight entries (§ cellar-transactional). Resolved by
    // TYPE: the durable backend is a distinct type (PulumiCellar), no clash.
    @ProvidedScenarioState Cellar cellar;

    public Given i_have_access_to_the_open_gardening(
        @Hidden SeedRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted;
      // the extension closes it at afterAll (the leak the hand-rolled open() left is gone).
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      // The host no longer computes the provisioning topology: the tree is incus's, so the incus
      // scion reconstructs it in-world from the flat worktree scalars the host hands it (§
      // host-cellar-realisation, computed OSGi-side) and picks its own rotation slot. The host
      // holds
      // only those scalars — built here as a blind subtree mirroring the incus contract's WORKTREE
      // schema, naming no incus type — and hands them to the incus crossing by the WORKTREE
      // amendment.
      final ObjectNode scalars = JsonNodeFactory.instance.objectNode();
      scalars.put("worktreeRoot", run.config().localWorktreePath().toString());
      scalars.put("clusterName", run.config().clusterName());
      scalars.put("nodeName", run.config().nodeName());
      scalars.put("nfsAutomount", run.config().nfsAutomount());
      this.worktreeScalars = scalars;
      // The IMAGE amendment — the seed-image build scalars the incus scion folds into the
      // buildChecksum and the artifact paths it projects for the GROW. Same blind-subtree
      // discipline
      // as WORKTREE: the host names only the neutral IMAGE role's schema fields, no incus type.
      final ObjectNode imageScalars = JsonNodeFactory.instance.objectNode();
      imageScalars.put("alias", run.config().imageAlias());
      imageScalars.put("builderBinary", run.config().imageBuilderBinary());
      imageScalars.put("builderHost", run.config().imageBuilderHost());
      imageScalars.put("sharedFolder", run.config().imageSharedFolder().toString());
      this.imageScalars = imageScalars;
      // The host GROW derives the instance mounts + provider context from the run's config.
      this.config = run.config();
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
      this.cellarRealisation = PulumiCellar.fromEnvironment(runGate, log);
      gardening
          .connection()
          .context()
          .registerService(OpaqueCellar.class, cellarRealisation, new Hashtable<>());
      gardening.connection().context().registerService(Parcel.class, parcel, new Hashtable<>());
      this.preflightGate =
          () ->
              EntryGatePolicyEnforcer.enforceAll(
                  run.config().localWorktreePath(), run.cleanWorktreeRequired());
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

    // Name-resolved on both sides of the crossing: two JsonNode subtrees share the same TYPE, so
    // jGiven must pick each by field name here as it did in the Given (else AmbiguousResolution).
    @ScenarioState(resolution = ScenarioState.Resolution.NAME)
    JsonNode worktreeScalars;

    @ScenarioState(resolution = ScenarioState.Resolution.NAME)
    JsonNode imageScalars;

    @ScenarioState BootstrapConfig config;

    // Decodes the InstanceGrowPlan envelope the GROW fetches — the host's flat copy of the codec
    // (dual-realm), the same the cellar drains through.
    private final SeedCodec codec = new SeedCodec();

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
      // Two amendments hand incus the flat scalars from BootstrapConfig. WORKTREE — the worktree
      // root, cluster/node, NFS automount: the scion reconstructs the topology, picks its own
      // rotation slot, and derives the manifests SOIL itself. IMAGE — the seed-image build scalars
      // the scion folds into the buildChecksum and the artifact paths it projects for the GROW (§
      // host-cellar-realisation, computed OSGi-side). The host names only the neutral roles, never
      // an incus field nor a path — the scion owns the tree.
      sowAndGraft
          .sowing(
              "incus-provision",
              gardening,
              hostTree,
              Map.of(Amendment.WORKTREE, worktreeScalars, Amendment.IMAGE, imageScalars))
          .the_scion_is_sown_and_grafted("the instance is provisioned");
      return self();
    }

    @NestedSteps
    @As("the live tree is reconciled")
    public When the_live_tree_is_reconciled(@Hidden ReportModel hostTree) {
      // Reconcile promotes the fresh staging (incus just published it) into host.live.d BEFORE the
      // instance mounts it — so it sits after the provision crossing and before systemd/cluster,
      // which run inside the instance. No amendment: the scion derives its whole state from the
      // cellar it inherits (source + pivot), the twin transaction the provision crossing wrote to.
      sowAndGraft
          .sowing("incus-reconcile", gardening, hostTree)
          .the_scion_is_sown_and_grafted("the live tree is reconciled");
      return self();
    }

    @As("the instance grows")
    public When the_instance_grows() {
      // The pure-host GROW (§ host-cellar § the-grow-anatomy): NOT a scion — the com.pulumi graph
      // cannot enter Felix, so it runs here, host-flat, between the promote (reconcile) and systemd
      // (which runs inside the instance). It fetches the InstanceGrowPlan the incus scion projected
      // (decoded host-side via the dual-realm codec) and actualises it: Project→{Network,Profile,
      // Image}→Instance + the 17 mounts it derives from BootstrapPaths. Gated on a live Pulumi
      // deployment on THIS worker thread (PulumiDeploymentSeed installs it) — absent in a
      // standalone
      // /offline run, where there is nothing to grow.
      if (!PulumiDeploymentSeed.isDeploymentPresent()) {
        return self();
      }
      cellarRealisation
          .fetch(parcel, IncusGrowCoordinate.INSTANCE_GROW_PLAN)
          .map(envelope -> codec.decode(envelope, InstanceGrowPlan.class))
          .ifPresent(plan -> new InstanceGrow(config, line -> {}).grow(plan));
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
