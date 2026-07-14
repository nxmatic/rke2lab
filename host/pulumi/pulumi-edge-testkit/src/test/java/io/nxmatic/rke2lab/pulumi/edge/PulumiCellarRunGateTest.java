package io.nxmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the H2 behaviour: the {@link PulumiCellar} consults its injected {@link RunGate} and routes
 * a {@code store} to {@code up()} (conserve) when cultivating, {@code preview()} (pre-reserve, no
 * history entry) when surveying — the scion's single neutral {@code store} verb, its mode chosen by
 * the cellar, never the scion.
 *
 * <p>The proof is end-to-end over a real file:// backend (the {@code pulumi} binary is on the flox
 * PATH): a cultivating store then {@link PulumiCellar#fetch} sees the conserved envelope; a
 * surveying store then {@code fetch} sees nothing (the preview computed the plan but touched no
 * state). The {@code CellarEntry} program is an inert component resource, so {@code up()} runs
 * offline with no cloud provider.
 */
class PulumiCellarRunGateTest {

  private static final String PROJECT = "rke2lab";
  private static final String STACK = "dev";
  private static final Parcel PARCEL = new Parcel(PROJECT, STACK);

  private static PulumiCellar cellarOver(Path backend, boolean cultivating) {
    final RunGate gate = () -> cultivating;
    return new PulumiCellar(Optional.of(backend), gate, line -> {});
  }

  private static SeedEnvelope harvest() {
    return new SeedEnvelope("incus", "incus-prep", "{\"recipeDigest\":\"abc\",\"soil\":\"/plot\"}");
  }

  @Test
  void cultivating_store_conserves_the_harvest(@TempDir Path backend) {
    cellarOver(backend, true).store(PARCEL, harvest());

    final List<SeedEnvelope> reaped = cellarOver(backend, true).fetch(PARCEL);
    assertEquals(1, reaped.size(), "a cultivating store (up) conserves one history entry");
    assertEquals(
        "incus-prep", reaped.get(0).coordinate(), "the conserved envelope keeps its coordinate");
  }

  @Test
  void surveying_store_pre_reserves_without_conserving(@TempDir Path backend) {
    cellarOver(backend, false).store(PARCEL, harvest());

    final List<SeedEnvelope> reaped = cellarOver(backend, true).fetch(PARCEL);
    assertTrue(
        reaped.isEmpty(), "a surveying store (preview) touches no state — nothing conserved");
  }
}
