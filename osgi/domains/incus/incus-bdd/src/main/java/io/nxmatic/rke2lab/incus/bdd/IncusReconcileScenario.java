package io.nxmatic.rke2lab.incus.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.incus.contract.HostDriftEntry;
import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.HostTreePromoter;
import io.nxmatic.rke2lab.incus.contract.HostTreePromoter.Promotion;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The incus RECONCILE checkpoint — the twin of {@link IncusProvisionScenario}, told in the incus
 * domain's own vocabulary. Where {@code prepare} answers "is the material ready?" (image +
 * manifests), reconcile answers "must the live tree flip, and onto what?": it FETCHES the host-tree
 * state from the cellar, decides on a checksum diff whether the {@code host.live.d} the instance
 * mounts is stale, and if so PROMOTES the fresh staging into it ({@link HostTreePromoter},
 * incus-core, jsync). It runs BEFORE the instance grows (§ host-cellar-realisation, the reconcile
 * cycle — the live must be current before the VM mounts it).
 *
 * <p>It derives EVERYTHING from the cellar, needing no worktree amendment (unlike prepare): the
 * {@code source} to promote is the {@code host-staging} entry the run holds (prepare published it
 * this run — the cellar's overlay returns the in-flight one, § seed-broker-spec, the entries
 * descend); the {@code pivot} is the {@code host-live} entry's {@code syncedFrom} (a prior-run
 * committed fact, empty on the first run); the {@code live} root is the {@code host.live.d} sibling
 * of the staging slot. So the whole cycle reads from the ambient {@link Cellar} this bundle's
 * registry holds; it is MODE-BLIND — it injects no {@code RunGate}.
 *
 * <p>The split by NATURE lives at the FRONTIER, not in the scion: the {@code change} delta (a
 * checksum-map compare, no FS touch) is the DECISION, computed in both modes; the promotion is the
 * live edge effect, so a surveying run gets the surveying promoter impl (touches nothing, the step
 * renders PENDING via E9, the cellar is not written). The {@code drift} (the live's out-of-band
 * deviation vs the pivot) is OBSERVED — inside the cultivating impl, before the sync — and
 * reported, NEVER a decision (if the source changed we promote, the staging is authoritative, the
 * drift is overwritten; the report is its only role).
 */
@SeedScenario
public class IncusReconcileScenario
    extends ScenarioTestBase<
        IncusReconcileScenario.Given, IncusReconcileScenario.When, IncusReconcileScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private static final String NODE = "bioskop-master";

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body — reconcile READS the host-tree
  // state from it (source + pivot) and WRITES the promotion outcome back. @MonotonicNonNull: null
  // until receiveCellar sets it (before the body), then read.
  @MonotonicNonNull private ScenarioCellar cellar;

  // Injected by the OsgiServiceExtension from THIS bundle's registry before the body (the
  // @Reference a Jupiter-instantiated scenario cannot have). Uniform Optional (never null — the
  // bridge owns presence): both required, awaited from SCR, so their orElseThrow never fires.
  @OsgiService private Optional<HostTreePromoter> promoter = Optional.empty();
  @OsgiService private Optional<Parcel> parcelService = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_live_tree_is_reconciled() {
    final HostTreePromoter promoter = this.promoter.orElseThrow();
    final Parcel parcel = parcelService.orElseThrow();
    final Reconciliation reconciliation = Reconciliation.foldFrom(cellar, parcel);
    // The @Test-owned sink the When fills with the promoter's factual outcome — read below for the
    // tag, independent of jGiven's stage state. Mode-blind: the tag follows what ACTUALLY flipped
    // (promotion.promoted()), which the frontier's cultivating/surveying impl decides, never a
    // gate.
    final AtomicReference<Promotion> promotion = new AtomicReference<>(Promotion.notPromoted());
    given()
        .the_host_tree(NODE)
        .and()
        .reconciling_through(promoter, cellar, parcel, reconciliation, promotion);
    when().the_change_is_decided().and().the_live_is_promoted();
    then().the_live_tree_is_reconciled();
    // Narrate a real promotion on the model — the PROMOTED tag projects the committed HostLiveEntry
    // (one truth, two renderings): the durable store is the fact, this rides the graft up so the
    // host tree shows "promoted from host.N.staging.d". Only on a real flip (the cultivating impl
    // promoted; a surveying run reports notPromoted, so no tag).
    if (promotion.get().promoted()) {
      getScenario()
          .getModel()
          .addTag(GraftTag.PROMOTED.of(reconciliation.source().get().stagingRoot()));
    }
  }

  /**
   * The host-tree state reconcile folds from the cellar, ONCE — the {@code source} staging to
   * promote (the run's own {@code host-staging} entry), the {@code pivot} the deltas compare
   * against ({@code live.syncedFrom} and its checksums), and the derived FS roots. {@link
   * #UNRECONCILABLE} when the cellar holds no source (a bare survey, or a run that published no
   * staging): there is nothing to promote.
   */
  private record Reconciliation(
      Optional<HostStagingEntry> source,
      Map<String, String> pivotChecksums,
      Path liveRoot,
      Path pivotRoot,
      Path driftBase) {

    static final Reconciliation UNRECONCILABLE =
        new Reconciliation(Optional.empty(), Map.of(), Path.of(""), Path.of(""), Path.of(""));

    static Reconciliation foldFrom(Cellar cellar, Parcel parcel) {
      final Optional<HostStagingEntry> source =
          cellar.fetch(parcel, IncusCoordinate.HOST_STAGING, HostStagingEntry.class);
      if (source.isEmpty()) {
        return UNRECONCILABLE;
      }
      // The pivot the live currently mirrors (empty on a first run) and its checksums — found among
      // the timeline's stagings by its path (the entry prepare published for that slot).
      final Optional<String> pivotPath =
          cellar
              .fetch(parcel, IncusCoordinate.HOST_LIVE, HostLiveEntry.class)
              .map(HostLiveEntry::syncedFrom);
      final Map<String, String> pivotChecksums =
          pivotPath
              .flatMap(
                  path ->
                      cellar.fetch(parcel, HostStagingEntry.class).stream()
                          .filter(entry -> entry.stagingRoot().equals(path))
                          .findFirst())
              .map(HostStagingEntry::checksums)
              .orElse(Map.of());
      final Path stagingRoot = Path.of(source.get().stagingRoot());
      final Path liveRoot = stagingRoot.resolveSibling("host.live.d");
      final Path pivotRoot =
          pivotPath.map(Path::of).orElse(stagingRoot.resolveSibling("host.live.d"));
      // The drift report sits beside the staging it describes: host.<N>.staging.d → host.<N>.drift.
      final Path driftBase =
          stagingRoot.resolveSibling(
              stagingRoot.getFileName().toString().replace(".staging.d", ".drift"));
      return new Reconciliation(source, pivotChecksums, liveRoot, pivotRoot, driftBase);
    }

    boolean hasSource() {
      return source.isPresent();
    }

    /** The intended change — this run's staging vs the pivot, on CHECKSUM maps (no FS touch). */
    boolean changed() {
      return source.map(entry -> !entry.checksums().equals(pivotChecksums)).orElse(false);
    }
  }

  /**
   * Given: the node under reconcile, the promoter contact, the folded state, and the outcome sink.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState HostTreePromoter promoter;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Parcel parcel;
    @ProvidedScenarioState Reconciliation reconciliation;
    @ProvidedScenarioState AtomicReference<Promotion> promotionSink;

    public Given the_host_tree(@Quoted String node) {
      return self();
    }

    @Hidden
    public Given reconciling_through(
        HostTreePromoter promoter,
        Cellar cellar,
        Parcel parcel,
        Reconciliation reconciliation,
        AtomicReference<Promotion> promotionSink) {
      this.promoter = promoter;
      this.cellar = cellar;
      this.parcel = parcel;
      this.reconciliation = reconciliation;
      this.promotionSink = promotionSink;
      return self();
    }
  }

  /**
   * When: decide on the {@code change} (a checksum-map compare), and — only when the source changed
   * — drive the promoter contact once, MODE-BLIND. The frontier already chose the impl: cultivating
   * (observes drift + syncs into {@code host.live.d}) or surveying (touches nothing). An empty
   * change is a NO-OP (the live already mirrors this staging), reported {@link
   * Promotion#notPromoted()}. The factual {@link Promotion} rides the sink to the @Test and the
   * stage state to the Then.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState HostTreePromoter promoter;
    @ExpectedScenarioState Reconciliation reconciliation;
    @ExpectedScenarioState AtomicReference<Promotion> promotionSink;

    @ProvidedScenarioState Promotion promotion;

    public When the_change_is_decided() {
      // A checksum-map compare, no FS touch: empty change ⇒ the live already mirrors this staging,
      // a NO-OP. The mode does NOT enter here — the decision is computed live AND survey.
      return self();
    }

    public When the_live_is_promoted() {
      this.promotion = promote();
      promotionSink.set(promotion);
      return self();
    }

    private Promotion promote() {
      if (!reconciliation.hasSource() || !reconciliation.changed()) {
        return Promotion.notPromoted(); // NO-OP — nothing to flip
      }
      // Mode-blind: the cultivating impl observes the drift + syncs; the surveying impl touches
      // nothing and reports notPromoted. The step renders PENDING under a survey via E9.
      final HostStagingEntry source = reconciliation.source().orElseThrow();
      return promoter.promote(
          Path.of(source.stagingRoot()),
          reconciliation.liveRoot(),
          reconciliation.pivotRoot(),
          reconciliation.driftBase());
    }
  }

  /**
   * Then: the live tree is reconciled. On a REAL promotion it commits the {@link HostLiveEntry}
   * (the new {@code syncedFrom}) and any {@link HostDriftEntry} to the cellar, and poses the
   * PROMOTED tag (a projection of the live entry, for the runbook). A NO-OP / preview writes
   * nothing — the cellar is the committed truth, written once when the flip is done.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Cellar cellar;
    @ExpectedScenarioState Parcel parcel;
    @ExpectedScenarioState Reconciliation reconciliation;
    @ExpectedScenarioState Promotion promotion;

    public Then the_live_tree_is_reconciled() {
      if (!promotion.promoted()) {
        return self();
      }
      final HostStagingEntry source = reconciliation.source().orElseThrow();
      cellar.store(parcel, IncusCoordinate.HOST_LIVE, HostLiveEntry.of(source.stagingRoot()));
      promotion.drift().ifPresent(entry -> cellar.store(parcel, IncusCoordinate.HOST_DRIFT, entry));
      return self();
    }
  }
}
