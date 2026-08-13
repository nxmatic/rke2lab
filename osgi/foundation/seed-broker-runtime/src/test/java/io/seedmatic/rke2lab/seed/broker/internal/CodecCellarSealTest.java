package io.seedmatic.rke2lab.seed.broker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.codec.PassphraseCellarCipher;
import io.seedmatic.rke2lab.seed.broker.port.Breadcrumb;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The seal path THROUGH the typed cellar: a {@link Sensitivity#SEALED} store leaves ciphertext in
 * the opaque backend (the harvest plaintext never reaches the host store), and fetch reveals it
 * back — while a {@link Sensitivity#PLAIN} store files the value in clear. Proves the OSGi-side
 * clean / smudge wiring end to end over an in-memory backend (no Pulumi).
 */
class CodecCellarSealTest {

  private static final Parcel PARCEL = new Parcel("bioskop", "dev");

  private enum Coord implements SeedCoordinate {
    SECRET;

    @Override
    public String slug() {
      return "test-secret";
    }

    @Override
    public String domain() {
      return "test";
    }
  }

  private record Secret(String clientKey) {}

  @Test
  void aSealedStoreLeavesCiphertextInTheBackendAndFetchReveals() {
    final CapturingOpaque backend = new CapturingOpaque();
    final CodecCellar cellar = new CodecCellar(backend, new PassphraseCellarCipher());
    final Secret secret = new Secret("-----BEGIN KEY-----topsecret");

    cellar.store(PARCEL, Coord.SECRET, secret, Sensitivity.SEALED);

    final String stored = backend.last().payload();
    assertFalse(
        stored.contains("topsecret"),
        "the backend holds ciphertext — the harvest plaintext never crossed to the host store");
    assertTrue(stored.startsWith("cellar:sealed:"), "the stored payload self-identifies as sealed");
    assertEquals(
        secret,
        cellar.fetch(PARCEL, Coord.SECRET, Secret.class).orElseThrow(),
        "fetch reveals the sealed harvest back to the original value");
  }

  @Test
  void aPlainStoreLeavesTheHarvestInClear() {
    final CapturingOpaque backend = new CapturingOpaque();
    final CodecCellar cellar = new CodecCellar(backend, new PassphraseCellarCipher());

    cellar.store(PARCEL, Coord.SECRET, new Secret("reservations-summary"));

    assertTrue(
        backend.last().payload().contains("reservations-summary"),
        "a PLAIN store files the harvest in clear (the default)");
    assertEquals(
        new Secret("reservations-summary"),
        cellar.fetch(PARCEL, Coord.SECRET, Secret.class).orElseThrow(),
        "a PLAIN value round-trips untouched (reveal is a no-op on a non-sealed payload)");
  }

  @Test
  void trailOfReadsThePersistedTrailOffTheOpaqueEnvelope() {
    // The durable read path: the typed cellar exposes a value's fil d'Ariane by reading the CLEAR
    // trail off the envelope the opaque backend hands back — no decode, no reveal, so a SEALED
    // value's lineage is traceable without the passphrase (§ fil-d-ariane, the durable extension).
    final CapturingOpaque backend = new CapturingOpaque();
    final CodecCellar cellar = new CodecCellar(backend, new PassphraseCellarCipher());
    final Trail trail =
        new Trail(
            List.of(
                new Breadcrumb("worktree", "run-provenance", "abc123", true),
                new Breadcrumb("test", "test-secret", "abc123", true)));
    backend.store(
        PARCEL, new SeedEnvelope("test", "test-secret", "cellar:sealed:v1:blob").withTrail(trail));

    assertEquals(
        Optional.of(trail),
        cellar.trailOf(PARCEL, Coord.SECRET),
        "trailOf returns the persisted trail on the durable read path");
  }

  /** Minimal in-memory backend: last-wins per coordinate, keeping the last stored envelope. */
  private static final class CapturingOpaque implements OpaqueCellar {

    private final Map<String, SeedEnvelope> byCoordinate = new LinkedHashMap<>();
    private SeedEnvelope last;

    @Override
    public void store(Parcel parcel, SeedEnvelope vegetal) {
      this.last = vegetal;
      byCoordinate.put(vegetal.coordinate(), vegetal);
    }

    SeedEnvelope last() {
      return last;
    }

    @Override
    public List<SeedEnvelope> fetch(Parcel parcel) {
      return List.copyOf(byCoordinate.values());
    }

    @Override
    public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.ofNullable(byCoordinate.get(coordinate.slug()));
    }

    @Override
    public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.ofNullable(byCoordinate.remove(coordinate.slug()));
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of(parcel);
    }
  }
}
