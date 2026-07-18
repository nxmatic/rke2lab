package io.nxmatic.rke2lab.incus.bdd;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.auth.contract.AuthTokenContact;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ObservationWire;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.incus.contract.IncusHarvest;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput.Image;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput.Worktree;
import io.nxmatic.rke2lab.incus.contract.host.BootstrapPaths;
import io.nxmatic.rke2lab.incus.contract.host.GrowNetworkView;
import io.nxmatic.rke2lab.incus.contract.host.IncusGrowCoordinate;
import io.nxmatic.rke2lab.incus.contract.host.InstanceGrowPlan;
import io.nxmatic.rke2lab.incus.core.GitProvenanceReader;
import io.nxmatic.rke2lab.incus.core.GrowNetworkResolver;
import io.nxmatic.rke2lab.incus.core.GrowPlanAssembler;
import io.nxmatic.rke2lab.incus.core.HostSlotSelector;
import io.nxmatic.rke2lab.incus.core.HostTreeChecksummer;
import io.nxmatic.rke2lab.incus.core.LaunchSecretsWriter;
import io.nxmatic.rke2lab.incus.core.NocloudSeedWriter;
import io.nxmatic.rke2lab.netplan.contract.NetplanSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The incus provisioning checkpoint, a production jGiven scenario told in the INCUS DOMAIN's own
 * vocabulary — it PREPARES the instance's material: the seed image built through {@link
 * ImageBuilder} and the manifests tree cultivated by consulting the manifests scion through the
 * broker, no host/Pulumi type. It does NOT make the instance grow nor probe its reachability:
 * creating the instance is the {@code com.pulumi} graph, which stays HOST (Shape C — the scion asks
 * the host to grow it through the broker), and verifying reachability is the responsibility of
 * whoever grows it (the host, post-push). Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code incus-bdd} over the {@code incus-contract} seam
 * plus {@code incus-core} (the host-tree logic — {@code BootstrapPaths} + {@code HostSlotSelector}:
 * the scion owns the tree, so it reconstructs the topology and picks its own slot in-world, §
 * host-cellar-realisation CORRECTION 2026-07-14; the heavy Pulumi-bound instance grow stays
 * host-side). Not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>The bbox/systemd/cluster twin: it resolves its collaborators from its OWN bundle's registry
 * ({@link ScenarioRegistry}) — the {@link ImageBuilder}, the {@link
 * io.nxmatic.rke2lab.seed.broker.port.SeedBroker} (to consult manifests), the ambient {@link
 * RunGate} (whose {@link RunGate#cultivating() cultivating} decides build-for-real vs plan-only),
 * and, on a failure, the doctor's {@link ConsultingService}. The scenario is identical live and in
 * test; only who published the collaborators differs (the live {@code DistrobuilderImageBuilder} +
 * the real broker + the host's RunGate, or the mocks a test seeds).
 *
 * <p>Preview inertness — the SCION consults the RunGate: under a closed gate it does NOT build the
 * image nor consult manifests (the real edge would ssh/build), it records the plan and the step
 * renders PENDING via E9. The image markers are fixed (the offline mock ignores them; the live
 * config-derived plumbing is the same deferral the cluster/systemd twins make for their kubeconfig
 * / endpoint).
 */
@SeedScenario
public class IncusProvisionScenario
    extends ScenarioTestBase<
        IncusProvisionScenario.Given, IncusProvisionScenario.When, IncusProvisionScenario.Then>
    implements CellarReceiver<ScenarioCellar> {

  private static final String NODE = "bioskop-master";

  // The handler seeds the activation input here before the launcher plays (same-loader static, the
  // input twin of the manifests scion's INPUT); it carries the @Amendment(SOIL) the scenario
  // forwards to the manifests scion it consults. Initialized (never null) so the null-hygiene gate
  // stays green.
  private static final AtomicReference<IncusRunbookInput> INPUT =
      new AtomicReference<>(IncusRunbookInput.defaults());

  // The front-door harvests the played model + any consultations off these holders (the same
  // scaffolding as the other scions). Initialized (never null) so the null-hygiene gate stays
  // green.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();
  private static final AtomicReference<List<SeedEnvelope>> LAST_CONSULTATIONS =
      new AtomicReference<>(List.of());

  /** The handler sets the sown input here before selecting this class into the launcher. */
  public static void seedInput(IncusRunbookInput input) {
    INPUT.set(input);
  }

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the scenario has not played yet — no runbook to harvest");
  }

  static List<SeedEnvelope> lastConsultations() {
    return LAST_CONSULTATIONS.get();
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body (store→tag, not
  // registry-resolved).
  // Typed ScenarioCellar (not just Cellar): the manifests sub-sow reads transactionId() to pass the
  // run's tx on. @MonotonicNonNull: null until receiveCellar sets it (before the body), then read.
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_instance_is_prepared() {
    final ImageBuilder imageBuilder = resolveImageBuilder();
    final RunGate gate = resolveGate();
    final SeedBroker broker = resolveBroker();
    // The scion reconstructs the provisioning topology from the flat worktree scalars the host
    // amended (§ host-cellar-realisation, computed OSGi-side) and picks its OWN rotation slot ONCE
    // —
    // stagingRoot, the SOIL forwarded to manifests, the liveRoot, and the worktree for provenance.
    final Resolved resolved = Resolved.from(INPUT.get().worktree(), cellar, resolveParcel());
    // The @Test body OWNS the observation sink (the same discipline as the other scions): the When
    // fills it, and the consult below reads THIS reference — independent of jGiven's stage state
    // after a fail-fast step, so a failed build still reaches the consult.
    final List<ObservationWire> observations = new ArrayList<>();
    given()
        .the_seed_node(NODE)
        .and()
        .prepared_through(imageBuilder, gate, observations, resolveNetplan())
        .and()
        .consulting_manifests_through(broker, resolved.soil(), cellar);
    when()
        .the_run_condition_is_read()
        .and()
        .the_image_is_built()
        .and()
        .the_manifests_are_cultivated()
        .and()
        .the_nocloud_seed_is_unwrapped(resolved)
        .and()
        .the_secrets_are_written(resolved, resolveAuthToken())
        .and()
        .the_network_is_resolved(resolved);
    then()
        .the_instance_is_prepared()
        .and()
        .the_prep_is_stored(imageBuilder, resolved.soil(), cellar, resolveParcel())
        .and()
        .the_staging_is_published(resolved, cellar, resolveParcel())
        .and()
        .the_instance_grow_plan_is_published(
            resolved,
            INPUT.get().image().orElse(new Image("", "", "", "")),
            imageBuilder.recipeDigest(),
            cellar,
            resolveParcel());
    // Pose the live root the host renders the runbook into — a within-run fact whose layout
    // convention lives only here (§ seed-broker-spec, two cellars: the ephemeral cellar). The graft
    // merges this tag into the host tree; the host reads it via ScenarioGraft.graftedValue. Posed
    // on the model BEFORE it is stashed, so it rides the serialised runbook across the realm.
    if (resolved.isAmended()) {
      getScenario().getModel().addTag(GraftTag.LIVE_ROOT.of(resolved.liveRoot()));
    }
    LAST_RUNBOOK.set(getScenario().getModel());
    LAST_CONSULTATIONS.set(consultOnFailure(observations));
  }

  /**
   * The domain consults the doctor ITSELF on a failed build or unreachable instance (fork B: the
   * checkpoint owns its consult, not the host). Any observation carrying a symptom triggers it;
   * resolve the doctor's {@link ConsultingService} from THIS bundle's registry, build an {@code
   * incus-provision} SeedEnvelope around the observations, and consult. A clean provision raised no
   * symptom, so it consults no one (empty list).
   */
  private List<SeedEnvelope> consultOnFailure(List<ObservationWire> observations) {
    final boolean anySymptom = observations.stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return resolveDoctor()
        .map(doctor -> List.of(doctor.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /** The {@code incus-provision} SeedEnvelope the domain hands the doctor — its observations. */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.INCUS_PROVISION.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The image-build request the scion drives. A fixed marker in the offline scenario (the mock
   * ignores it); the live config-derived path translation is deferred, exactly as the cluster twin
   * defers its kubeconfig.
   */
  private static ImageBuildRequest imageRequest() {
    return new ImageBuildRequest("distrobuilder", "/srv/host/incus-build", "artifacts", "", "", "");
  }

  private ImageBuilder resolveImageBuilder() {
    return require(
        ImageBuilder.class,
        "no ImageBuilder in the registry (live edge or test mock must publish one)");
  }

  /**
   * Resolve the ambient {@link RunGate} from THIS bundle's registry — the whole-run live/preview
   * fact. Required: a run without a published gate is a wiring bug (the host publishes it at boot,
   * a test registers a mock), so this fails loud rather than guessing a default.
   */
  private RunGate resolveGate() {
    return require(
        RunGate.class,
        "no RunGate in the registry (the host publishes it at boot, a test registers a mock)");
  }

  /**
   * Resolve the {@link SeedBroker} from THIS bundle's registry — the door incus sows toward to
   * consult the manifests scion (amend → runbook). Required: the broker is an SCR
   * {@code @Component} the framework publishes; a run without it is a wiring bug (a test registers
   * a mock broker).
   */
  private SeedBroker resolveBroker() {
    return require(
        SeedBroker.class,
        "no SeedBroker in the registry (the framework publishes DefaultSeedBroker; a test mocks it)");
  }

  /**
   * Resolve the current {@link Parcel} — the one plot this run cultivates, published as an ambient
   * fact beside the Cellar (the twin of the RunGate). The scion stores under it without ever
   * computing the stack identity.
   */
  private Parcel resolveParcel() {
    return require(
        Parcel.class,
        "no current Parcel in the registry (the host publishes it at the GIVEN like the RunGate)");
  }

  /**
   * Resolve the {@link AuthTokenContact} from THIS bundle's registry, or {@link Optional#empty()}
   * when no {@code auth-edge} is published — {@link LaunchSecretsWriter} still resolves tokens from
   * the environment (its higher-precedence source), so a missing contact degrades gracefully rather
   * than failing the prepare.
   */
  private Optional<AuthTokenContact> resolveAuthToken() {
    return ScenarioRegistry.of(this).optional(AuthTokenContact.class);
  }

  /**
   * Resolve the {@link NetplanSynthesisService} from THIS bundle's registry — the
   * {@code @Component} the scion reads (like {@link ImageBuilder}) to derive the network blueprint
   * it projects into the grow plan. Required: a run without it is a wiring bug (netplan-core
   * publishes it; a test mocks it).
   */
  private NetplanSynthesisService resolveNetplan() {
    return require(
        NetplanSynthesisService.class,
        "no NetplanSynthesisService in the registry (netplan-core publishes it; a test mocks it)");
  }

  private <T> T require(Class<T> type, String message) {
    return ScenarioRegistry.of(this).require(type, message);
  }

  /**
   * The topology the scion resolves ONCE from the worktree scalars — the inversion made concrete:
   * the scion owns the tree, picks its OWN rotation slot, and derives every path the run needs. The
   * slot is chosen ONCE here (a second {@code nextStaging()} after manifests materialised the slot
   * would see it and pick a different N). {@code stagingRoot} is the slot the assets land in;
   * {@code soil} the manifests plot forwarded to the manifests scion; {@code liveRoot} where the
   * host renders the runbook; {@code worktreeRoot} the base for the git provenance. All blank/empty
   * for an unamended survey (a bare {@code shape} probe), so manifests falls back to a temp dir.
   */
  private record Resolved(
      String stagingRoot,
      String soil,
      String liveRoot,
      Path worktreeRoot,
      Path runtimeCloudConfigRoot,
      Path cloudSeedRoot,
      Path secretsFile,
      String clusterName,
      String nodeName) {

    static final Resolved UNAMENDED =
        new Resolved("", "", "", Path.of(""), Path.of(""), Path.of(""), Path.of(""), "", "");

    static Resolved from(Optional<Worktree> maybeWorktree, Cellar cellar, Parcel parcel) {
      if (maybeWorktree.isEmpty()) {
        return UNAMENDED;
      }
      final Worktree worktree = maybeWorktree.orElseThrow();
      final Path root = Path.of(worktree.worktreeRoot());
      final BootstrapPaths local =
          BootstrapPaths.fromLocalWorktree(root, worktree.clusterName(), worktree.nodeName());
      final Path slot = new HostSlotSelector(local.clusterNodeRoot(), cellar, parcel).nextStaging();
      final BootstrapPaths staging = local.asStagingView(slot);
      return new Resolved(
          slot.toString(),
          staging.manifestsRoot().toString(),
          local.liveRoot().toString(),
          root,
          staging.runtimeCloudConfigRoot(),
          staging.cloudSeedRoot(),
          staging.secretsFile(),
          worktree.clusterName(),
          worktree.nodeName());
    }

    boolean isAmended() {
      return !stagingRoot.isBlank();
    }
  }

  /**
   * Resolve the doctor's {@link ConsultingService} from THIS bundle's registry, or {@link
   * Optional#empty()} if none is published — a real runtime condition (a world booted without the
   * doctor), so a failure without a doctor degrades to no consultation rather than a crash.
   */
  private Optional<ConsultingService> resolveDoctor() {
    return ScenarioRegistry.of(this).optional(ConsultingService.class);
  }

  /** Given: the seed node, the image builder, the run gate, the observation sink, and the door. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ImageBuilder imageBuilder;
    @ProvidedScenarioState RunGate gate;
    @ProvidedScenarioState List<ObservationWire> observations;
    @ProvidedScenarioState SeedBroker broker;
    @ProvidedScenarioState String soil;
    // The netplan synthesis service the scion reads to derive the network blueprint it projects
    // into
    // the grow plan (the_network_is_resolved). A resolved collaborator like the image builder.
    @ProvidedScenarioState NetplanSynthesisService netplan;
    // This scion's working cellar (the seam type Cellar here), carried on to the manifests sub-sow
    // so it inherits the same tx (txId + in-flight entries). Resolved by TYPE — the only Cellar in
    // stage state.
    @ProvidedScenarioState Cellar cellar;

    public Given the_seed_node(@Quoted String name) {
      return self();
    }

    @Hidden
    public Given prepared_through(
        ImageBuilder imageBuilder,
        RunGate gate,
        List<ObservationWire> observations,
        NetplanSynthesisService netplan) {
      this.imageBuilder = imageBuilder;
      this.gate = gate;
      this.observations = observations;
      this.netplan = netplan;
      return self();
    }

    @Hidden
    public Given consulting_manifests_through(SeedBroker broker, String soil, Cellar cellar) {
      this.broker = broker;
      this.soil = soil;
      this.cellar = cellar;
      return self();
    }
  }

  /**
   * When: the scion reads the run condition (the {@link RunGate}), builds the image ONLY when
   * cultivating (an edge effect — distrobuilder/ssh), and ALWAYS consults manifests, recording each
   * facet's {@link ObservationWire} (ok, or failed with a typed {@link SymptomKind}) into the
   * shared sink, fail-fast on the first failure. It PREPARES the instance's material (image +
   * manifests); the host makes the instance grow and verifies its reachability (Shape C — the gRPC
   * push and its post-push probe are host-side).
   *
   * <p>The gate splits by NATURE of effect (§ host-cellar-realisation, Live vs preview): the image
   * build is an edge effect, so a closed gate builds nothing (the plan renders PENDING via E9, no
   * edge contacted); the manifests synthesis is a pure FS materialisation into {@code
   * host.N.staging.d}, inert against the live instance, so it runs at preview too — a preview run
   * materialises its staging replica and its host-manifest (traceable from the cellar), only the
   * rsync into {@code host.live.d} is gated (I6). So consulting manifests is NOT gated.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ImageBuilder imageBuilder;
    @ExpectedScenarioState RunGate gate;
    @ExpectedScenarioState List<ObservationWire> observations;
    @ExpectedScenarioState SeedBroker broker;
    @ExpectedScenarioState String soil;
    @ExpectedScenarioState NetplanSynthesisService netplan;

    @ExpectedScenarioState Cellar cellar;

    @ProvidedScenarioState boolean cultivating;
    // The flat network view the scion projects for the host GROW — assembled here (WHEN
    // fabricates),
    // sealed into the plan by the THEN. Null for an unamended survey (no cluster to resolve).
    @ProvidedScenarioState GrowNetworkView networkView;

    private final SeedCodec codec = new SeedCodec();

    public When the_run_condition_is_read() {
      this.cultivating = gate.cultivating();
      return self();
    }

    public When the_image_is_built() {
      if (!cultivating) {
        return self();
      }
      final Optional<String> failure = imageBuilder.build(imageRequest());
      if (failure.isPresent()) {
        record("incus image", false, SymptomKind.IMAGE_BUILD_FAILED, failure.get());
        throw new AssertionError("incus image build failed: " + failure.get());
      }
      record("incus image", true, SymptomKind.IMAGE_BUILD_FAILED, null);
      return self();
    }

    /**
     * incus consults the manifests scion through the broker — the first metier scion→scion. The
     * instance mounts a materialised tree, which manifests cultivates; incus forwards its OWN
     * {@code @Amendment(SOIL)} as the manifests SOIL (a plot, never a fingerprint — a harvest is
     * fetched, not pushed). Two sows: AMEND reconciles the neutral role into the manifests input at
     * the door (incus names no manifests field), then RUNBOOK plays the synthesis with the amended
     * input. NOT gated: the synthesis writes {@code host.N.staging.d}, a pure FS materialisation
     * the host-tree model wants at preview too (only the rsync into {@code host.live.d} is gated).
     * The manifests scion picks a temp dir itself when the SOIL is blank (a bare survey).
     */
    public When the_manifests_are_cultivated() {
      // AMEND: hand the broker {soil → path} by neutral role; the manifests amend reflector binds
      // it
      // onto ManifestsRunbookInput and returns the reconciled input, still under the runbook
      // coordinate.
      final ObjectNode roleValues = JsonNodeFactory.instance.objectNode();
      roleValues.put(Amendment.SOIL, soil);
      final SeedEnvelope amended =
          broker.sow(
              new AmendCoordinate("manifests"),
              cellar,
              new SeedEnvelope("manifests", "runbook", codec.encode(roleValues)));
      // RUNBOOK: play the manifests synthesis with the reconciled input; the fresh tree is the
      // graft
      // the instance will mount (consumed at once, never cellared — cultivated fresh). No
      // observation
      // recorded here: the consult sink is for probe symptoms (build/reachability), not for the
      // sub-scenario's own outcome — a manifests failure surfaces as the sow throwing.
      broker.sow(new RunbookCoordinate("manifests"), cellar, amended);
      return self();
    }

    /**
     * Unwrap the synthesised {@code cloud-config} ConfigMap into the NoCloud seed the instance
     * reads at first boot (§ provisioning-slice delta #2) — a WHEN because it FABRICATES material,
     * like the manifests synthesis it follows. The manifests sub-sow materialised the ConfigMap
     * under the staging slot's {@code runtime/cloud-config}; {@link NocloudSeedWriter} strips the
     * envelope and writes {@code user-data}/{@code meta-data}/{@code network-config} into the
     * slot's {@code cloud.d}, so it lands BEFORE {@code the_staging_is_published} checksums the
     * tree. NOT gated: a pure FS materialisation into the staging slot, inert against the live
     * instance (like the manifests synthesis) — only the promotion into {@code host.live.d} is
     * gated. Skipped for an unamended survey (no slot materialised).
     */
    public When the_nocloud_seed_is_unwrapped(@Hidden Resolved resolved) {
      if (!resolved.isAmended()) {
        return self();
      }
      new NocloudSeedWriter().unwrap(resolved.runtimeCloudConfigRoot(), resolved.cloudSeedRoot());
      return self();
    }

    /**
     * Upsert the gh/flox launch tokens into the worktree's {@code .secrets} (§ provisioning-slice
     * delta #10) — a WHEN because it FABRICATES material (a file materialisation), BEFORE the
     * {@code worktree.dir} mount binds it into the instance. {@link LaunchSecretsWriter} resolves
     * each token from the environment first, else the {@link AuthTokenContact} edge. GATED on
     * {@code cultivating}: the contact shells {@code gh}/{@code flox} (an edge effect), so a closed
     * gate touches no CLI and leaves the file untouched — the plan renders PENDING via E9, the same
     * deferral the image build makes. Skipped for an unamended survey (no worktree {@code .secrets}
     * to upsert).
     */
    public When the_secrets_are_written(
        @Hidden Resolved resolved, @Hidden Optional<AuthTokenContact> authToken) {
      if (!resolved.isAmended() || !cultivating) {
        return self();
      }
      new LaunchSecretsWriter(authToken).ensureTokensPresent(resolved.secretsFile());
      return self();
    }

    /**
     * Resolve the network view the host GROW poses — the two NIC hwaddrs + the {@code vmnet}
     * bridge's dnsmasq config, projected flat from the netplan blueprint (§ the
     * scion-projects/host-actualises rule). A WHEN because it FABRICATES the value the THEN seals
     * into the grow plan. NOT gated: a pure OSGi computation off the {@link
     * NetplanSynthesisService}, no edge contacted — inert at preview like the manifests synthesis.
     * Skipped for an unamended survey (no cluster to resolve).
     */
    public When the_network_is_resolved(@Hidden Resolved resolved) {
      if (!resolved.isAmended()) {
        return self();
      }
      this.networkView =
          new GrowNetworkResolver(netplan).resolve(resolved.clusterName(), resolved.nodeName());
      return self();
    }

    private void record(String facet, boolean ok, SymptomKind failureSymptom, String detail) {
      if (ok) {
        observations.add(
            new ObservationWire("ok", facet, Optional.empty(), Map.of("facet", facet)));
        return;
      }
      observations.add(
          new ObservationWire(
              "failed",
              facet + ": " + (detail == null ? "not ready" : detail),
              Optional.of(failureSymptom),
              Map.of("facet", facet)));
    }
  }

  /**
   * Then: the instance's material is prepared — reached only once the image built and manifests
   * were cultivated (a failure throws in the When). The readable closing line; the host then makes
   * the instance grow (Shape C) and verifies its reachability, not this scion.
   */
  public static class Then extends Stage<Then> {

    // The network view the WHEN produced — read here to seal it into the grow plan. Null for an
    // unamended survey (the_network_is_resolved skipped).
    @ExpectedScenarioState GrowNetworkView networkView;

    public Then the_instance_is_prepared() {
      return self();
    }

    /**
     * The scion harvests AND stores — the reversal made concrete (§ host-cellar-realisation,
     * every-scion-contributes), the twin of the bbox scion's {@code the_harvest_is_stored}. It
     * folds the prep INTENTION into an {@link IncusHarvest} — the {@link
     * ImageBuilder#recipeDigest() recipe digest} (stable across a closed gate, the host's
     * image-cache key) and the {@code soil} the manifests tree was cultivated under — and stores it
     * at the {@code incus-prep} coordinate under the current {@link Parcel}. On the Pulumi
     * realisation this store PRODUCES the incus-prep resource; the host FETCHES the harvest and
     * grows the instance (Shape C). The store is unconditional: the cellar consults the RunGate
     * itself to route conserve ({@code up}) vs pre-reserve ({@code preview}), so the scion never
     * picks the mode.
     */
    public Then the_prep_is_stored(
        @Hidden ImageBuilder imageBuilder,
        @Hidden String soil,
        @Hidden Cellar cellar,
        @Hidden Parcel parcel) {
      final IncusHarvest harvest = new IncusHarvest(imageBuilder.recipeDigest(), soil);
      cellar.store(parcel, IncusCoordinate.INCUS_PREP, harvest);
      return self();
    }

    /**
     * Publish the {@link HostStagingEntry} for the slot this run materialised — the host-tree fact
     * reconcile folds later to decide the promotion (§ host-cellar-realisation, the reconcile
     * cycle). It checksums the WHOLE staging tree ({@link HostTreeChecksummer}) — the discriminant
     * reconcile diffs against the pivot — and captures the worktree's git provenance ({@link
     * GitProvenanceReader}: sha + dirty), frozen with the immutable staging (the fold keeps N
     * stagings, so this history survives, unlike the last-wins live). Skipped for an unamended
     * survey (no slot materialised). The store is unconditional on the gate: the cellar itself
     * routes conserve vs pre-reserve, so a preview run still records its staging entry (only the
     * promotion's live entry is gated, at reconcile).
     */
    public Then the_staging_is_published(
        @Hidden Resolved resolved, @Hidden Cellar cellar, @Hidden Parcel parcel) {
      if (!resolved.isAmended()) {
        return self();
      }
      final Path stagingRoot = Path.of(resolved.stagingRoot());
      final HostStagingEntry entry =
          HostStagingEntry.of(
              resolved.stagingRoot(),
              new HostTreeChecksummer().checksum(stagingRoot),
              new GitProvenanceReader().read(resolved.worktreeRoot()));
      cellar.store(parcel, IncusCoordinate.HOST_STAGING, entry);
      return self();
    }

    /**
     * Seal the ONE immutable {@link InstanceGrowPlan} the host GROW fetches — the scion-projects
     * part of the scion-projects/host-actualises rule (§ host-cellar-realisation,
     * the-grow-anatomy). The network view the {@code the_network_is_resolved} WHEN produced, plus
     * the image view and the cloud-init checksum the {@link GrowPlanAssembler} computes OSGi-side
     * (the {@code buildChecksum} folding the edge {@code recipeDigest} with the image scalars, the
     * readable artifact paths, and the SHA-256 of the NoCloud seed that arms the replace wire).
     * Stored at {@link IncusGrowCoordinate#INSTANCE_GROW_PLAN} — the single record the host decodes
     * host-side via the dual-realm codec. Skipped for an unamended survey (no view resolved). The
     * store is unconditional on the gate: the cellar routes conserve vs pre-reserve, so a preview
     * run still records the plan.
     */
    public Then the_instance_grow_plan_is_published(
        @Hidden Resolved resolved,
        @Hidden Image image,
        @Hidden String recipeDigest,
        @Hidden Cellar cellar,
        @Hidden Parcel parcel) {
      if (!resolved.isAmended() || networkView == null) {
        return self();
      }
      final InstanceGrowPlan plan =
          new GrowPlanAssembler(
                  image.alias(),
                  image.builderBinary(),
                  image.builderHost(),
                  recipeDigest,
                  Path.of(image.sharedFolder()),
                  resolved.cloudSeedRoot())
              .assemble(networkView);
      cellar.store(parcel, IncusGrowCoordinate.INSTANCE_GROW_PLAN, plan);
      return self();
    }
  }
}
