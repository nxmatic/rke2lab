package io.nxmatic.rke2lab.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.contract.HostDriftEntry;
import io.nxmatic.rke2lab.manifests.contract.HostLiveEntry;
import io.nxmatic.rke2lab.manifests.contract.HostStagingEntry;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins I6a-B2: the HEAD is a last-wins fold of the cellar timeline. The pivot the grow needs
 * ({@code liveSyncedFrom}) is the last {@code host-live} entry; stagings and drifts fold per path.
 */
class HostTreeHeadTest {

  private static final SeedCodec CODEC = new SeedCodec();

  private static SeedEnvelope staging(String root, String hash) {
    return new SeedEnvelope(
        "manifests",
        "host-staging",
        CODEC.encode(HostStagingEntry.of(root, Map.of("f", hash), "manifests-synthesis")));
  }

  private static SeedEnvelope live(String syncedFrom) {
    return new SeedEnvelope("host", "host-live", CODEC.encode(HostLiveEntry.of(syncedFrom)));
  }

  private static SeedEnvelope drift(String driftRoot, String evicted) {
    return new SeedEnvelope(
        "host", "host-drift", CODEC.encode(HostDriftEntry.of(driftRoot, evicted)));
  }

  @Test
  void an_empty_timeline_folds_to_an_empty_head() {
    final HostTreeHead head = HostTreeHead.fold(List.of(), CODEC);
    assertTrue(head.liveSyncedFrom().isEmpty(), "no live entry → no pivot");
    assertTrue(head.stagings().isEmpty());
    assertTrue(head.drifts().isEmpty());
  }

  @Test
  void the_last_live_entry_wins_as_the_pivot() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                staging("/host.staging.0", "a"), live("/host.staging.0"), live("/host.staging.2")),
            CODEC);
    assertEquals("/host.staging.2", head.liveSyncedFrom().orElseThrow(), "last live entry wins");
  }

  @Test
  void a_recycled_staging_path_supersedes_the_old_entry() {
    // The same path re-materialised (bounded rotation) — the later checksum must win.
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(staging("/host.staging.0", "old"), staging("/host.staging.0", "new")), CODEC);
    assertEquals(1, head.stagings().size(), "one entry per path");
    assertEquals("new", head.stagings().get("/host.staging.0").checksums().get("f"), "last-wins");
  }

  @Test
  void stagings_drifts_and_live_fold_side_by_side() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                staging("/host.staging.0", "a"),
                staging("/host.staging.1", "b"),
                live("/host.staging.1"),
                drift("/host.drift.0", "/host.staging.0")),
            CODEC);
    assertEquals("/host.staging.1", head.liveSyncedFrom().orElseThrow());
    assertEquals(2, head.stagings().size());
    assertEquals(1, head.drifts().size());
    assertEquals("/host.staging.0", head.drifts().get("/host.drift.0").evictedSyncedFrom());
  }

  @Test
  void unrelated_envelopes_are_ignored() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                new SeedEnvelope("bbox", "some-harvest", "{}"), staging("/host.staging.0", "a")),
            CODEC);
    assertEquals(1, head.stagings().size(), "a non-host-tree envelope is not this fold's concern");
    assertFalse(head.stagings().isEmpty());
  }
}
