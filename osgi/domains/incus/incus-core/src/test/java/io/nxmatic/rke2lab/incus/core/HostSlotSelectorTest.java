package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code host.<N>.staging.d} rotation: the CELLAR is the state (not the filesystem), the
 * next slot is {@code (max present + 1) mod 3} — no staging entry yields 0, a partial set fills the
 * gap upward, and a full set wraps to overwrite the oldest position. The occupancy is the {@code
 * host-staging} entries the cellar holds; the pinned {@code live.syncedFrom} is its {@code
 * host-live} entry. {@code nodeRoot} only prefixes the returned path — no directory is read.
 */
class HostSlotSelectorTest {

  private static final Path NODE_ROOT = Path.of("/srv/host/.local.d/bioskop/bioskop-master");
  private static final Parcel PARCEL = new Parcel("bioskop-master", "dev");

  private static Path stagingDir(int slot) {
    return NODE_ROOT.resolve("host." + slot + ".staging.d");
  }

  /**
   * File a {@code host-staging} entry per present slot — its stagingRoot is what the selector
   * reads.
   */
  private static void seedStagings(Cellar cellar, int... slots) {
    for (int slot : slots) {
      cellar.store(
          PARCEL,
          IncusCoordinate.HOST_STAGING,
          HostStagingEntry.of(
              stagingDir(slot).toString(),
              Map.of(),
              new HostStagingEntry.Provenance("sha", false)));
    }
  }

  @Test
  void noStagingEntry_yieldsSlotZero() {
    assertEquals(
        stagingDir(0),
        new HostSlotSelector(NODE_ROOT, new InMemoryCellar(), PARCEL).nextStaging(),
        "a fresh cellar (no host-staging) materialises into slot 0");
  }

  @Test
  void slotsZeroAndOnePresent_yieldsSlotTwo() {
    final Cellar cellar = new InMemoryCellar();
    seedStagings(cellar, 0, 1);
    assertEquals(
        stagingDir(2),
        new HostSlotSelector(NODE_ROOT, cellar, PARCEL).nextStaging(),
        "with {0,1} present the next is 2");
  }

  @Test
  void fullRotation_wrapsToZero() {
    final Cellar cellar = new InMemoryCellar();
    seedStagings(cellar, 0, 1, 2);
    assertEquals(
        stagingDir(0),
        new HostSlotSelector(NODE_ROOT, cellar, PARCEL).nextStaging(),
        "with {0,1,2} present the rotation wraps to overwrite the oldest position (0)");
  }

  @Test
  void pinnedSlot_isSkipped_whenTheRotationWouldLandOnIt() {
    // {0,1} present → naive next is 2; but the live mirrors slot 2, materialising there would
    // overwrite the deltas' pivot tree. The selector steps forward to the next free position (0).
    final Cellar cellar = new InMemoryCellar();
    seedStagings(cellar, 0, 1);
    cellar.store(PARCEL, IncusCoordinate.HOST_LIVE, HostLiveEntry.of(stagingDir(2).toString()));
    assertEquals(
        stagingDir(0),
        new HostSlotSelector(NODE_ROOT, cellar, PARCEL).nextStaging(),
        "the pinned live.syncedFrom slot is never overwritten — the rotation steps past it");
  }

  @Test
  void pinnedSlot_isIgnored_whenTheRotationDoesNotLandOnIt() {
    // {0,1} present → naive next is 2; the live mirrors slot 0, which the rotation would not touch
    // anyway, so pinning changes nothing.
    final Cellar cellar = new InMemoryCellar();
    seedStagings(cellar, 0, 1);
    cellar.store(PARCEL, IncusCoordinate.HOST_LIVE, HostLiveEntry.of(stagingDir(0).toString()));
    assertEquals(
        stagingDir(2),
        new HostSlotSelector(NODE_ROOT, cellar, PARCEL).nextStaging(),
        "pinning a slot the rotation avoids anyway leaves the choice unchanged");
  }
}
