package io.nxmatic.rke2lab.incus.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.incus.contract.HostDriftEntry;
import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.incus.core.HostTreeDelta;
import io.nxmatic.rke2lab.incus.core.HostTreeDeltaRenderer;
import io.nxmatic.rke2lab.incus.core.HostTreeDiffer;
import io.nxmatic.rke2lab.incus.core.HostTreePromoter;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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
 * of the staging slot. So the whole cycle reads from the ambient {@link Cellar} + {@link RunGate}
 * this bundle's registry holds.
 *
 * <p>The gate splits by NATURE (§ Live vs preview): the {@code change} delta (a checksum-map
 * compare, no FS touch) is the DECISION, computed always; the promotion is the live edge effect, so
 * a closed gate promotes nothing (the step renders PENDING via E9, the cellar is not written). The
 * {@code drift} (the live's out-of-band deviation vs the pivot) is OBSERVED on the FS before the
 * sync and reported — NEVER a decision gate (if the source changed we promote, the staging is
 * authoritative, the drift is overwritten; the report is its only role).
 */
@SeedScenario
public class IncusReconcileScenario
    extends ScenarioTestBase<
        IncusReconcileScenario.Given, IncusReconcileScenario.When, IncusReconcileScenario.Then>
    implements CellarReceiver<ScenarioCellar> {

  private static final String NODE = "bioskop-master";

  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the reconcile scenario has not played yet — no runbook to harvest");
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body — reconcile READS the host-tree
  // state from it (source + pivot) and WRITES the promotion outcome back. @MonotonicNonNull: null
  // until receiveCellar sets it (before the body), then read.
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
  void the_live_tree_is_reconciled() {
    final RunGate gate = resolveGate();
    final Parcel parcel = resolveParcel();
    final Reconciliation reconciliation = Reconciliation.foldFrom(cellar, parcel);
    given().the_host_tree(NODE).and().reconciling_through(gate, cellar, parcel, reconciliation);
    when().the_run_condition_is_read().and().the_change_is_decided().and().the_live_is_promoted();
    then().the_live_tree_is_reconciled();
    // Narrate a real promotion on the model — the PROMOTED tag projects the committed HostLiveEntry
    // (one truth, two renderings): the durable store is the fact, this rides the graft up so the
    // host tree shows "promoted from host.N.staging.d". Only on a real flip (change + live gate).
    if (gate.cultivating() && reconciliation.hasSource() && reconciliation.changed()) {
      getScenario()
          .getModel()
          .addTag(GraftTag.PROMOTED.of(reconciliation.source().get().stagingRoot()));
    }
    LAST_RUNBOOK.set(getScenario().getModel());
  }

  private RunGate resolveGate() {
    return require(
        RunGate.class,
        "no RunGate in the registry (the host publishes it at boot, a test registers a mock)");
  }

  private Parcel resolveParcel() {
    return require(
        Parcel.class,
        "no current Parcel in the registry (the host publishes it at the GIVEN like the RunGate)");
  }

  private <T> T require(Class<T> type, String message) {
    return ScenarioRegistry.of(this).require(type, message);
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

  /** Given: the node under reconcile, the run gate, and the folded host-tree state. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState RunGate gate;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Parcel parcel;
    @ProvidedScenarioState Reconciliation reconciliation;

    public Given the_host_tree(@Quoted String node) {
      return self();
    }

    @Hidden
    public Given reconciling_through(
        RunGate gate, Cellar cellar, Parcel parcel, Reconciliation reconciliation) {
      this.gate = gate;
      this.cellar = cellar;
      this.parcel = parcel;
      this.reconciliation = reconciliation;
      return self();
    }
  }

  /**
   * When: read the run condition, decide on the {@code change} (a checksum-map compare), and — only
   * when cultivating and the source changed — OBSERVE the drift on the FS then PROMOTE the staging
   * into {@code host.live.d}. A closed gate promotes nothing (the plan renders PENDING via E9); an
   * empty change is a NO-OP (the live already mirrors this staging).
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState RunGate gate;
    @ExpectedScenarioState Reconciliation reconciliation;

    @ProvidedScenarioState boolean cultivating;
    @ProvidedScenarioState boolean promoted;
    @ProvidedScenarioState Optional<HostDriftEntry> drift;

    private final HostTreePromoter promoter = new HostTreePromoter();
    private final HostTreeDiffer differ = new HostTreeDiffer();
    private final HostTreeDeltaRenderer renderer = new HostTreeDeltaRenderer();

    public When the_run_condition_is_read() {
      this.cultivating = gate.cultivating();
      this.drift = Optional.empty();
      this.promoted = false;
      return self();
    }

    public When the_change_is_decided() {
      // A checksum-map compare, no FS touch: empty change ⇒ the live already mirrors this staging,
      // a NO-OP. The gate does NOT enter here — the decision is computed live AND preview.
      return self();
    }

    public When the_live_is_promoted() {
      if (!reconciliation.hasSource() || !reconciliation.changed()) {
        return self(); // NO-OP — nothing to flip
      }
      if (!cultivating) {
        return self(); // preview — the live edge is gated (PENDING via E9), the cellar not written
      }
      // OBSERVE the live's out-of-band deviation vs the pivot BEFORE the sync overwrites it, and
      // record it as a drift entry (its report rendered beside the staging). Never a decision gate.
      final HostTreeDelta driftDelta =
          differ.diff(reconciliation.pivotRoot(), reconciliation.liveRoot());
      if (!driftDelta.isEmpty()) {
        final Path report = renderer.render(reconciliation.driftBase(), driftDelta);
        this.drift =
            Optional.of(
                HostDriftEntry.of(report.toString(), reconciliation.pivotRoot().toString()));
      }
      // ACT — sync the staging into host.live.d (jsync, --delete, skip-flox).
      final HostStagingEntry source = reconciliation.source().orElseThrow();
      promoter.promote(Path.of(source.stagingRoot()), reconciliation.liveRoot());
      this.promoted = true;
      return self();
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
    @ExpectedScenarioState boolean promoted;
    @ExpectedScenarioState Optional<HostDriftEntry> drift;

    public Then the_live_tree_is_reconciled() {
      if (!promoted) {
        return self();
      }
      final HostStagingEntry source = reconciliation.source().orElseThrow();
      cellar.store(parcel, IncusCoordinate.HOST_LIVE, HostLiveEntry.of(source.stagingRoot()));
      drift.ifPresent(entry -> cellar.store(parcel, IncusCoordinate.HOST_DRIFT, entry));
      return self();
    }
  }
}
