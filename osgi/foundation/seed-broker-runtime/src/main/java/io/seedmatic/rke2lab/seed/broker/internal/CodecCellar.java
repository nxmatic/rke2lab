package io.seedmatic.rke2lab.seed.broker.internal;

import io.seedmatic.rke2lab.seed.broker.codec.PassphraseCellarCipher;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.CellarCipher;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * The TYPED cellar realisation — a DECORATOR over the opaque one. It takes an {@link OpaqueCellar}
 * (the seam the host provides) by {@code @Reference} and adds the {@link SeedCodec} decode, so the
 * two faces stay separable: the host implements only the opaque verbs, OSGi consumers see the typed
 * {@link Cellar}. Published as the {@code Cellar} service the domains {@code @Reference}.
 *
 * <p>The decode reflects on the {@link Class} the caller passes (no bundle type is a compile link
 * across the seam — the {@code Class<T>} carries its own loader), so this ONE component decodes any
 * domain type whose record the codec's realm can reach. {@link #fetch(Parcel, Class)} is
 * fail-at-end: an entry the codec cannot read is skipped, the fold continues (the tolerance the
 * doctor's {@code *Reader} classes had); the single-case peeks propagate the codec's throw.
 */
@Component(service = Cellar.class)
public final class CodecCellar implements Cellar {

  private final OpaqueCellar opaque;
  private final SeedCodec codec = new SeedCodec();
  // The clean/smudge filter (§ cellar-secrets). Sealing happens HERE, OSGi-side, before the
  // envelope
  // crosses to the host backend — so a SEALED harvest's plaintext never leaves the realm. The
  // cipher
  // is the injected CellarCipher service (age) when its bundle is provisioned; where it is absent
  // the
  // mono passphrase stand-in fills in.
  private final CellarCipher cipher;

  @Activate
  public CodecCellar(
      @Reference OpaqueCellar opaque,
      @Reference(cardinality = ReferenceCardinality.OPTIONAL) CellarCipher cipher) {
    this.opaque = opaque;
    this.cipher = cipher != null ? cipher : new PassphraseCellarCipher();
  }

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
    final String encoded = codec.encode(value);
    final String payload = sensitivity == Sensitivity.SEALED ? cipher.seal(encoded) : encoded;
    opaque.store(parcel, new SeedEnvelope(coordinate.domain(), coordinate.slug(), payload));
  }

  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    // Fail-at-end: decode every readable entry, skip the ones the codec cannot read into type
    // (a malformed or foreign entry does not sink the fold — the tolerance the *Reader classes
    // had).
    final List<T> decoded = new ArrayList<>();
    for (SeedEnvelope envelope : opaque.fetch(parcel)) {
      try {
        decoded.add(codec.decode(revealed(envelope), type));
      } catch (RuntimeException skip) {
        // not readable into type (a tombstone, a foreign coordinate, a malformed payload) — skip
        // it.
      }
    }
    return decoded;
  }

  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return opaque.fetch(parcel, coordinate).map(envelope -> codec.decode(revealed(envelope), type));
  }

  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return opaque
        .withdraw(parcel, coordinate)
        .map(envelope -> codec.decode(revealed(envelope), type));
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return opaque.neighbours(parcel);
  }

  /**
   * The current value's fil d'Ariane at {@code coordinate} — read CLEAR off the opaque envelope the
   * backend hands back, WITHOUT revealing the (possibly SEALED) payload. This is the DURABLE read
   * path the {@code ScenarioCellar} default cannot serve: the durable edge now carries the trail in
   * its coquille (§ fil-d-ariane, the durable extension), so a value sealed in one run exposes its
   * lineage in a later run without the passphrase. Empty when the case is empty.
   */
  @Override
  public Optional<Trail> trailOf(Parcel parcel, SeedCoordinate coordinate) {
    return opaque.fetch(parcel, coordinate).map(SeedEnvelope::trail);
  }

  /**
   * Smudge — reveal a fetched envelope's payload before it is decoded. Self-identifying: a {@link
   * Sensitivity#PLAIN} store (never sealed) passes through untouched, so the same read path serves
   * sealed and clear. Keeps the coordinate ({@code domain}/{@code coordinate}) so the {@code
   * decode(SeedEnvelope, …)} contract-guard still fires.
   */
  private SeedEnvelope revealed(SeedEnvelope envelope) {
    return new SeedEnvelope(
        envelope.domain(), envelope.coordinate(), cipher.reveal(envelope.payload()));
  }
}
