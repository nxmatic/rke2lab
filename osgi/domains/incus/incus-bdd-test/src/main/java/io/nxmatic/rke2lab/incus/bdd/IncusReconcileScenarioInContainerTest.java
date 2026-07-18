package io.nxmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry.Provenance;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarEntriesSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the incus RECONCILE scion, the twin of {@link
 * IncusProvisionScenarioInContainerTest} — run WHERE the scenario lives (this passenger shares the
 * incus-bdd host loader through the fragment). It plays {@link IncusReconcileScenario} through the
 * shared {@link ScenarioPlayer} (the recipe the production {@code GenericRunbookHandler} also
 * drives) and asserts the three NATURES the reconcile splits on:
 *
 * <ul>
 *   <li>a LIVE FLIP — the run's staging differs from the pivot and the gate is open: the staging is
 *       jsync-promoted into {@code host.live.d} (a real FS sync), the {@code HostLiveEntry} is
 *       committed, and the {@code PROMOTED} tag is posed;
 *   <li>a NO-OP — the staging equals the pivot (same checksum map): nothing is promoted, whatever
 *       the gate;
 *   <li>a PREVIEW — a real change but a CLOSED gate: the decision is computed, but the live edge is
 *       gated, so nothing is promoted and nothing is committed.
 * </ul>
 *
 * <p>The scion derives its whole state from the ambient transaction, so this passenger seeds it the
 * way prod does — but split by PROVENANCE, faithfully: the {@code source} staging (the one THIS run
 * published) DESCENDS through the real ALLER channel ({@code runReconcile}'s {@code
 * inheritedEntries}, encoded {@link ScenarioCellar.Entry} strings), while the {@code pivot} (a
 * prior run's committed {@code host-live} + its staging checksums) sits on a durable {@link Cellar}
 * the passenger registers — the {@link StubCellar} standing in for prod's Pulumi cellar (which no
 * test can run). Reconcile is played as a FRAGMENT (the front-door seeds no {@code RunRole}), so
 * the extension never drains to a durable {@code OpaqueCellar} — the stub is a read side only.
 *
 * <p>The commit is read back like the provision twin: a {@link ScenarioCellar} over the LIVE model
 * with an EMPTY durable side, so {@code fetch(host-live)} returns ONLY the run's own store (the
 * inherited source having been stripped before the graft, § seed-broker-spec) — present on a real
 * flip, empty on a no-op or preview. Reconcile is played on the same in-container worker, so the
 * outcome's {@code ReportModel} is read live — no JSON round-trip.
 */
public class IncusReconcileScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /** The current parcel the host publishes at the GIVEN; the scion reconciles under it. */
  private static final Parcel PARCEL = new Parcel("bioskop", "dev");

  private static final String DEPLOYMENT = "rke2-manifests.d/deployment.yaml";

  @Test
  void a_live_change_promotes_the_staging_into_the_live_tree(@TempDir Path node) throws Exception {
    final HostTree tree = HostTree.laidOut(node, "image: v2", "image: v1");
    final ReportModel runbook = play(cultivatingGate(true), tree).runbook();

    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a changed staging under an open gate reconciles the live tree cleanly");
    assertTrue(
        posedPromotedFrom(runbook).contains(tree.sourceRoot().toString()),
        "the PROMOTED tag names the staging the live flipped from");
    assertEquals(
        "image: v2",
        Files.readString(tree.liveRoot().resolve(DEPLOYMENT)),
        "the staging content is jsync-promoted into the physical host.live.d");
    assertEquals(
        tree.sourceRoot().toString(),
        committedLive(runbook).orElseThrow(() -> new AssertionError("no host-live committed")),
        "the committed host-live entry now mirrors the freshly promoted staging");
  }

  @Test
  void a_no_op_change_promotes_nothing(@TempDir Path node) throws Exception {
    // Source checksums EQUAL the pivot's — the live already mirrors this staging, so the decision
    // is
    // a no-op even under an open gate.
    final HostTree tree = HostTree.noChange(node, "image: v1");
    final ReportModel runbook = play(cultivatingGate(true), tree).runbook();

    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a no-op reconcile still plays cleanly");
    assertTrue(posedPromotedFrom(runbook).isEmpty(), "an unchanged staging poses no PROMOTED tag");
    assertTrue(
        committedLive(runbook).isEmpty(),
        "a no-op commits no host-live — the live already mirrors this staging");
    assertEquals(
        "image: v1",
        Files.readString(tree.liveRoot().resolve(DEPLOYMENT)),
        "the physical live tree is untouched by a no-op");
  }

  @Test
  void a_preview_run_promotes_nothing(@TempDir Path node) throws Exception {
    // A real change (v2 vs v1) but a CLOSED gate: the decision is computed, the live edge is gated.
    final HostTree tree = HostTree.laidOut(node, "image: v2", "image: v1");
    final ReportModel runbook = play(cultivatingGate(false), tree).runbook();

    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a preview reconcile plans cleanly without touching the live tree");
    assertTrue(posedPromotedFrom(runbook).isEmpty(), "a gated preview poses no PROMOTED tag");
    assertTrue(committedLive(runbook).isEmpty(), "a preview commits no host-live");
    assertEquals(
        "image: v1",
        Files.readString(tree.liveRoot().resolve(DEPLOYMENT)),
        "under a closed gate the physical live tree is NOT promoted");
  }

  /**
   * Register the ambient collaborators into THIS bundle's registry — the {@link RunGate} the scion
   * reads its run condition from, the current {@link Parcel}, and the durable {@link Cellar} (the
   * {@link StubCellar} carrying the pivot) — then play the reconcile in-container through the
   * shared {@link ScenarioPlayer}, DESCENDING the source staging as the transaction's inherited
   * write-set (the ALLER channel), the {@code txId} + entries composed exactly as the handler does.
   * Registrations are removed in the {@code finally} so each test plays its own state.
   */
  private static ScenarioOutcome play(RunGate gate, HostTree tree) throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(IncusBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(Parcel.class, PARCEL, new Hashtable<>()));
    registrations.add(
        context.registerService(Cellar.class, tree.durablePivot(), new Hashtable<>()));
    try {
      return new ScenarioPlayer()
          .play(
              IncusReconcileScenario.class,
              TxIdSeed.into("tx-reconcile")
                  .andThen(CellarEntriesSeed.into(tree.descendingSource())));
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The staging paths the PROMOTED tag posed on the rebuilt model — empty when none was posed. */
  private static List<String> posedPromotedFrom(ReportModel runbook) {
    return runbook.getTagMap().values().stream()
        .filter(tag -> GraftTag.PROMOTED.type().equals(tag.getType()))
        .flatMap(tag -> tag.getValues().stream())
        .toList();
  }

  /**
   * The {@code host-live} entry the run committed, read through a {@link ScenarioCellar} over the
   * rebuilt model with an EMPTY durable side — so the inherited source (stripped before the graft)
   * does not leak, and a peek returns ONLY the run's own store: the {@code syncedFrom} on a real
   * flip, empty on a no-op / preview.
   */
  private static Optional<String> committedLive(ReportModel runbook) {
    final ScenarioCellar cellar = new ScenarioCellar(() -> runbook, StubCellar::empty, "");
    return cellar
        .fetch(PARCEL, IncusCoordinate.HOST_LIVE, HostLiveEntry.class)
        .map(HostLiveEntry::syncedFrom);
  }

  /** A RunGate the test pins to a chosen cultivating value. */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /**
   * The host tree the reconcile reads, laid out under a temp node root — the {@code source} staging
   * (this run's), the {@code pivot} staging the live currently mirrors, and the physical {@code
   * host.live.d}. It carries BOTH the FS layout (for the jsync promote + the drift observe) AND the
   * cellar facets (the checksum maps that drive the change decision), decoupled on purpose: the
   * decision is a checksum-map compare, the act is an FS sync. The {@code liveRoot} the scion
   * derives is {@code sourceRoot}'s sibling {@code host.live.d}, so the layout matches.
   */
  private record HostTree(
      Path sourceRoot,
      Path pivotRoot,
      Path liveRoot,
      Map<String, String> sourceChecksums,
      Map<String, String> pivotChecksums) {

    /**
     * A CHANGED tree: source at {@code host.1.staging.d} with {@code sourceContent}, a pivot at
     * {@code host.2.staging.d} the live mirrors with {@code pivotContent} (their checksum maps
     * differ, so the change decides true). The live starts as the pivot, so the drift is empty.
     */
    static HostTree laidOut(Path node, String sourceContent, String pivotContent) throws Exception {
      final Path source = write(node.resolve("host.1.staging.d"), sourceContent);
      final Path pivot = write(node.resolve("host.2.staging.d"), pivotContent);
      final Path live = write(node.resolve("host.live.d"), pivotContent);
      return new HostTree(
          source, pivot, live, Map.of(DEPLOYMENT, "sha-source"), Map.of(DEPLOYMENT, "sha-pivot"));
    }

    /** A NO-OP tree: source and pivot share ONE checksum map, so the change decides false. */
    static HostTree noChange(Path node, String content) throws Exception {
      final Path source = write(node.resolve("host.1.staging.d"), content);
      final Path pivot = write(node.resolve("host.2.staging.d"), content);
      final Path live = write(node.resolve("host.live.d"), content);
      final Map<String, String> shared = Map.of(DEPLOYMENT, "sha-same");
      return new HostTree(source, pivot, live, shared, shared);
    }

    /** The source staging DESCENDING through the ALLER channel — one encoded inherited entry. */
    List<String> descendingSource() {
      final HostStagingEntry source =
          HostStagingEntry.of(
              sourceRoot.toString(), sourceChecksums, new Provenance("head", false));
      final ScenarioCellar.Entry entry =
          new ScenarioCellar.Entry(
              PARCEL,
              SeedEnvelope.of(IncusCoordinate.HOST_STAGING, CODEC.encode(source)),
              false,
              false);
      return List.of(CODEC.encode(entry));
    }

    /** The durable read side carrying the pivot — the {@code host-live} + its staging checksums. */
    Cellar durablePivot() {
      final HostStagingEntry pivot =
          HostStagingEntry.of(pivotRoot.toString(), pivotChecksums, new Provenance("prior", false));
      return new StubCellar(Optional.of(HostLiveEntry.of(pivotRoot.toString())), List.of(pivot));
    }

    private static Path write(Path stagingRoot, String content) throws Exception {
      final Path file = stagingRoot.resolve(DEPLOYMENT);
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
      return stagingRoot;
    }
  }

  /**
   * The durable read side standing in for prod's Pulumi cellar (which no test can run): it holds a
   * fixed pivot — the {@code host-live} entry and the staging timeline the scion folds the pivot
   * checksums from — and nothing else. Reconcile is a FRAGMENT, so its stores never reach here (a
   * store is a tag on the model, drained only by a ROOT run); {@link #store} is inert. {@link
   * #empty()} is the read-back stand-in (no pivot), so a peek returns only the model's own writes.
   */
  private record StubCellar(Optional<HostLiveEntry> live, List<HostStagingEntry> stagings)
      implements Cellar {

    static StubCellar empty() {
      return new StubCellar(Optional.empty(), List.of());
    }

    @Override
    public <T> void store(Parcel parcel, SeedCoordinate coordinate, T value) {}

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> fetch(Parcel parcel, Class<T> type) {
      return type.equals(HostStagingEntry.class) ? (List<T>) stagings : List.of();
    }

    @Override
    public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return IncusCoordinate.HOST_LIVE.slug().equals(coordinate.slug())
          ? live.map(type::cast)
          : Optional.empty();
    }

    @Override
    public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of(parcel);
    }
  }
}
