package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
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
 * <p>The reads ({@code fetch}/{@code neighbours}) are NOT transactional — they delegate to a
 * durable {@link Cellar} directly (no cache; re-hitting the backend twice a run is correct and
 * negligible). A scenario with no durable read side (a pure in-container fragment whose reads are
 * mocked) is handed a delegate for them; the transactional {@code store} needs only the model.
 *
 * <p>The {@link ReportModel} is read LAZILY (a {@link Supplier}, resolved at the first {@code
 * store}), so the extension can inject this cellar before jGiven has bound the model to the
 * scenario — the order between the two post-processors is a non-issue.
 */
public final class ScenarioCellar implements Cellar {

  private final Supplier<ReportModel> model;
  private final Supplier<Cellar> durableReads;
  private final String txId;
  private final SeedCodec codec = new SeedCodec();

  /**
   * @param model the run's {@link ReportModel} (read lazily, but live already when the extension
   *     constructs this — jGiven binds it before the body)
   * @param durableReads the durable read side, resolved on first fetch
   * @param txId the run's transaction id, or empty for a run outside a transaction — the cellar is
   *     its lifecycle-mate, so it POSTS the tx-id tag here (the sole writer of a tag on the model),
   *     and a scion sowing a sub-scion reads it back via {@link #transactionId()}
   */
  public ScenarioCellar(Supplier<ReportModel> model, Supplier<Cellar> durableReads, String txId) {
    this.model = model;
    this.durableReads = durableReads;
    this.txId = txId;
    if (!txId.isEmpty()) {
      model.get().addTag(Tag.TRANSACTION.of(txId));
    }
  }

  /** The run's transaction id — what a scion passes on when it sows a sub-scion (correlation). */
  public String transactionId() {
    return txId;
  }

  /**
   * One accumulated cellar entry as it rides the model: the {@link Parcel} it is filed under and
   * the encoded {@link SeedEnvelope}. Flat (the codec serialises it to the tag's value String); the
   * drain decodes it back and re-stores the envelope opaquely.
   */
  public record Entry(Parcel parcel, SeedEnvelope envelope) {}

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
  public <T> void store(Parcel parcel, SeedCoordinate coordinate, T value) {
    final SeedEnvelope envelope = SeedEnvelope.of(coordinate, codec.encode(value));
    final String entryJson = codec.encode(new Entry(parcel, envelope));
    model.get().addTag(Tag.ENTRY.of(entryJson));
  }

  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    return durableReads.get().fetch(parcel, type);
  }

  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return durableReads.get().fetch(parcel, coordinate, type);
  }

  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return durableReads.get().withdraw(parcel, coordinate, type);
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return durableReads.get().neighbours(parcel);
  }
}
