package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.seed.broker.codec.PassphraseCellarCipher;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Breadcrumb;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.CellarCipher;
import io.nxmatic.rke2lab.seed.broker.port.CellarCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.Sensitivity;
import io.nxmatic.rke2lab.seed.broker.port.Trail;
import io.nxmatic.rke2lab.seed.broker.port.TransactionalCellar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The UNIVERSAL transactional cellar: the single {@link Cellar} every scenario — host root AND
 * in-container scion — is handed, and the SOLE writer of a tag on the run's {@link ReportModel}.
 * {@code store} does NOT touch the durable backend: it posts the codec-encoded value as a {@link
 * tag} on the model (the within-run write set). The graft folds a scion's tags into the host trunk,
 * and at the run boundary the root's {@code ScenarioCellarExtension} DRAINS the folded tags to the
 * durable {@link io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar} — so a fragment never persists,
 * only the root does, atomically (§ seed-broker-spec, cellar-transactional).
 *
 * <p>The reads are an OVERLAY: the run's own read-write set (its {@code store}s and {@code
 * withdraw} tombstones on the model) takes precedence, the durable {@link Cellar} is the fallback.
 * So a scenario reads back what it just wrote (read-your-writes) before the drain has made anything
 * durable, and a {@code withdraw} shadows the durable case for the rest of the run. {@code
 * neighbours} alone is pure delegation — it names parcels (topology), not values, so the set never
 * shadows it. A scenario with no durable read side (a pure in-container fragment whose reads are
 * mocked) is handed a delegate for the fallback; the overlay itself needs only the model.
 *
 * <p>The {@link ReportModel} is read LAZILY (a {@link Supplier}, resolved at the first {@code
 * store}), so the extension can inject this cellar before jGiven has bound the model to the
 * scenario — the order between the two post-processors is a non-issue.
 */
public final class ScenarioCellar implements TransactionalCellar {

  private final Supplier<ReportModel> model;
  private final Supplier<Cellar> durableReads;
  private final Optional<String> txId;
  private final SeedCodec codec = new SeedCodec();
  // The mono clean/smudge filter (§ cellar-secrets). A SEALED store is sealed HERE, so its sealed
  // payload is what rides the run's write-set tag across the graft and drains to the durable
  // backend
  // — the harvest plaintext never crosses the seam.
  private final CellarCipher cipher = new PassphraseCellarCipher();

  /**
   * @param model the run's {@link ReportModel} (read lazily, but live already when the extension
   *     constructs this — jGiven binds it before the body)
   * @param durableReads the durable read side, resolved on first fetch
   * @param txId the run's transaction id, or {@link Optional#empty()} for a run outside a
   *     transaction — the cellar is its lifecycle-mate, so it POSTS the tx-id tag here (the sole
   *     writer of a tag on the model), and a scion sowing a sub-scion reads it back via {@link
   *     #transactionId()}
   */
  public ScenarioCellar(
      Supplier<ReportModel> model, Supplier<Cellar> durableReads, Optional<String> txId) {
    this.model = model;
    this.durableReads = durableReads;
    this.txId = txId;
    txId.ifPresent(id -> model.get().addTag(Tag.TRANSACTION.of(id)));
  }

  /**
   * The run's transaction id, or empty when this cellar is not transactional (a scenario played
   * outside a run — its own isolated test). A scion sowing a sub-scion passes this straight to the
   * sow: present ⇒ the sub-scion inherits the tx; empty ⇒ a non-transactional play, legitimately no
   * correlation. So "error if transactional, tolerated otherwise" is encoded by the value itself —
   * no ad-hoc emptiness check at the call site.
   */
  @Override
  public Optional<String> transactionId() {
    return txId;
  }

  /**
   * This run's entries as flat encoded strings — the instance view of {@link
   * #entriesEncodedOf(ReportModel)} over this cellar's own model. A {@code *RunbookHandler} reads
   * it to hand DOWN the transaction's in-flight stores when it launches a sub-scion (the ALLER
   * sense, § seed-broker-spec, the entries descend); the sub-scion's extension re-posts them via
   * {@link #inheritEntries}. Flat by construction, so nothing live crosses the launcher membrane.
   */
  @Override
  public List<String> entriesEncoded() {
    return entriesEncodedOf(model.get());
  }

  /**
   * One accumulated cellar operation as it rides the model: the {@link Parcel} it is filed under,
   * the {@link SeedEnvelope} (its {@code coordinate} always meaningful; its {@code payload} the
   * encoded value for a store, empty for a tombstone), and whether it is a {@code tombstone} — a
   * {@code withdraw} that empties the case. Flat (the codec serialises it to the tag's value
   * String). Entries ride in store ORDER, so the drain replays each in turn: a store re-stores the
   * envelope, a tombstone withdraws the case (a store→withdraw→store sequence lands the case
   * present, as it must).
   */
  public record Entry(Parcel parcel, SeedEnvelope envelope, boolean tombstone, boolean inherited) {

    /**
     * The same operation seen from a CHILD crossing — marked {@code inherited} so the overlay reads
     * it (read-your-parent's-writes) but the child's graft does NOT fold it back up into the trunk
     * (the trunk already holds it; the extension strips it first). Orthogonal to {@code tombstone}:
     * a parent's WITHDRAW descends as an inherited tombstone (else the child re-reads a durable
     * case the parent emptied in-flight).
     */
    Entry asInherited() {
      return new Entry(parcel, envelope, tombstone, true);
    }
  }

  /**
   * The accumulated {@link Entry entries} on a {@code model}, decoded from the {@link Tag#ENTRY}
   * tags — the cellar is the SOLE writer of that tag, so it is also the sole READER of its format.
   * Package-private: the run-boundary DRAIN (its lifecycle-mate {@code ScenarioCellarExtension},
   * same package) consumes it, and the overlay reads use it internally; the format stays the
   * cellar's private concern (a test reads back through the generic {@link #fetch} API, not this).
   * Order is the model's tag order, i.e. store order.
   */
  static List<Entry> entriesOf(ReportModel model) {
    final SeedCodec codec = new SeedCodec();
    return model.getTagMap().values().stream()
        .filter(tag -> Tag.ENTRY.type().equals(tag.getType()))
        .flatMap(tag -> tag.getValues().stream())
        .map(value -> codec.decode(value, Entry.class))
        .toList();
  }

  /**
   * The run's entries as they ride the model — the ENCODED {@link Entry} strings (the raw {@link
   * Tag#ENTRY} tag values), in store order. This is a VIEW on the caller's {@link ReportModel}, the
   * ALLER-sense twin of the runbook JSON the graft carries the other way: the host reads it off the
   * trunk at a {@code sow}, hands it down (§ seed-broker-spec, the entries descend), and the
   * child's extension re-posts it via {@link #inheritEntries}. Encoded (not decoded) because it
   * only needs to cross flat and be re-posted verbatim, never inspected host-side.
   */
  public static List<String> entriesEncodedOf(ReportModel model) {
    return model.getTagMap().values().stream()
        .filter(tag -> Tag.ENTRY.type().equals(tag.getType()))
        .flatMap(tag -> tag.getValues().stream())
        .toList();
  }

  /**
   * Re-post a parent transaction's entries onto THIS run's model, each marked {@code inherited} —
   * the ALLER sense of the transaction (the child reads its parent's in-flight stores as its own
   * overlay). Called by the extension BEFORE the body, so the inherited entries precede the child's
   * own stores in tag order: on a shared coordinate the child's own store (posted later) wins,
   * giving own {@literal >} inherited {@literal >} durable for free. The cellar is the SOLE tag
   * writer, so this re-posting lives here, not in the extension.
   */
  public void inheritEntries(List<String> encodedEntries) {
    for (String encoded : encodedEntries) {
      append(codec.decode(encoded, Entry.class).asInherited());
    }
  }

  /**
   * Remove the inherited entries from {@code model}'s tag map — the child's extension calls this
   * AFTER the body, before the graft folds the model up into the trunk, so an inherited entry
   * (which the trunk already holds from the parent crossing) is not folded up a SECOND time and
   * drained twice. Clean on jGiven 2.0.3: a cellar tag is added via {@code ReportModel.addTag} (the
   * tag map alone) and never referenced by a {@code ScenarioModel.tagIds}, so removing it leaves no
   * dangling id. Each {@link Tag#ENTRY} tag holds exactly one entry (a distinct {@code
   * toIdString}), so the per-tag test is unambiguous.
   */
  public static void stripInherited(ReportModel model) {
    final SeedCodec codec = new SeedCodec();
    model
        .getTagMap()
        .values()
        .removeIf(
            tag ->
                Tag.ENTRY.type().equals(tag.getType())
                    && tag.getValues().stream()
                        .anyMatch(value -> codec.decode(value, Entry.class).inherited()));
  }

  /**
   * The cellar's tags on a scenario's {@code ReportModel} — nested here because the cellar is their
   * SOLE writer (§ seed-broker-spec cellar-transactional). Referenced qualified ({@code
   * ScenarioCellar.Tag}), so it does not clash with jGiven's {@code
   * com.tngtech.jgiven.report.model.Tag}. The graft folds them into the root tree; the drain reads
   * {@link #ENTRY}, a scion sowing a sub-scion reads {@link #TRANSACTION}.
   */
  public enum Tag implements ScenarioTag {

    /** An accumulated store — the within-run write set the root drains to the durable backend. */
    ENTRY("cellar-entry"),

    /** The run's transaction id (a root-minted UUID) — audit correlation across the crossing. */
    TRANSACTION("tx-id");

    private final String type;

    Tag(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }
  }

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
    final String encoded = codec.encode(value);
    final String payload = sensitivity == Sensitivity.SEALED ? cipher.seal(encoded) : encoded;
    final SeedEnvelope envelope =
        SeedEnvelope.of(coordinate, payload).withTrail(trailFor(parcel, coordinate));
    append(new Entry(parcel, envelope, false, false));
  }

  /**
   * Stamp the value's fil d'Ariane at {@code coordinate}: the run's git root breadcrumb — read back
   * from {@link CellarCoordinate#RUN_PROVENANCE}, present once the worktree crossing has harvested
   * HEAD and descended to sibling crossings by the ordinary transactional inheritance — followed by
   * THIS coordinate's link (carrying the same git source, so each link is self-describing). The
   * root declaration itself carries no lineage (it IS the root). A store filed before any
   * provenance is known yields a lone self-link with an empty sha — a legitimate pre-provenance
   * root.
   */
  private Trail trailFor(Parcel parcel, SeedCoordinate coordinate) {
    if (isRunProvenance(coordinate)) {
      return Trail.empty();
    }
    final Optional<Breadcrumb> root = runProvenance(parcel);
    final Breadcrumb here =
        new Breadcrumb(
            coordinate.domain(),
            coordinate.slug(),
            root.map(Breadcrumb::sha).orElse(""),
            root.map(Breadcrumb::dirty).orElse(false));
    return root.map(r -> new Trail(List.of(r)).push(here)).orElse(new Trail(List.of(here)));
  }

  /**
   * The run's git root breadcrumb from the OVERLAY only (the run's own store or a crossing it
   * inherited), never the durable fallback — a prior run's durable provenance must not root THIS
   * run's stores. Empty until the first crossing harvests HEAD.
   */
  private Optional<Breadcrumb> runProvenance(Parcel parcel) {
    return latestSetEntry(parcel, CellarCoordinate.RUN_PROVENANCE)
        .filter(entry -> !entry.tombstone())
        .map(entry -> codec.decode(cipher.reveal(entry.envelope().payload()), Breadcrumb.class));
  }

  private boolean isRunProvenance(SeedCoordinate coordinate) {
    return coordinate.domain().equals(CellarCoordinate.RUN_PROVENANCE.domain())
        && coordinate.slug().equals(CellarCoordinate.RUN_PROVENANCE.slug());
  }

  /** Append one operation to the read-write set — a new {@link Tag#ENTRY} tag, in store order. */
  private void append(Entry entry) {
    model.get().addTag(Tag.ENTRY.of(codec.encode(entry)));
  }

  /**
   * Peek ONE case, DECODED — an OVERLAY read: the run's own read-write set (the {@link Tag#ENTRY}
   * tags on the model) takes precedence over the durable backend, so a scenario reads back what it
   * just {@code store}d (read-your-writes) before the drain has made anything durable. The set's
   * LAST entry for {@code (parcel, coordinate)} wins (a re-store overwrites); only when the set has
   * nothing for that case does it fall through to the durable read side.
   */
  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final Optional<Entry> latest = latestSetEntry(parcel, coordinate);
    if (latest.isEmpty()) {
      return durableReads
          .get()
          .fetch(parcel, coordinate, type); // set silent here → the durable case
    }
    // The set has the last word on this case: a store → its value; a tombstone → EMPTY, and it does
    // NOT fall back to the durable (the withdraw's intent is "gone for this run", the durable is
    // shadowed, not consulted).
    return latest
        .filter(entry -> !entry.tombstone())
        .map(entry -> codec.decode(cipher.reveal(entry.envelope().payload()), type));
  }

  /**
   * The whole timeline DECODED — the OVERLAY of the read-write set over the durable timeline: the
   * run's own CURRENT values for {@code parcel} first (the last entry per coordinate; a tombstoned
   * coordinate contributes nothing), then the durable timeline. Fail-at-end: an entry the codec
   * cannot read into {@code type} is skipped, so a fold over one domain's records tolerates a
   * foreign coordinate in the set.
   */
  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    final List<T> overlaid = new ArrayList<>();
    for (Entry entry : currentSetEntries(parcel)) {
      try {
        overlaid.add(codec.decode(cipher.reveal(entry.envelope().payload()), type));
      } catch (RuntimeException skip) {
        // not readable into type (a foreign coordinate) — skip, keep the fold going.
      }
    }
    overlaid.addAll(durableReads.get().fetch(parcel, type));
    return overlaid;
  }

  /**
   * TAKE one case out, DECODED — the OVERLAY value ({@link #fetch(Parcel, SeedCoordinate, Class)}:
   * the run's own store, else the durable one), then RECORD the withdraw as a tombstone {@link
   * Entry} in the read-write set. The tombstone empties the case within the run (a later {@code
   * fetch} sees the case gone) and is replayed at the drain as a durable {@code withdraw} — the set
   * is the transaction, so the take is deferred like every other write, not applied to the durable
   * backend now.
   */
  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final Optional<T> current = fetch(parcel, coordinate, type);
    append(new Entry(parcel, SeedEnvelope.of(coordinate, ""), true, false));
    return current;
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return durableReads.get().neighbours(parcel);
  }

  /**
   * The current value's fil d'Ariane at {@code coordinate}, from the OVERLAY: the trail stamped on
   * the last {@link Entry} filed there (a tombstoned case has none). Reads the CLEAR trail off the
   * envelope — no decode, no reveal — so a SEALED value's lineage is traceable without the
   * passphrase. Empty when the run's write set is silent on this case; the durable edge does not
   * yet carry the trail (§ fil-d-ariane, a handoff item), so there is no durable fallback to
   * consult.
   */
  @Override
  public Optional<Trail> trailOf(Parcel parcel, SeedCoordinate coordinate) {
    return latestSetEntry(parcel, coordinate)
        .filter(entry -> !entry.tombstone())
        .map(entry -> entry.envelope().trail());
  }

  /**
   * The LAST read-write-set entry filed at {@code (parcel, coordinate)} — store OR tombstone — or
   * empty if the set is silent on this case. The caller reads {@link Entry#tombstone()} to tell a
   * withdraw (case emptied in-run, durable shadowed) from a store (its value), and an empty result
   * (set silent) means "consult the durable".
   */
  private Optional<Entry> latestSetEntry(Parcel parcel, SeedCoordinate coordinate) {
    Entry latest = null;
    for (Entry entry : setEntriesFor(parcel)) {
      if (entry.envelope().coordinate().equals(coordinate.slug())) {
        latest = entry; // store order → the last match is the current value
      }
    }
    return Optional.ofNullable(latest);
  }

  /**
   * The read-write set's CURRENT non-empty cases under {@code parcel}: the last entry per
   * coordinate (store order, last wins), a coordinate whose last entry is a tombstone DROPPED (the
   * case is empty in-run). The overlay's live state, coordinate order preserved by first
   * appearance.
   */
  private List<Entry> currentSetEntries(Parcel parcel) {
    final Map<String, Entry> byCoordinate = new LinkedHashMap<>();
    for (Entry entry : setEntriesFor(parcel)) {
      byCoordinate.put(entry.envelope().coordinate(), entry);
    }
    return byCoordinate.values().stream().filter(entry -> !entry.tombstone()).toList();
  }

  /** The read-write-set entries filed under {@code parcel}, in store order. */
  private List<Entry> setEntriesFor(Parcel parcel) {
    return entriesOf(model.get()).stream().filter(entry -> entry.parcel().equals(parcel)).toList();
  }
}
