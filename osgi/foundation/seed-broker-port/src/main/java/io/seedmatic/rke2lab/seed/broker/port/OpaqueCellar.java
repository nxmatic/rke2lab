package io.seedmatic.rke2lab.seed.broker.port;

import java.util.List;
import java.util.Optional;

/**
 * The OPAQUE cellar — the commissioner's addressable store seen as sealed envelopes only, a FRIDGE
 * (put / peek / take) addressed by a neutral {@link Parcel} (the fridge) and a {@link
 * SeedCoordinate} (the case within it). This is the seam the host EDGE provides (over the Pulumi
 * backend) and the realisation every backend implements ({@code PulumiCellar} today, a git-backed
 * one in the cluster version tomorrow). It never opens a payload — it knows the parcel, the
 * coordinate, and the gesture, nothing of contents.
 *
 * <p>Two axes the earlier "append-only" framing conflated: CURRENT state is mutable (put/peek/take,
 * a Map per coordinate — {@code store} / {@code fetch(coordinate)} / {@code withdraw}), while the
 * backend's own revision log is the append-only AUDIT (the {@code fetch(Parcel)} timeline walk).
 * The doctor's usage is a JOURNAL (accumulate, never {@code withdraw}); the staging rotation is a
 * RING (the usage that justifies {@code withdraw}). One primitive, several usages.
 *
 * <p>The TYPED (codec-aware) view sits ABOVE this as {@link Cellar}: a decorator holding a {@link
 * SeedCoordinate}-keyed codec wraps an {@code OpaqueCellar} and adds the decode. So the seam stays
 * codec-free (no bundle type is a compile link across it), and the decode lives in the realm that
 * owns the types. See docs/architecture/osgi/seed-broker-spec.adoc (§ the cellar is a fridge).
 */
public interface OpaqueCellar {

  /**
   * File a sealed végétal at its coordinate (the case). The store never reads the payload; it files
   * the sealed envelope under its coordinate.
   */
  void store(Parcel parcel, SeedEnvelope vegetal);

  /**
   * Open the fridge and list what is there — the parcel's timeline, oldest first, one opaque {@link
   * SeedEnvelope} per readable entry. NOT "harvest" (the field's hand-over gesture): this is
   * retrieval. An absent or empty cellar yields an empty list (a legitimate nothing-here); a
   * present-but-unreadable store degrades to the readable prefix rather than throwing.
   */
  List<SeedEnvelope> fetch(Parcel parcel);

  /**
   * Peek ONE case — the current envelope at {@code coordinate} (the last-wins fold of the
   * timeline), or {@link Optional#empty()} if the case is empty (never stored, or withdrawn). An
   * empty case is a legitimate state, not an error — hence {@code Optional}, not {@code null} nor a
   * throw.
   */
  Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate);

  /**
   * TAKE one case out — hand back its current envelope ({@link Optional#empty()} if already empty)
   * and leave the case EMPTY: a subsequent {@link #fetch(Parcel, SeedCoordinate)} at {@code
   * coordinate} yields empty until re-stored (the fridge, not an append-only journal — the ring
   * rotation is the usage this serves). The current state is a fold of the timeline, so the take is
   * a tombstone the fold reads as most-recent; the backend's history keeps the trace (the audit
   * does not lie).
   */
  Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate);

  /**
   * The parcel's neighbouring cellars — the sibling parcels sharing the same soil (the parcel's own
   * first). Sibling enumeration is Layer-1 host knowledge (which stacks exist under the backend);
   * any grant filter is applied by the consumer, not here. With no backend, the neighbourhood is
   * just the parcel itself.
   */
  List<Parcel> neighbours(Parcel parcel);

  /**
   * Commit the parcel's staged current-state to the soil — the end-of-drain flush. A backend that
   * files each {@link #store} / {@link #withdraw} eagerly (a separate-run {@code up}, a git commit)
   * has nothing to flush and keeps the default no-op. The backend that shares the RUN's own stack
   * cannot file eagerly — its coquilles are resources of the run's single deployment, and Pulumi
   * makes one {@code up} authoritative over the whole desired set — so it STAGES store/withdraw
   * and, here, re-declares the full live set at once (carry-forward + this run's changes), a
   * coordinate absent from the set being naturally reaped. Idempotent, and a no-op when nothing was
   * staged for this parcel.
   */
  default void conserve(Parcel parcel) {}
}
