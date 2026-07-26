package io.nxmatic.rke2lab.seed.broker.port;

import java.util.List;
import java.util.Optional;

/**
 * The TYPED cellar — the codec-aware view of the fridge, its cases peeked/taken as decoded domain
 * values and its timeline read decoded. It exists ONLY in the OSGi world (the realm that owns the
 * domain types and holds a {@code SeedCodec}); the host EDGE never references it — a host provides
 * and consumes only the opaque {@link OpaqueCellar}.
 *
 * <p>It does NOT extend {@link OpaqueCellar}: the realisation ({@code CodecCellar}) is a DECORATOR
 * that takes an {@code OpaqueCellar} by REFERENCE (an OSGi component) and adds the codec, rather
 * than an interface that inherits the opaque verbs. So the two faces stay separable — the opaque
 * one is the seam the host implements, the typed one is the OSGi-only decode layer over it.
 *
 * <p>The typed reads DELEGATE to the realm's codec, which reflects on the {@link Class} passed here
 * — no bundle type is a compile link across the seam ({@code Class<T>} carries its own loader). A
 * consumer needing per-entry tolerance uses {@link #fetch(Parcel, Class)}, which SKIPS the
 * unreadable entries (fail-at-end, the tolerance the {@code *Reader} classes had).
 */
public interface Cellar {

  /**
   * File a decoded value at its coordinate with an explicit {@link Sensitivity} — the typed twin of
   * {@link OpaqueCellar#store}: the impl encodes {@code value} under {@code coordinate} via the
   * realm's codec, SEALS it through the {@code CellarCipher} when {@code sensitivity} is {@link
   * Sensitivity#SEALED} (so its plaintext never rides the run's write set nor reaches the durable
   * backend), then files the envelope. The scion DECLARES the sensitivity here, where the harvest
   * is born (§ cellar-secrets, re-declare where born).
   */
  <T> void store(Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity);

  /**
   * File a decoded value in clear — the {@link Sensitivity#PLAIN} convenience over {@link
   * #store(Parcel, SeedCoordinate, Object, Sensitivity)}, the common case (most harvests hold
   * nothing secret). A scion with a secret harvest calls the four-arg form with {@link
   * Sensitivity#SEALED}.
   */
  default <T> void store(Parcel parcel, SeedCoordinate coordinate, T value) {
    store(parcel, coordinate, value, Sensitivity.PLAIN);
  }

  /**
   * The whole timeline DECODED into {@code type} — one {@code T} per readable entry, oldest first
   * (the doctor's medical record, the ledger). Fail-at-end: an entry the codec cannot read into
   * {@code type} is SKIPPED, the fold continues on the rest. Tombstones (a withdrawn case's marker)
   * are not entries and are skipped too.
   */
  <T> List<T> fetch(Parcel parcel, Class<T> type);

  /**
   * Peek ONE case, DECODED — the current value at {@code coordinate} decoded into {@code type}, or
   * {@link Optional#empty()} if the case is empty (never stored, or withdrawn).
   */
  <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type);

  /**
   * TAKE one case out, DECODED — hand back the withdrawn value decoded into {@code type} ({@link
   * Optional#empty()} if the case was empty) and leave the case empty until re-stored.
   */
  <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type);

  /**
   * The parcel's neighbouring cellars — the sibling parcels sharing the same soil (the parcel's own
   * first). Codec-NEUTRAL (it names only {@link Parcel}s, no envelope, no decode), so it rides the
   * typed face too — a consumer folding a cohort (the doctor) references one {@code Cellar}, not
   * also the opaque one. Delegated verbatim to the underlying {@link OpaqueCellar}.
   */
  List<Parcel> neighbours(Parcel parcel);
}
