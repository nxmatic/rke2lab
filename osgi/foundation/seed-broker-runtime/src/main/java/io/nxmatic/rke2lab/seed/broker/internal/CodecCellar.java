package io.nxmatic.rke2lab.seed.broker.internal;

import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

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

  @Activate
  public CodecCellar(@Reference OpaqueCellar opaque) {
    this.opaque = opaque;
  }

  @Override
  public <T> void store(Parcel parcel, SeedCoordinate coordinate, T value) {
    opaque.store(
        parcel, new SeedEnvelope(coordinate.domain(), coordinate.slug(), codec.encode(value)));
  }

  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    // Fail-at-end: decode every readable entry, skip the ones the codec cannot read into type
    // (a malformed or foreign entry does not sink the fold — the tolerance the *Reader classes
    // had).
    final List<T> decoded = new ArrayList<>();
    for (SeedEnvelope envelope : opaque.fetch(parcel)) {
      try {
        decoded.add(codec.decode(envelope, type));
      } catch (RuntimeException skip) {
        // not readable into type (a tombstone, a foreign coordinate, a malformed payload) — skip
        // it.
      }
    }
    return decoded;
  }

  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return opaque.fetch(parcel, coordinate).map(envelope -> codec.decode(envelope, type));
  }

  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    return opaque.withdraw(parcel, coordinate).map(envelope -> codec.decode(envelope, type));
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return opaque.neighbours(parcel);
  }
}
